package com.xptempo.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded FIFO of unsent batches. When the API is unreachable for a long time the oldest two batches are
 * merged instead of dropped: XP deltas are summed per skill, heartbeats from the older batch are discarded,
 * everything else is kept (data-model.md §13 / runelite-plugin.md §5.3).
 */
final class BatchQueue
{
	private static final int MAX_BATCHES = 50;
	private static final int MAX_EVENTS = 500;

	private final ArrayDeque<Payloads.Batch> queue = new ArrayDeque<>();

	synchronized void add(Payloads.Batch batch)
	{
		queue.addLast(batch);
		while (queue.size() > MAX_BATCHES)
		{
			mergeOldestTwo();
		}
	}

	synchronized Payloads.Batch peek()
	{
		return queue.peekFirst();
	}

	synchronized void removeHead(Payloads.Batch batch)
	{
		if (queue.peekFirst() == batch)
		{
			queue.pollFirst();
		}
	}

	/** Replaces the head with two halves (HTTP 413). Returns false when the head cannot be split. */
	synchronized boolean splitHead(Payloads.Batch batch)
	{
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

	synchronized boolean isEmpty()
	{
		return queue.isEmpty();
	}

	synchronized int size()
	{
		return queue.size();
	}

	private void mergeOldestTwo()
	{
		Payloads.Batch older = queue.pollFirst();
		Payloads.Batch newer = queue.pollFirst();
		if (newer == null)
		{
			queue.addFirst(older);
			return;
		}
		List<Map<String, Object>> events = new ArrayList<>();
		for (Map<String, Object> ev : older.events)
		{
			if (!"heartbeat".equals(ev.get("type")))
			{
				events.add(ev);
			}
		}
		events.addAll(newer.events);
		events = compactXp(events);
		if (events.size() > MAX_EVENTS)
		{
			events = new ArrayList<>(events.subList(events.size() - MAX_EVENTS, events.size()));
		}
		queue.addFirst(copyWithEvents(newer, events));
	}

	/** Folds all xp events into one: deltas summed per skill, latest xp/level kept, window spans both. */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> compactXp(List<Map<String, Object>> events)
	{
		Map<String, Map<String, Object>> deltasBySkill = new LinkedHashMap<>();
		String windowStart = null;
		String windowEnd = null;
		int seq = -1;
		String at = null;
		List<Map<String, Object>> rest = new ArrayList<>();
		for (Map<String, Object> ev : events)
		{
			if (!"xp".equals(ev.get("type")))
			{
				rest.add(ev);
				continue;
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
		rest.add(xp);
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
