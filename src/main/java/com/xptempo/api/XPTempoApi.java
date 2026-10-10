package com.xptempo.api;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * can deduplicate) with exponential back-off from 5 s to 5 min.
 */
@Slf4j
public final class XPTempoApi
{
	public interface Listener
	{
		void onAck(Payloads.Ack ack);

		void onUnauthorized();

		void onFailure(String message, boolean willRetry);
	}

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final long BASE_BACKOFF_MS = 5_000L;
	private static final long MAX_BACKOFF_MS = 300_000L;

	private final OkHttpClient http;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Listener listener;
	private final BatchQueue queue = new BatchQueue();
	private final String userAgent;

	private volatile HttpUrl baseUrl;
	private volatile String apiKey = "";
	private volatile boolean paused;
	private boolean sending;
	private int failures;
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

	/** Called at start-up and whenever the config changes. A new key un-pauses sending after a 401. */
	/** Where every player's plugin sends data. Fixed: the service lives at api.xptempo.com. */
	public static final String PRODUCTION_BASE_URL = "https://api.xptempo.com";

	/**
	 * The production address, unless a developer launched the client with -Dxptempo.api=http://127.0.0.1:3000
	 * (the Gradle run task forwards the XPTEMPO_API environment variable as that property).
	 */
	public static String baseUrl()
	{
		String override = System.getProperty("xptempo.api");
		return override == null || override.trim().isEmpty() ? PRODUCTION_BASE_URL : override.trim();
	}

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
		queue.add(batch);
		executor.execute(this::sendNext);
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
			if (sending || paused || queue.isEmpty() || !isConfigured())
			{
				completeDrainedIfIdle();
				return;
			}
			batch = queue.peek();
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
				synchronized (XPTempoApi.this)
				{
					sending = false;
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
					synchronized (XPTempoApi.this)
					{
						sending = false;
					}
					scheduleRetry("could not read response: " + e.getMessage(), -1);
					return;
				}
				synchronized (XPTempoApi.this)
				{
					sending = false;
				}
				handleResponse(batch, code, text, retryAfterMs);
			}
		});
	}

	private void handleResponse(Payloads.Batch batch, int code, String text, long retryAfterMs)
	{
		if (code >= 200 && code < 300)
		{
			queue.removeHead(batch);
			synchronized (this)
			{
				failures = 0;
			}
			try
			{
				Payloads.Ack ack = gson.fromJson(text, Payloads.Ack.class);
				if (ack != null)
				{
					listener.onAck(ack);
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
			paused = true;
			listener.onUnauthorized();
			completeDrainedIfIdle();
			return;
		}
		if (code == 413)
		{
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
			scheduleRetry(errorMessage(code, text), retryAfterMs);
			return;
		}
		// Any other 4xx: the batch itself is unacceptable; drop it rather than block the queue forever.
		queue.removeHead(batch);
		listener.onFailure(errorMessage(code, text) + "; batch dropped", false);
		executor.execute(this::sendNext);
	}

	private void scheduleRetry(String message, long retryAfterMs)
	{
		long delay;
		synchronized (this)
		{
			failures++;
			int shift = Math.min(failures - 1, 6);
			delay = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << shift);
			delay += ThreadLocalRandom.current().nextLong(delay / 4 + 1);
			if (retryAfterMs > 0)
			{
				delay = Math.min(MAX_BACKOFF_MS, retryAfterMs);
			}
			// Never hold the client's shutdown hook for a retry window.
			if (drained != null)
			{
				drained.complete(null);
				drained = null;
			}
		}
		listener.onFailure(message + " (retrying in " + (delay / 1000) + " s)", true);
		executor.schedule(this::sendNext, delay, TimeUnit.MILLISECONDS);
	}

	private void completeDrainedIfIdle()
	{
		CompletableFuture<Void> toComplete = null;
		synchronized (this)
		{
			if (drained != null && (queue.isEmpty() || paused || !isConfigured()))
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
