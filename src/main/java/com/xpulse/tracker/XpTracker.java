package com.xpulse.tracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * Accumulates XP gains per skill between flushes. Pure logic, no client access, so it is unit-testable.
 * All methods are called on the client thread (or under the plugin's lock during shutdown).
 */
public final class XpTracker
{
	/** One skill's gain over a flush window plus its absolute XP/level at the end of the window. */
	public static final class Delta
	{
		public final Skill skill;
		public final int xpDelta;
		public final int xp;
		public final int level;

		Delta(Skill skill, int xpDelta, int xp, int level)
		{
			this.skill = skill;
			this.xpDelta = xpDelta;
			this.xp = xp;
			this.level = level;
		}
	}

	public static final class LevelUp
	{
		public final Skill skill;
		public final int level;
		public final int xp;

		LevelUp(Skill skill, int level, int xp)
		{
			this.skill = skill;
			this.level = level;
			this.xp = xp;
		}
	}

	private static final long RATE_WINDOW_MS = 10 * 60_000L;

	private final EnumMap<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final EnumMap<Skill, Integer> lastLevel = new EnumMap<>(Skill.class);
	private final EnumMap<Skill, Integer> pending = new EnumMap<>(Skill.class);
	/** {timeMs, delta} pairs for the rolling XP/hr figure. */
	private final Deque<long[]> recent = new ArrayDeque<>();
	private Skill activeSkill;
	private long lastGainMs;

	public void reset()
	{
		lastXp.clear();
		lastLevel.clear();
		pending.clear();
		recent.clear();
		activeSkill = null;
		lastGainMs = 0;
	}

	/** Sets the baseline for a skill without recording a gain (login seed, world hop). */
	public void seed(Skill skill, int xp, int level)
	{
		lastXp.put(skill, xp);
		lastLevel.put(skill, level);
	}

	public boolean isSeeded()
	{
		return !lastXp.isEmpty();
	}

	/**
	 * Records a StatChanged. Returns a LevelUp when the real level rose, otherwise null.
	 * Boost/drain events (same xp) record nothing.
	 */
	public LevelUp record(Skill skill, int xp, int level, long nowMs)
	{
		Integer prevXp = lastXp.put(skill, xp);
		Integer prevLevel = lastLevel.put(skill, level);
		if (prevXp == null)
		{
			return null; // first sight: baseline only
		}
		int delta = xp - prevXp;
		if (delta > 0)
		{
			pending.merge(skill, delta, Integer::sum);
			recent.addLast(new long[]{nowMs, delta});
			activeSkill = skill;
			lastGainMs = nowMs;
		}
		// A real level only rises together with XP; anything else is a boost, a drain or a stale read.
		if (delta > 0 && prevLevel != null && level > prevLevel)
		{
			return new LevelUp(skill, level, xp);
		}
		return null;
	}

	public boolean hasPending()
	{
		return !pending.isEmpty();
	}

	public Skill getActiveSkill()
	{
		return activeSkill;
	}

	public long getLastGainMs()
	{
		return lastGainMs;
	}

	/** Moves the accumulated gains out as deltas and clears them. */
	public List<Delta> drain()
	{
		List<Delta> out = new ArrayList<>(pending.size());
		for (Map.Entry<Skill, Integer> e : pending.entrySet())
		{
			Skill s = e.getKey();
			out.add(new Delta(s, e.getValue(), lastXp.getOrDefault(s, 0), lastLevel.getOrDefault(s, 1)));
		}
		pending.clear();
		return out;
	}

	/**
	 * XP gained over the last ten minutes, scaled to an hour. Early in a session the span is the time since
	 * the first gain (never less than a minute), so the figure is not diluted by time before training began.
	 */
	public int xpPerHour(long nowMs)
	{
		while (!recent.isEmpty() && recent.peekFirst()[0] < nowMs - RATE_WINDOW_MS)
		{
			recent.pollFirst();
		}
		if (recent.isEmpty())
		{
			return 0;
		}
		long sum = 0;
		for (long[] r : recent)
		{
			sum += r[1];
		}
		long spanMs = Math.max(60_000L, Math.min(RATE_WINDOW_MS, nowMs - recent.peekFirst()[0]));
		return (int) Math.min(Integer.MAX_VALUE, sum * 3_600_000L / spanMs);
	}
}
