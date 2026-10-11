package com.xptempo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.xptempo.tracker.SyncStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SyncStatusTest
{
	private static Map<String, Object> ev(String type, String container)
	{
		Map<String, Object> m = new HashMap<>();
		m.put("type", type);
		if (container != null)
		{
			m.put("container", container);
		}
		return m;
	}

	@Test
	public void eventsMapToWhatThePanelShows()
	{
		assertEquals(SyncStatus.Kind.BANK, SyncStatus.kindOf(ev("container", "BANK")));
		assertEquals(SyncStatus.Kind.INVENTORY, SyncStatus.kindOf(ev("container", "EQUIPMENT")));
		assertEquals(SyncStatus.Kind.SKILLS, SyncStatus.kindOf(ev("xp", null)));
		assertEquals(SyncStatus.Kind.COLLECTION_LOG, SyncStatus.kindOf(ev("clog_page", null)));
		assertNull(SyncStatus.kindOf(ev("heartbeat", null)));
	}

	@Test
	public void acceptedUploadsRecordTimesAndClearProblems()
	{
		SyncStatus s = new SyncStatus();
		s.failed("server unreachable", 1_000);
		assertEquals("server unreachable", s.snapshot().problem);
		assertEquals(1_000, s.snapshot().lastFailedAt);
		s.accepted(Arrays.asList(ev("xp", null), ev("clog_page", null), ev("clog_page", null), ev("heartbeat", null)), 5_000);
		SyncStatus.Snapshot snap = s.snapshot();
		assertNull(snap.problem);
		assertEquals("a success clears the failure", 0, snap.lastFailedAt);
		assertEquals(5_000, snap.lastUploadAt);
		assertEquals(Long.valueOf(5_000), snap.lastSent.get(SyncStatus.Kind.SKILLS));
		assertFalse(snap.lastSent.containsKey(SyncStatus.Kind.BANK));
		assertEquals(2, snap.clogPages);
		s.accepted(Collections.emptyList(), 9_000);
		assertTrue(s.snapshot().lastUploadAt == 9_000);
	}

	@Test
	public void queuedEventsShowAsWaitingUntilSent()
	{
		SyncStatus s = new SyncStatus();
		s.waiting(Arrays.asList(ev("clog_page", null), ev("clog_page", null), ev("heartbeat", null)));
		assertEquals(Integer.valueOf(2), s.snapshot().waiting.get(SyncStatus.Kind.COLLECTION_LOG));
		s.waiting(Collections.emptyList());
		assertTrue(s.snapshot().waiting.isEmpty());
	}

	@Test
	public void agoIsShortAndReadable()
	{
		assertEquals("12s ago", SyncStatus.ago(0, 12_000));
		assertEquals("4m ago", SyncStatus.ago(0, 4 * 60_000 + 5_000));
		assertEquals("2h ago", SyncStatus.ago(0, 2 * 3_600_000));
	}
}
