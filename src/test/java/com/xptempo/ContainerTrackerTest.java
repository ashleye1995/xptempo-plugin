package com.xptempo;

import com.xptempo.tracker.ContainerTracker;
import com.xptempo.tracker.ContainerTracker.Kind;
import com.xptempo.tracker.ContainerTracker.Snapshot;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ContainerTrackerTest
{
	@Test
	public void snapshotDropsEmptySlotsAndPlaceholdersAndMergesStacks()
	{
		Snapshot s = ContainerTracker.snapshot(Kind.INVENTORY,
			new int[]{-1, 4151, 536, 4151, 995, 1234},
			new int[]{0, 1, 27, 1, 1_000_000, 0});
		assertEquals(3, s.items.size());
		assertArrayEquals(new int[]{536, 27}, s.items.get(0));
		assertArrayEquals(new int[]{995, 1_000_000}, s.items.get(1));
		assertArrayEquals(new int[]{4151, 2}, s.items.get(2));
	}

	@Test
	public void unchangedContentsAreNotPendingAndIntervalsAreHonoured()
	{
		ContainerTracker t = new ContainerTracker();
		Snapshot a = ContainerTracker.snapshot(Kind.INVENTORY, new int[]{536}, new int[]{27});
		t.offer(a);
		assertTrue(t.hasPending(Kind.INVENTORY));
		assertNotNull(t.take(Kind.INVENTORY, 1_000, 60_000, false));
		assertFalse(t.hasPending(Kind.INVENTORY));

		t.offer(ContainerTracker.snapshot(Kind.INVENTORY, new int[]{536}, new int[]{27})); // same hash
		assertFalse(t.hasPending(Kind.INVENTORY));

		Snapshot b = ContainerTracker.snapshot(Kind.INVENTORY, new int[]{536}, new int[]{26});
		t.offer(b);
		assertNull(t.take(Kind.INVENTORY, 30_000, 60_000, false));   // too soon
		assertNotNull(t.take(Kind.INVENTORY, 30_000, 60_000, true)); // forced (bank close)
		assertNull(t.take(Kind.INVENTORY, 61_001, 60_000, false));   // nothing pending any more
	}

	@Test
	public void itemIdsThatShareTheirLowByteHashDifferently()
	{
		// 861 (magic shortbow) and 1373 (rune battleaxe) are both 0x5D in their low byte.
		ContainerTracker.Snapshot a = ContainerTracker.snapshot(ContainerTracker.Kind.INVENTORY, new int[]{861}, new int[]{1});
		ContainerTracker.Snapshot b = ContainerTracker.snapshot(ContainerTracker.Kind.INVENTORY, new int[]{1373}, new int[]{1});
		org.junit.Assert.assertNotEquals(a.hash, b.hash);
	}

	@Test
	public void forgettingACategoryDropsItsPendingSnapshotAndResendsLater()
	{
		ContainerTracker t = new ContainerTracker();
		ContainerTracker.Snapshot s = ContainerTracker.snapshot(ContainerTracker.Kind.BANK, new int[]{995}, new int[]{100});
		t.offer(s);
		org.junit.Assert.assertNotNull(t.take(ContainerTracker.Kind.BANK, 0, 0, true));
		t.offer(s);
		org.junit.Assert.assertFalse(t.hasPending(ContainerTracker.Kind.BANK)); // unchanged, nothing to send
		t.forget(ContainerTracker.Kind.BANK);
		t.offer(s);
		org.junit.Assert.assertTrue(t.hasPending(ContainerTracker.Kind.BANK)); // after a switch-off, a fresh snapshot goes again
	}
}
