package com.xptempo.tracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "Get synced" walkthrough after pairing: the few things the plugin can only read once the player opens them.
 * Everything else (skills, quests, diaries, combat achievements, inventory, worn items, GE offers) is read at login.
 * Pure state and logic: the plugin reports what it sees, persists {@link State} per RuneScape profile and draws
 * the side panel and the in-game highlights from {@link #steps}.
 */
public final class SyncGuide
{
	public enum Step
	{
		BANK, COLLECTION_LOG
	}

	/** Collection log tabs, in the game's order. */
	public static final List<String> CATEGORIES = Collections.unmodifiableList(java.util.Arrays.asList("BOSSES", "RAIDS", "CLUES", "MINIGAMES", "OTHER"));

	/** What is persisted (as JSON) per RuneScape profile. */
	public static final class State
	{
		public boolean bank;
		/** Every page title seen, lower case. */
		public Set<String> pagesSeen = new LinkedHashSet<>();
		/** Each tab's page titles as the game lists them, read when that tab is shown. */
		public Map<String, List<String>> lists = new LinkedHashMap<>();
	}

	/** One row of the checklist. */
	public static final class Row
	{
		public final Step step;
		public final String title;
		public final String detail;
		public final boolean done;

		Row(Step step, String title, String detail, boolean done)
		{
			this.step = step;
			this.title = title;
			this.detail = detail;
			this.done = done;
		}
	}

	private State state;

	public SyncGuide(State state)
	{
		this.state = state == null ? new State() : state;
		if (this.state.pagesSeen == null)
		{
			this.state.pagesSeen = new LinkedHashSet<>();
		}
		if (this.state.lists == null)
		{
			this.state.lists = new LinkedHashMap<>();
		}
	}

	public State state()
	{
		return state;
	}

	/** True when something changed (so the plugin saves and redraws). */
	public boolean bankOpened()
	{
		if (state.bank)
		{
			return false;
		}
		state.bank = true;
		return true;
	}

	public boolean pageSeen(String title)
	{
		return title != null && !title.isEmpty() && state.pagesSeen.add(title.toLowerCase());
	}

	public boolean listSeen(String category, List<String> titles)
	{
		if (!CATEGORIES.contains(category) || titles == null || titles.isEmpty())
		{
			return false;
		}
		List<String> clean = new ArrayList<>();
		for (String t : titles)
		{
			if (t != null && !t.trim().isEmpty())
			{
				clean.add(t.trim());
			}
		}
		if (clean.isEmpty() || clean.equals(state.lists.get(category)))
		{
			return false;
		}
		state.lists.put(category, clean);
		return true;
	}

	/** Pages listed in a tab that have not been opened yet, in the game's order. */
	public List<String> missingPages(String category)
	{
		List<String> out = new ArrayList<>();
		for (String t : state.lists.getOrDefault(category, Collections.emptyList()))
		{
			if (!state.pagesSeen.contains(t.toLowerCase()))
			{
				out.add(t);
			}
		}
		return out;
	}

	/** The first tab that still needs visiting: never shown yet, or with pages left to open. Null when all done. */
	public String nextCategory()
	{
		for (String c : CATEGORIES)
		{
			if (!state.lists.containsKey(c) || !missingPages(c).isEmpty())
			{
				return c;
			}
		}
		return null;
	}

	/** The next page to open: the first unopened page in the first unfinished tab, when that tab has been shown. */
	public String nextPage()
	{
		String c = nextCategory();
		if (c == null)
		{
			return null;
		}
		List<String> missing = missingPages(c);
		return missing.isEmpty() ? null : missing.get(0);
	}

	public int pagesListed()
	{
		int n = 0;
		for (List<String> l : state.lists.values())
		{
			n += l.size();
		}
		return n;
	}

	public int pagesOpened()
	{
		int n = 0;
		for (List<String> l : state.lists.values())
		{
			for (String t : l)
			{
				if (state.pagesSeen.contains(t.toLowerCase()))
				{
					n++;
				}
			}
		}
		return n;
	}

	public boolean collectionLogDone()
	{
		return nextCategory() == null;
	}

	/** The checklist for the switches that are on (bank needs "Bank"; the collection log "Drops and kill counts"). */
	public List<Row> steps(boolean bankOn, boolean lootOn)
	{
		List<Row> rows = new ArrayList<>();
		if (bankOn)
		{
			rows.add(new Row(Step.BANK, "Open your bank", state.bank ? "Bank synced" : "Any bank booth, chest or banker: they are outlined in green", state.bank));
		}
		if (lootOn)
		{
			boolean done = collectionLogDone();
			String detail;
			if (done)
			{
				detail = "All " + pagesListed() + " pages synced";
			}
			else if (state.lists.size() < CATEGORIES.size())
			{
				detail = pagesOpened() + " pages so far: open every tab and each page in it";
			}
			else
			{
				String next = nextPage();
				detail = pagesOpened() + " / " + pagesListed() + " pages opened" + (next == null ? "" : ". Next: " + next);
			}
			rows.add(new Row(Step.COLLECTION_LOG, "Open every collection log page", detail, done));
		}
		return rows;
	}

	/** The step to highlight now: the first one not done, or null when everything is synced. */
	public Step current(boolean bankOn, boolean lootOn)
	{
		for (Row r : steps(bankOn, lootOn))
		{
			if (!r.done)
			{
				return r.step;
			}
		}
		return null;
	}
}
