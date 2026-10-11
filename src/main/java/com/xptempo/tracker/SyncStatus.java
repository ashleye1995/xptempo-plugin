package com.xptempo.tracker;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What has reached the server, for the side panel's "Sync" section: when the last upload was accepted, the last
 * problem (if uploads are failing), and when each kind of data was last sent. Fed from the upload acknowledgements;
 * kept in memory only.
 */
public final class SyncStatus
{
	public enum Kind
	{
		SKILLS("XP", "on XP gain"),
		PROGRESS("Progress", "at login"),
		INVENTORY("Inventory", "on change"),
		BANK("Bank", "open bank"),
		GE("GE offers", "on offers"),
		DROPS("Drops", "next drop"),
		COLLECTION_LOG("Collection log", "open log");

		public final String label;
		/** When it will be sent, for a kind not sent yet. */
		public final String hint;

		Kind(String label, String hint)
		{
			this.label = label;
			this.hint = hint;
		}
	}

	/** An immutable copy for the panel. */
	public static final class Snapshot
	{
		public final long lastUploadAt;
		public final String problem;
		public final Map<Kind, Long> lastSent;
		public final int clogPages;
		/** Read in game but not uploaded yet, per kind. */
		public final Map<Kind, Integer> waiting;

		Snapshot(long lastUploadAt, String problem, Map<Kind, Long> lastSent, int clogPages, Map<Kind, Integer> waiting)
		{
			this.lastUploadAt = lastUploadAt;
			this.problem = problem;
			this.lastSent = lastSent;
			this.clogPages = clogPages;
			this.waiting = waiting;
		}
	}

	private long lastUploadAt;
	private String problem;
	private final EnumMap<Kind, Long> lastSent = new EnumMap<>(Kind.class);
	private int clogPages;
	private EnumMap<Kind, Integer> waiting = new EnumMap<>(Kind.class);

	/** What is queued in the plugin and not uploaded yet (the plugin refreshes this every game tick). */
	public synchronized void waiting(List<Map<String, Object>> queued)
	{
		EnumMap<Kind, Integer> next = new EnumMap<>(Kind.class);
		for (Map<String, Object> e : queued)
		{
			Kind k = kindOf(e);
			if (k != null)
			{
				next.merge(k, 1, Integer::sum);
			}
		}
		waiting = next;
	}

	/** Which kind of data an event is; null for session bookkeeping (heartbeats, session start/end, game events). */
	public static Kind kindOf(Map<String, Object> event)
	{
		Object type = event.get("type");
		if (type == null)
		{
			return null;
		}
		switch (type.toString())
		{
			case "skills":
			case "xp":
				return Kind.SKILLS;
			case "milestones":
				return Kind.PROGRESS;
			case "container":
				return "BANK".equals(String.valueOf(event.get("container"))) ? Kind.BANK : Kind.INVENTORY;
			case "ge_offer":
				return Kind.GE;
			case "loot":
			case "kill_count":
				return Kind.DROPS;
			case "clog_page":
			case "clog_new":
			case "counts":
				return Kind.COLLECTION_LOG;
			default:
				return null;
		}
	}

	/** An upload was accepted: everything in it is now on the server. */
	public synchronized void accepted(List<Map<String, Object>> events, long now)
	{
		lastUploadAt = now;
		problem = null;
		for (Map<String, Object> e : events == null ? Collections.<Map<String, Object>>emptyList() : events)
		{
			Kind k = kindOf(e);
			if (k != null)
			{
				lastSent.put(k, now);
			}
			if ("clog_page".equals(e.get("type")))
			{
				clogPages++;
			}
		}
	}

	public synchronized void failed(String message)
	{
		problem = message;
	}

	public synchronized Snapshot snapshot()
	{
		return new Snapshot(lastUploadAt, problem, new EnumMap<>(lastSent), clogPages, new EnumMap<>(waiting));
	}

	/** "12s ago", "4m ago", "2h ago". */
	public static String ago(long then, long now)
	{
		long s = Math.max(0, (now - then) / 1000);
		if (s < 60)
		{
			return s + "s ago";
		}
		if (s < 3600)
		{
			return (s / 60) + "m ago";
		}
		return (s / 3600) + "h ago";
	}
}
