package com.xptempo.tracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a collection log page shows into the event the server stores. Pure logic: the plugin reads the
 * widgets (header lines with tags removed, and each item slot's id, quantity and whether it is obtained) and
 * this class parses the header, builds the payload and remembers what was last sent for each page, so re-opening
 * an unchanged page sends nothing.
 */
public final class CollectionLogReader
{
	/** "Demonic gorilla kills: 1,284", "Chambers of Xeric completions: 12", "Total opened: 5". */
	private static final Pattern COUNT_LINE = Pattern.compile("^(.+?):\\s*([\\d,]+)$");
	/** The title bar's "Collection Log - 734/1,590" (any "a/b" on the frame). */
	private static final Pattern LOGGED = Pattern.compile("(\\d[\\d,]*)\\s*/\\s*(\\d[\\d,]*)");
	/** "New item added to your collection log: Zenyte shard". */
	private static final Pattern NEW_ITEM = Pattern.compile("^New item added to your collection log: (.+)$", Pattern.CASE_INSENSITIVE);

	private static final int MAX_KC_LINES = 10;
	private static final int MAX_ITEMS = 300;

	private final Map<String, Integer> lastSent = new HashMap<>();

	/** The page title and its count lines ("Obtained: 4/6" is left out: the items say it already). */
	public static final class Header
	{
		public final String title;
		public final List<Object[]> counts;

		Header(String title, List<Object[]> counts)
		{
			this.title = title;
			this.counts = counts;
		}
	}

	public static Header parseHeader(List<String> lines)
	{
		String title = null;
		List<Object[]> counts = new ArrayList<>();
		for (String raw : lines)
		{
			String line = raw == null ? "" : raw.trim();
			if (line.isEmpty())
			{
				continue;
			}
			if (title == null)
			{
				title = line.length() > 80 ? line.substring(0, 80) : line;
				continue;
			}
			if (line.toLowerCase().startsWith("obtained"))
			{
				continue;
			}
			Matcher m = COUNT_LINE.matcher(line);
			if (m.matches() && counts.size() < MAX_KC_LINES)
			{
				String label = m.group(1).trim();
				counts.add(new Object[]{label.length() > 80 ? label.substring(0, 80) : label, Integer.parseInt(m.group(2).replace(",", ""))});
			}
		}
		return title == null ? null : new Header(title, counts);
	}

	/** The first "a/b" on the frame, as "a/b" without separators; null when the frame shows none. */
	public static String parseLogged(List<String> frameLines)
	{
		for (String line : frameLines)
		{
			if (line == null || line.toLowerCase().startsWith("obtained"))
			{
				continue;
			}
			Matcher m = LOGGED.matcher(line);
			if (m.find())
			{
				return m.group(1).replace(",", "") + "/" + m.group(2).replace(",", "");
			}
		}
		return null;
	}

	/** The item name from the game's "New item added to your collection log" message, or null. */
	public static String parseNewItem(String message)
	{
		if (message == null)
		{
			return null;
		}
		Matcher m = NEW_ITEM.matcher(message.trim());
		if (!m.matches())
		{
			return null;
		}
		String name = m.group(1).trim();
		return name.isEmpty() ? null : (name.length() > 80 ? name.substring(0, 80) : name);
	}

	/**
	 * The event fields for a page, or null when nothing changed since this page was last sent (or it has no items).
	 * Each item row is [item id, quantity, 1 when obtained else 0].
	 */
	public Map<String, Object> page(Header header, String category, List<int[]> items, String logged)
	{
		if (header == null || items.isEmpty())
		{
			return null;
		}
		List<int[]> rows = items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items;
		int signature = header.title.hashCode();
		signature = 31 * signature + (category == null ? 0 : category.hashCode());
		for (Object[] c : header.counts)
		{
			signature = 31 * signature + Arrays.hashCode(c);
		}
		for (int[] r : rows)
		{
			signature = 31 * signature + Arrays.hashCode(r);
		}
		signature = 31 * signature + (logged == null ? 0 : logged.hashCode());
		Integer previous = lastSent.get(header.title);
		if (previous != null && previous == signature)
		{
			return null;
		}
		lastSent.put(header.title, signature);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("page", header.title);
		if (category != null)
		{
			m.put("category", category);
		}
		m.put("kc", header.counts);
		m.put("items", rows);
		if (logged != null)
		{
			m.put("logged", logged);
		}
		return m;
	}

	/** A new session re-sends pages as they are opened (the server may have missed the last upload). */
	public void reset()
	{
		lastSent.clear();
	}
}
