package com.xptempo.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP client for the XPTempo API. Every call is asynchronous (OkHttp enqueue) and never runs on the client
 * thread. Batches are sent one at a time in order; failures retry the same batch (same batchId, so the server
 * can deduplicate) with exponential back-off from 5 s to 5 min plus jitter. There is only ever one retry timer,
 * new batches wait for it, and a backlog is sent at a gentle pace once the server answers again.
 */
@Slf4j
public final class XPTempoApi
{
	public interface Listener
	{
		/** The server answered for this batch; `sent` is the batch the ack belongs to. */
		void onAck(Payloads.Batch sent, Payloads.Ack ack);

		void onUnauthorized();

		void onFailure(String message, boolean willRetry);
	}

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final long BASE_BACKOFF_MS = 5_000L;
	private static final long MAX_BACKOFF_MS = 300_000L;
	/** Gap between batches while catching up on a backlog, so recovering servers are not flooded. */
	private static final long BACKLOG_GAP_MS = 750L;

	private final OkHttpClient http;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Listener listener;
	private final BatchQueue queue = new BatchQueue();
	private final String userAgent;

	private volatile HttpUrl baseUrl;
	private volatile String apiKey = "";
	private volatile boolean paused;
	private volatile boolean closed;
	private boolean sending;
	private int failures;
	/**
	 * No send before this time (the single retry timer, or the pause between backlog batches). Monotonic, on the
	 * same clock as the executor's timer, so a wall-clock adjustment can never strand a wake-up.
	 */
	private long notBeforeMs;
	private ScheduledFuture<?> wakeUp;
	private CompletableFuture<Void> drained;

	public XPTempoApi(OkHttpClient base, Gson gson, ScheduledExecutorService executor, String userAgent, Listener listener)
	{
		// Derive from RuneLite's shared client (Hub rule: never construct your own); only the call timeout changes.
		this.http = base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build();
		this.gson = gson;
		this.executor = executor;
		this.userAgent = userAgent;
		this.listener = listener;
	}

	/** Where every player's plugin sends data. Fixed: the service lives at api.xptempo.com. */
	public static final String PRODUCTION_BASE_URL = "https://api.xptempo.com";

	/**
	 * The production address. A developer running the plugin from source can point it at an API on their own
	 * machine with -Dxptempo.api=http://127.0.0.1:3000; any other host is ignored, so data only ever goes to
	 * api.xptempo.com or the developer's own computer.
	 */
	public static String baseUrl()
	{
		String override = System.getProperty("xptempo.api");
		HttpUrl url = override == null ? null : HttpUrl.parse(override.trim());
		boolean local = url != null && ("127.0.0.1".equals(url.host()) || "localhost".equals(url.host()));
		return local ? url.toString() : PRODUCTION_BASE_URL;
	}

	/** Called at start-up and whenever the config changes. A new key un-pauses sending after a 401. */

	public void configure(String baseUrlText, String apiKey)
	{
		HttpUrl parsed = HttpUrl.parse(baseUrlText == null ? "" : baseUrlText.trim());
		this.baseUrl = parsed;
		String key = apiKey == null ? "" : apiKey.trim();
		if (!key.equals(this.apiKey))
		{
			this.apiKey = key;
			this.paused = false;
			executor.execute(this::sendNext);
		}
	}

	public boolean isConfigured()
	{
		return baseUrl != null && !apiKey.isEmpty();
	}

	public int queuedBatches()
	{
		return queue.size();
	}

