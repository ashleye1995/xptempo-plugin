package com.xptempo.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

/**
 * Bounded FIFO of unsent batches. While the API is unreachable the queue caps itself by merging two neighbouring
 * batches of the same character and session (never across characters or sessions, never the batch being sent):
 * XP deltas are summed per skill and superseded snapshots are dropped, so nothing the server needs is lost.
 */
@Slf4j
final class BatchQueue
{
	static final int MAX_BATCHES = 50;
	static final int MAX_EVENTS = 500;
	/** Never dropped when a merged batch has to be trimmed. */
	private static final Set<String> KEEP = Set.of("session_start", "session_end", "skills", "xp", "event", "ge_offer", "loot", "kill_count");
	/** What a session cannot do without; these survive even when a batch has to be cut down to fit. */
	private static final Set<String> SESSION = Set.of("session_start", "session_end", "skills");
	/** Only the newest of each matters, so older ones go first when trimming. */
	private static final Set<String> LATEST_ONLY = Set.of("heartbeat", "milestones", "counts");

	private final ArrayDeque<Payloads.Batch> queue = new ArrayDeque<>();
	/** The batch currently on the wire; it is never merged, split or dropped by add(). */
	private Payloads.Batch inFlight;
	/**
	 * A batch that was sent but got no clear answer (timeout, 5xx): the server may already have applied it, so it
	 * is only ever re-sent unchanged (same batchId, deduplicated server-side), never merged into a new batch.
	 */
	private Payloads.Batch attempted;

	synchronized void add(Payloads.Batch batch)
	{
		queue.addLast(batch);
		while (queue.size() > MAX_BATCHES)
		{
			if (!mergeOldestCompatiblePair())
			{
				dropOldestIdle();
			}
		}
	}

	/** The next batch to send, marked as in flight until {@link #release} or {@link #removeHead}. */
	synchronized Payloads.Batch take()
	{
		Payloads.Batch head = queue.peekFirst();
		if (head != null && head.events.size() > MAX_EVENTS)
		{
			// Safety net: never send more than the server accepts in one batch.
			queue.pollFirst();
			List<Payloads.Batch> chunks = new ArrayList<>();
			for (int i = 0; i < head.events.size(); i += MAX_EVENTS)
			{
				chunks.add(copyWithEvents(head, new ArrayList<>(head.events.subList(i, Math.min(head.events.size(), i + MAX_EVENTS)))));
			}
			for (int i = chunks.size() - 1; i >= 0; i--)
			{
				queue.addFirst(chunks.get(i));
			}
		}
		inFlight = queue.peekFirst();
		attempted = inFlight; // until the server answers, it may or may not have applied it
		return inFlight;
	}

	/** The send failed in a way the server may still have applied it; it stays at the head, to be re-sent unchanged. */
	synchronized void release(Payloads.Batch batch)
	{
		if (inFlight == batch)
		{
			inFlight = null;
		}
	}

	/** The send certainly did not reach the server (no connection, 429, 503): the batch may be changed or merged again. */
	synchronized void notApplied(Payloads.Batch batch)
	{
		if (attempted == batch)
		{
			attempted = null;
		}
		release(batch);
	}

	/** The server answered for this batch (accepted, or rejected for good). */
	synchronized void removeHead(Payloads.Batch batch)
	{
		if (queue.peekFirst() == batch)
		{
			queue.pollFirst();
		}
		if (attempted == batch)
		{
			attempted = null;
		}
		release(batch);
	}

	/** Replaces the head with two halves (HTTP 413). Returns false when the head cannot be split. */
	synchronized boolean splitHead(Payloads.Batch batch)
	{
		release(batch);
		if (attempted == batch)
		{
			attempted = null; // a 413 is refused before anything is applied
		}
		if (queue.peekFirst() != batch || batch.events.size() < 2)
		{
			return false;
		}
		queue.pollFirst();
		int mid = batch.events.size() / 2;
		Payloads.Batch second = copyWithEvents(batch, new ArrayList<>(batch.events.subList(mid, batch.events.size())));
		Payloads.Batch first = copyWithEvents(batch, new ArrayList<>(batch.events.subList(0, mid)));
		queue.addFirst(second);
		queue.addFirst(first);
		return true;
	}

