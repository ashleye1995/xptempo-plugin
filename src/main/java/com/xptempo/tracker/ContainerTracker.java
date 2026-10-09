package com.xptempo.tracker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Keeps the latest snapshot of each container and decides when it is worth sending: only when the
 * contents changed since the last send, and no more often than the per-container interval.
 * Pure logic; the plugin feeds it canonicalised item ids.
 */
public final class ContainerTracker
{
	public enum Kind
	{
		INVENTORY, BANK, EQUIPMENT
	}

	public static final class Snapshot
	{
		public final Kind kind;
		/** [itemId, quantity] pairs, ids ascending, duplicates summed. */
		public final List<int[]> items;
		public final String hash;

		Snapshot(Kind kind, List<int[]> items, String hash)
		{
			this.kind = kind;
			this.items = items;
			this.hash = hash;
		}
	}

	private final EnumMap<Kind, Snapshot> pending = new EnumMap<>(Kind.class);
	private final EnumMap<Kind, String> lastSentHash = new EnumMap<>(Kind.class);
	private final EnumMap<Kind, Long> lastSentMs = new EnumMap<>(Kind.class);

	/** Drops empty slots (id ≤ 0) and placeholders (quantity ≤ 0), sums duplicate ids, sorts by id. */
	public static Snapshot snapshot(Kind kind, int[] ids, int[] quantities)
	{
		TreeMap<Integer, Long> merged = new TreeMap<>();
		for (int i = 0; i < ids.length; i++)
		{
			if (ids[i] <= 0 || quantities[i] <= 0)
			{
				continue;
			}
			merged.merge(ids[i], (long) quantities[i], Long::sum);
		}
		List<int[]> items = new ArrayList<>(merged.size());
		CRC32 crc = new CRC32();
		for (var e : merged.entrySet())
		{
			int qty = (int) Math.min(Integer.MAX_VALUE, e.getValue());
			items.add(new int[]{e.getKey(), qty});
			crc.update(e.getKey());
			crc.update(qty);
			crc.update(qty >>> 8);
			crc.update(qty >>> 16);
			crc.update(qty >>> 24);
		}
		return new Snapshot(kind, items, Long.toHexString(crc.getValue()) + ":" + items.size());
	}

	/** Records the latest contents; nothing becomes pending if they match what was last sent. */
	public void offer(Snapshot snapshot)
	{
		if (snapshot.hash.equals(lastSentHash.get(snapshot.kind)))
		{
			pending.remove(snapshot.kind);
		}
		else
		{
			pending.put(snapshot.kind, snapshot);
		}
	}

	public boolean hasPending(Kind kind)
	{
		return pending.containsKey(kind);
	}

	/**
	 * Returns the pending snapshot and marks it sent when one exists and the interval since the last
	 * send has elapsed (or force is set); otherwise null.
	 */
	public Snapshot take(Kind kind, long nowMs, long minIntervalMs, boolean force)
	{
		Snapshot s = pending.get(kind);
		if (s == null)
		{
			return null;
		}
		Long last = lastSentMs.get(kind);
		if (!force && last != null && nowMs - last < minIntervalMs)
		{
			return null;
		}
		pending.remove(kind);
		lastSentMs.put(kind, nowMs);
		lastSentHash.put(kind, s.hash);
		return s;
	}

	/** Forget everything (new character / new session): the server will get full payloads again. */
	public void reset()
	{
		pending.clear();
		lastSentHash.clear();
		lastSentMs.clear();
	}
}
