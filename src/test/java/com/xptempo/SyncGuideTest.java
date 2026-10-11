package com.xptempo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.xptempo.tracker.SyncGuide;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SyncGuideTest
{
	@Test
	public void onlyTheSwitchedOnStepsAreListed()
	{
		SyncGuide g = new SyncGuide(null);
		assertEquals(2, g.steps(true, true).size());
		assertEquals(Collections.singletonList(SyncGuide.Step.BANK), stepsOf(g.steps(true, false)));
		assertEquals(Collections.singletonList(SyncGuide.Step.COLLECTION_LOG), stepsOf(g.steps(false, true)));
		assertTrue(g.steps(false, false).isEmpty());
		assertNull(g.current(false, false));
	}

	@Test
	public void stepsTickOffInOrderAndOnlyOnce()
	{
		SyncGuide g = new SyncGuide(null);
		assertEquals(SyncGuide.Step.BANK, g.current(true, true));
		assertTrue(g.bankOpened());
		assertFalse("already done", g.bankOpened());
		assertEquals(SyncGuide.Step.COLLECTION_LOG, g.current(true, true));
	}

	@Test
	public void theCollectionLogNeedsEveryTabAndEveryPage()
	{
		SyncGuide g = new SyncGuide(null);
		assertEquals("BOSSES", g.nextCategory());
		g.listSeen("BOSSES", Arrays.asList("Abyssal Sire", "Vorkath"));
		g.pageSeen("Abyssal Sire");
		assertEquals("Vorkath", g.nextPage());
		g.pageSeen("vorkath");
		assertEquals("a tab never shown still counts as unfinished", "RAIDS", g.nextCategory());
		for (String c : Arrays.asList("RAIDS", "CLUES", "MINIGAMES", "OTHER"))
		{
			g.listSeen(c, Collections.singletonList(c + " page"));
			assertFalse(g.collectionLogDone());
			g.pageSeen(c + " page");
		}
		assertTrue(g.collectionLogDone());
		assertEquals(6, g.pagesListed());
		assertEquals(6, g.pagesOpened());
		assertTrue("the collection log row reads as done", g.steps(false, true).get(0).done);
	}

	@Test
	public void stateSurvivesARoundTrip()
	{
		SyncGuide g = new SyncGuide(null);
		g.bankOpened();
		g.listSeen("BOSSES", Collections.singletonList("Vorkath"));
		g.pageSeen("Vorkath");
		SyncGuide again = new SyncGuide(g.state());
		assertTrue(again.state().bank);
		assertEquals(1, again.pagesOpened());
		assertFalse("an unknown tab is ignored", again.listSeen("PETS", Collections.singletonList("x")));
	}

	private static List<SyncGuide.Step> stepsOf(List<SyncGuide.Row> rows)
	{
		return rows.stream().map(r -> r.step).collect(java.util.stream.Collectors.toList());
	}
}