	/**
	 * Uploads were switched off: drops everything not yet acknowledged, including the batch on the wire. If that
	 * request fails it is not retried; the inFlight reference stays so nothing merges with it until it returns.
	 */
	synchronized void clear()
	{
		queue.clear();
		attempted = null;
	}

	/**
	 * A category was switched off: removes its events from every queued batch except the one on the wire (and
	 * one awaiting a retry, which must be re-sent unchanged). Changed batches get a new id; empty ones go.
	 */
	synchronized void removeEvents(Predicate<Map<String, Object>> drop)
	{
		List<Payloads.Batch> list = new ArrayList<>(queue);
		queue.clear();
		for (Payloads.Batch b : list)
		{
			if (b == inFlight || b == attempted || b.events.stream().noneMatch(drop))
			{
				queue.addLast(b);
				continue;
			}
			List<Map<String, Object>> kept = new ArrayList<>();
			for (Map<String, Object> ev : b.events)
			{
				if (!drop.test(ev))
				{
					kept.add(ev);
				}
			}
			if (!kept.isEmpty())
			{
				queue.addLast(copyWithEvents(b, kept));
			}
		}
	}

	synchronized boolean isEmpty()
	{
		return queue.isEmpty();
	}

	synchronized int size()
	{
		return queue.size();
	}

	synchronized List<Payloads.Batch> snapshot()
	{
		return new ArrayList<>(queue);
	}

	private static boolean sameStream(Payloads.Batch a, Payloads.Batch b)
	{
		return a.account != null && b.account != null
			&& Objects.equals(a.account.hash, b.account.hash)
			&& Objects.equals(a.sessionId, b.sessionId);
	}

	/**
	 * Merges the oldest neighbouring pair of the same character and session whose result still fits the server's
	 * per-batch limit, skipping the batch on the wire and one awaiting a retry.
	 */
	private boolean mergeOldestCompatiblePair()
	{
		List<Payloads.Batch> list = new ArrayList<>(queue);
		for (int i = 0; i + 1 < list.size(); i++)
		{
			Payloads.Batch older = list.get(i);
			Payloads.Batch newer = list.get(i + 1);
			if (older == inFlight || newer == inFlight || older == attempted || newer == attempted || !sameStream(older, newer))
			{
				continue;
			}
			List<Map<String, Object>> events = new ArrayList<>(older.events);
			events.addAll(newer.events);
			List<Map<String, Object>> merged = trim(compactXp(events));
			if (merged.size() > MAX_EVENTS)
			{
				continue; // too many events that must be kept: leave this pair alone
			}
			list.set(i, copyWithEvents(newer, merged));
			list.remove(i + 1);
			queue.clear();
			queue.addAll(list);
			return true;
		}
		return false;
	}

	/**
	 * Last resort when no pair can be merged (alternating characters, or batches full of drops): drop the oldest
	 * batch not in flight. Its session_start and skills seed move to the next batch of the same session, so the
	 * server still opens that session.
	 */
	private void dropOldestIdle()
	{
		List<Payloads.Batch> list = new ArrayList<>(queue);
		for (int i = 0; i < list.size(); i++)
		{
			Payloads.Batch victim = list.get(i);
			if (victim == inFlight)
			{
				continue;
			}
			List<Map<String, Object>> carry = new ArrayList<>();
			for (Map<String, Object> ev : victim.events)
			{
				Object type = ev.get("type");
				if ("session_start".equals(type) || "skills".equals(type))
				{
					carry.add(ev);
				}
			}
			list.remove(i);
			if (!carry.isEmpty())
			{
				for (int j = i; j < list.size(); j++)
				{
					Payloads.Batch next = list.get(j);
					if (next != inFlight && next != attempted && sameStream(victim, next))
					{
						carry.addAll(next.events);
						list.set(j, copyWithEvents(next, fit(carry)));
						break;
					}
				}
			}
			if (victim == attempted)
			{
				attempted = null;
			}
			log.debug("XPTempo: offline queue full, dropped a batch of {} events", victim.events.size());
			queue.clear();
			queue.addAll(list);
			return;
		}
	}

