package com.xptempo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.xptempo.tracker.CollectionLogReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CollectionLogReaderTest
{
	@Test
	public void parsesTitleAndCountLinesButNotObtained()
	{
		CollectionLogReader.Header h = CollectionLogReader.parseHeader(Arrays.asList(
			"Demonic gorillas", "Obtained: 4/6", "Demonic gorilla kills: 1,284", "", "Some note without a number"));
		assertNotNull(h);
		assertEquals("Demonic gorillas", h.title);
		assertEquals(1, h.counts.size());
		assertEquals("Demonic gorilla kills", h.counts.get(0)[0]);
		assertEquals(1284, h.counts.get(0)[1]);
		assertNull(CollectionLogReader.parseHeader(Collections.singletonList("  ")));
	}

	@Test
	public void readsTheLoggedTotalAndTheNewItemMessage()
	{
		assertEquals("734/1590", CollectionLogReader.parseLogged(Arrays.asList("Obtained: 4/6", "Collection Log - 734/1,590")));
		assertNull(CollectionLogReader.parseLogged(Collections.singletonList("Collection Log")));
		assertEquals("Zenyte shard", CollectionLogReader.parseNewItem("New item added to your collection log: Zenyte shard"));
		assertNull(CollectionLogReader.parseNewItem("Your Vorkath kill count is: 12"));
	}

	@Test
	public void sendsAPageOnceUntilItChanges()
	{
		CollectionLogReader reader = new CollectionLogReader();
		CollectionLogReader.Header h = CollectionLogReader.parseHeader(Arrays.asList("Vorkath", "Vorkath kills: 12"));
		List<int[]> items = Arrays.asList(new int[]{22006, 1, 1}, new int[]{11286, 0, 0});
		Map<String, Object> first = reader.page(h, "BOSSES", items, null);
		assertNotNull(first);
		assertEquals("Vorkath", first.get("page"));
		assertEquals("BOSSES", first.get("category"));
		assertNull("unchanged page is not sent again", reader.page(h, "BOSSES", items, null));
		List<int[]> more = Arrays.asList(new int[]{22006, 1, 1}, new int[]{11286, 1, 1});
		assertNotNull("a newly obtained item is sent", reader.page(h, "BOSSES", more, null));
		reader.reset();
		assertNotNull("a new session sends it again", reader.page(h, "BOSSES", more, null));
		assertNull("a page without items is never sent", reader.page(h, "BOSSES", Collections.emptyList(), null));
	}
}