	/** Exchanges a pairing code for an API key (no auth; the code is single-use). */
	public void pair(String code, String clientName, Consumer<Payloads.PairResponse> onSuccess, Consumer<String> onError)
	{
		HttpUrl url = baseUrl;
		if (url == null)
		{
			onError.accept("API base URL is not valid");
			return;
		}
		Payloads.PairRequest body = new Payloads.PairRequest();
		body.code = code;
		body.clientName = clientName;
		Request request = new Request.Builder()
			.url(url.newBuilder().addPathSegments("v1/pair").build())
			.header("User-Agent", userAgent)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("could not reach the XPTempo API: " + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					String text = bodyText(r.body());
					if (r.isSuccessful())
					{
						onSuccess.accept(gson.fromJson(text, Payloads.PairResponse.class));
					}
					else
					{
						onError.accept(errorMessage(r.code(), text));
					}
				}
				catch (IOException | JsonSyntaxException e)
				{
					onError.accept("bad response from the XPTempo API: " + e.getMessage());
				}
			}
		});
	}

	public void submit(Payloads.Batch batch)
	{
		if (closed)
		{
			return;
		}
		queue.add(batch);
		executor.execute(this::sendNext);
	}

	/**
	 * Uploads were switched off: forget everything not yet sent and send only this last batch (the
	 * session_end), so the server stops counting the session. Null just clears the queue.
	 */
	public void discardAllExcept(Payloads.Batch last)
	{
		queue.clear();
		if (last != null)
		{
			submit(last);
		}
	}

	/** A category was switched off: its events leave every queued batch that has not been attempted yet. */
	public void dropQueuedEvents(Predicate<Map<String, Object>> drop)
	{
		queue.removeEvents(drop);
	}

	/** The plugin is shutting down: stop retrying, drop the queue, ignore further submissions. */
	public void close()
	{
		ScheduledFuture<?> timer;
		synchronized (this)
		{
			closed = true;
			timer = wakeUp;
			wakeUp = null;
		}
		if (timer != null)
		{
			timer.cancel(false);
		}
		queue.clear();
		completeDrainedIfIdle();
	}

	/** For ClientShutdown: completes when the queue is empty, paused, or the next attempt fails. */
	public CompletableFuture<Void> flush()
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		synchronized (this)
		{
			drained = future;
		}
		executor.execute(this::sendNext);
		return future;
	}

	private void sendNext()
	{
		Payloads.Batch batch;
		Request request;
		synchronized (this)
		{
			if (closed || sending || paused || queue.isEmpty() || !isConfigured())
			{
				completeDrainedIfIdle();
				return;
			}
			long wait = notBeforeMs - nowMs();
			// While the client waits to shut down, a backlog goes out without the pacing gap (but never early in a back-off).
			if (wait > 0 && !(failures == 0 && drained != null))
			{
				// Backing off or pacing: one timer wakes the queue, new batches just wait for it.
				schedule(wait);
				if (failures > 0 && drained != null)
				{
					drained.complete(null); // never hold the client's shutdown for a retry window
					drained = null;
				}
				return;
			}
			batch = queue.take();
			if (batch == null)
			{
				completeDrainedIfIdle(); // emptied by an opt-out between the checks
				return;
			}
			request = new Request.Builder()
				.url(baseUrl.newBuilder().addPathSegments("v1/ingest").build())
				.header("Authorization", "Bearer " + apiKey)
				.header("User-Agent", userAgent)
				.post(RequestBody.create(JSON, gson.toJson(batch)))
				.build();
			sending = true;
		}
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				// No connection was made, so the server cannot have applied it: it may be changed or merged again.
				boolean neverArrived = e instanceof ConnectException || e instanceof UnknownHostException || e instanceof NoRouteToHostException;
				if (neverArrived)
				{
					queue.notApplied(batch);
				}
				else
				{
					queue.release(batch);
				}
				scheduleRetry("network error: " + e.getMessage(), -1);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				int code;
				String text;
				long retryAfterMs = -1;
				try (Response r = response)
				{
					code = r.code();
					text = bodyText(r.body());
					String retryAfter = r.header("Retry-After");
					if (retryAfter != null)
					{
						try
						{
							retryAfterMs = Long.parseLong(retryAfter.trim()) * 1000L;
						}
						catch (NumberFormatException ignored)
						{
							// not a delay in seconds; use the normal back-off
						}
					}
				}
				catch (IOException e)
				{
					queue.release(batch);
					scheduleRetry("could not read response: " + e.getMessage(), -1);
					return;
				}
				handleResponse(batch, code, text, retryAfterMs);
			}
		});
	}

	/** Every branch clears `sending` in the same locked step that records the outcome, so no send can slip in between. */
	private void handleResponse(Payloads.Batch batch, int code, String text, long retryAfterMs)
	{
		if (code >= 200 && code < 300)
		{
			boolean moreAfter = queue.size() > 1;
			queue.removeHead(batch);
			synchronized (this)
			{
				sending = false;
				// Catching up after an outage: space the backlog out (with jitter) instead of sending it all at once,
				// unless the client is waiting to shut down.
				boolean pace = (failures > 0 || moreAfter) && !queue.isEmpty() && drained == null;
				failures = 0;
				notBeforeMs = pace ? nowMs() + BACKLOG_GAP_MS + ThreadLocalRandom.current().nextLong(BACKLOG_GAP_MS) : 0;
			}
			try
			{
				Payloads.Ack ack = gson.fromJson(text, Payloads.Ack.class);
				if (ack != null)
				{
					listener.onAck(batch, ack);
				}
			}
			catch (JsonSyntaxException e)
			{
				log.warn("XPTempo: unreadable ack: {}", e.getMessage());
			}
			executor.execute(this::sendNext);
			return;
		}
		if (code == 401)
		{
			queue.release(batch);
			synchronized (this)
			{
				sending = false;
				paused = true;
			}
			listener.onUnauthorized();
			completeDrainedIfIdle();
			return;
		}
		if (code == 413)
		{
			synchronized (this)
			{
				sending = false;
			}
			if (!queue.splitHead(batch))
			{
				queue.removeHead(batch);
				listener.onFailure("batch too large and cannot be split; dropped", false);
			}
			executor.execute(this::sendNext);
			return;
		}
		if (code == 429 || code >= 500)
		{
			if (code == 429 || code == 503)
			{
				queue.notApplied(batch); // refused before anything was applied
			}
			else
			{
				queue.release(batch);
			}
			scheduleRetry(errorMessage(code, text), retryAfterMs);
			return;
		}
		// Any other 4xx: the batch itself is unacceptable; drop it rather than block the queue forever.
		synchronized (this)
		{
			sending = false;
		}
		queue.removeHead(batch);
		listener.onFailure(errorMessage(code, text) + "; batch dropped", false);
		executor.execute(this::sendNext);
	}

	private void scheduleRetry(String message, long retryAfterMs)
	{
		long delay;
		synchronized (this)
		{
			sending = false;
			if (closed)
			{
				return;
			}
			failures++;
			int shift = Math.min(failures - 1, 6);
			long base = retryAfterMs > 0 ? Math.min(MAX_BACKOFF_MS, retryAfterMs) : Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << shift);
			// Jitter on top (also of Retry-After), so thousands of clients do not come back in the same second.
			delay = base + ThreadLocalRandom.current().nextLong(base / 4 + 1);
			notBeforeMs = nowMs() + delay;
			schedule(delay);
			// Never hold the client's shutdown hook for a retry window.
			if (drained != null)
			{
				drained.complete(null);
				drained = null;
			}
		}
		listener.onFailure(message + " (retrying in " + (delay / 1000) + " s)", true);
	}

	/** Arms the single wake-up timer (replacing an earlier, later one). Caller holds the lock. */
	private void schedule(long delayMs)
	{
		if (closed)
		{
			return;
		}
		if (wakeUp != null && !wakeUp.isDone())
		{
			long remaining = wakeUp.getDelay(TimeUnit.MILLISECONDS);
			if (remaining > 0 && remaining <= delayMs)
			{
				return; // an earlier wake-up is already armed (a timer that is firing right now does not count)
			}
			wakeUp.cancel(false);
		}
		ScheduledFuture<?>[] self = new ScheduledFuture<?>[1];
		self[0] = executor.schedule(() ->
		{
			synchronized (XPTempoApi.this)
			{
				if (wakeUp == self[0])
				{
					wakeUp = null;
				}
			}
			sendNext();
		}, delayMs, TimeUnit.MILLISECONDS);
		wakeUp = self[0];
	}

	private static long nowMs()
	{
		return System.nanoTime() / 1_000_000L;
	}

	private void completeDrainedIfIdle()
	{
		CompletableFuture<Void> toComplete = null;
		synchronized (this)
		{
			if (drained != null && (closed || queue.isEmpty() || paused || !isConfigured()))
			{
				toComplete = drained;
				drained = null;
			}
		}
		if (toComplete != null)
		{
			toComplete.complete(null);
		}
	}

	private String errorMessage(int code, String text)
	{
		try
		{
			Payloads.ApiError err = gson.fromJson(text, Payloads.ApiError.class);
			if (err != null && err.error != null && err.error.message != null)
			{
				return "HTTP " + code + " " + err.error.code + ": " + err.error.message;
			}
		}
		catch (JsonSyntaxException ignored)
		{
			// not our error shape
		}
		return "HTTP " + code;
	}

	private static String bodyText(ResponseBody body) throws IOException
	{
		return body == null ? "" : body.string();
	}
}
