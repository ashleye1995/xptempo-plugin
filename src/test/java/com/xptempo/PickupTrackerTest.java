package com.xptempo;

import com.xptempo.tracker.PickupTracker;
import com.xptempo.tracker.PickupTracker.Pickup;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PickupTrackerTest
{
	private static final int WHIP = 4151;
	private static final int COINS = 995;
	private static final int BONES = 526;

	private PickupTracker tracker;
	private final Map<Integer, Integer> inv = new HashMap<>();

	private static Map<Integer, Integer> items(int... idQty)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			m.merge(idQty[i], idQty[i + 1], Integer::sum);
		}
		return m;
	}

	private void hold(int tick, int... idQty)
	{
		for (int i = 0; i < idQty.length; i += 2)
		{
			inv.merge(idQty[i], idQty[i + 1], Integer::sum);
		}
		tracker.inventory(new HashMap<>(inv), tick);
	}

	@Before
	public void setUp()
	{
		tracker = new PickupTracker();
		inv.clear();
		inv.put(COINS, 1000);
		tracker.inventory(new HashMap<>(inv), 0);
	}

	@Test
	public void aDropPickedUpIsCountedUnderItsSource()
	{
		tracker.spawned("A", WHIP, 1, 10);
		tracker.spawned("A", COINS, 500, 10);
		tracker.spawned("A", BONES, 1, 10);
		tracker.loot("Abyssal demon", "NPC", items(WHIP, 1, COINS, 500, BONES, 1), 11);
		assertTrue(tracker.tick(11).isEmpty());

		// Coins join an existing stack; the bones are left on the floor and time out.
		tracker.despawned("A", WHIP, 1, 20);
		hold(20, WHIP, 1);
		tracker.despawned("A", COINS, 500, 21);
		hold(21, COINS, 500);
		List<Pickup> got = tracker.tick(21);
		assertEquals(2, got.size());
		assertEquals("Abyssal demon", got.get(0).source);
		assertEquals(WHIP, got.get(0).itemId);
		assertEquals(COINS, got.get(1).itemId);
		assertEquals(500, got.get(1).quantity);

		tracker.despawned("A", BONES, 1, 200);
		assertTrue(tracker.tick(205).isEmpty());
	}

	@Test
	public void anItemThatVanishesWithoutReachingTheInventoryIsNotAPickup()
	{
		tracker.spawned("B", WHIP, 1, 10);
		tracker.loot("Abyssal demon", "NPC", items(WHIP, 1), 10);
		tracker.despawned("B", WHIP, 1, 300);
		assertTrue(tracker.tick(300).isEmpty());
		assertTrue(tracker.tick(305).isEmpty());
	}

	@Test
	public void itemsAlreadyOnTheFloorOrDroppedByThePlayerAreNotLoot()
	{
		// Something on the floor before the kill, of the same kind, and an item the player drops and picks back up.
		tracker.spawned("C", BONES, 1, 5);
		tracker.tick(9);
		tracker.spawned("C", BONES, 1, 10);
		tracker.loot("Goblin", "NPC", items(BONES, 1), 10);
		tracker.spawned("D", WHIP, 1, 12);
		tracker.despawned("D", WHIP, 1, 14);
		hold(14, WHIP, 1);
		assertTrue(tracker.tick(14).isEmpty());

		tracker.despawned("C", BONES, 1, 16);
		hold(16, BONES, 1);
		List<Pickup> got = tracker.tick(16);
		assertEquals(1, got.size());
		assertEquals("Goblin", got.get(0).source);
	}

	@Test
	public void theFirstInventoryReadIsOnlyABaseline()
	{
		tracker.reset();
		tracker.spawned("E", WHIP, 1, 10);
		tracker.loot("Abyssal demon", "NPC", items(WHIP, 1), 10);
		tracker.despawned("E", WHIP, 1, 12);
		hold(12, WHIP, 1); // first read after a reset: the whip may have been there all along
		assertTrue(tracker.tick(12).isEmpty());
	}
}