	/** Cuts a batch down to MAX_EVENTS: trim first, then the oldest events other than the session's own. */
	private static List<Map<String, Object>> fit(List<Map<String, Object>> events)
	{
		List<Map<String, Object>> out = events.size() > MAX_EVENTS ? trim(events) : events;
		for (int i = 0; out.size() > MAX_EVENTS && i < out.size(); )
		{
			if (SESSION.contains(String.valueOf(out.get(i).get("type"))))
			{
				i++;
			}
			else
			{
				out.remove(i);
			}
		}
		return out;
	}

	/** Keeps a merged batch under MAX_EVENTS: superseded snapshots first, then the oldest droppable events. */
	static List<Map<String, Object>> trim(List<Map<String, Object>> events)
	{
		// Keep only the newest heartbeat/milestones/counts and the newest snapshot per container.
		Map<String, Integer> newest = new LinkedHashMap<>();
		for (int i = 0; i < events.size(); i++)
		{
			String key = supersedeKey(events.get(i));
			if (key != null)
			{
				newest.put(key, i);
			}
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < events.size(); i++)
		{
			String key = supersedeKey(events.get(i));
			if (key == null || newest.get(key) == i)
			{
				out.add(events.get(i));
			}
		}
		for (int i = 0; out.size() > MAX_EVENTS && i < out.size(); )
		{
			if (KEEP.contains(String.valueOf(out.get(i).get("type"))))
			{
				i++;
			}
			else
			{
				out.remove(i);
			}
		}
		return out;
	}

	private static String supersedeKey(Map<String, Object> ev)
	{
		String type = String.valueOf(ev.get("type"));
		if (LATEST_ONLY.contains(type))
		{
			return type + ("counts".equals(type) ? ":" + ev.get("interface") : "");
		}
		if ("container".equals(type))
		{
			return "container:" + ev.get("container");
		}
		return null;
	}

	/** Folds all xp events into one: deltas summed per skill, latest xp/level kept, window spans both. */
	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> compactXp(List<Map<String, Object>> events)
	{
		Map<String, Map<String, Object>> deltasBySkill = new LinkedHashMap<>();
		String windowStart = null;
		String windowEnd = null;
		int seq = -1;
		String at = null;
		int insertAt = -1;
		List<Map<String, Object>> rest = new ArrayList<>();
		for (Map<String, Object> ev : events)
		{
			if (!"xp".equals(ev.get("type")))
			{
				rest.add(ev);
				continue;
			}
			if (insertAt < 0)
			{
				insertAt = rest.size();
			}
			String ws = (String) ev.get("windowStart");
			String we = (String) ev.get("windowEnd");
			if (windowStart == null || ws.compareTo(windowStart) < 0)
			{
				windowStart = ws;
			}
			if (windowEnd == null || we.compareTo(windowEnd) > 0)
			{
				windowEnd = we;
				at = (String) ev.get("at");
				seq = ((Number) ev.get("seq")).intValue();
			}
			for (Map<String, Object> d : (List<Map<String, Object>>) ev.get("deltas"))
			{
				String skill = (String) d.get("skill");
				Map<String, Object> merged = deltasBySkill.get(skill);
				if (merged == null)
				{
					deltasBySkill.put(skill, new LinkedHashMap<>(d));
				}
				else
				{
					int sum = ((Number) merged.get("xpDelta")).intValue() + ((Number) d.get("xpDelta")).intValue();
					merged.put("xpDelta", sum);
					merged.put("xp", d.get("xp"));
					merged.put("level", d.get("level"));
				}
			}
		}
		if (deltasBySkill.isEmpty())
		{
			return rest;
		}
		Map<String, Object> xp = new LinkedHashMap<>();
		xp.put("seq", seq);
		xp.put("at", at);
		xp.put("type", "xp");
		xp.put("windowStart", windowStart);
		xp.put("windowEnd", windowEnd);
		xp.put("deltas", new ArrayList<>(deltasBySkill.values()));
		rest.add(insertAt, xp);
		return rest;
	}

	/** A merged or split batch is a new payload, so it gets a new id (never reuse an id with a different body). */
	private static Payloads.Batch copyWithEvents(Payloads.Batch src, List<Map<String, Object>> events)
	{
		Payloads.Batch b = new Payloads.Batch();
		b.batchId = UUID.randomUUID().toString();
		b.sentAt = src.sentAt;
		b.plugin = src.plugin;
		b.account = src.account;
		b.sessionId = src.sessionId;
		b.events = events;
		return b;
	}
}
