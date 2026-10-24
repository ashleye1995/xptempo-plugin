package com.xptempo.tracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which drops the player picked up. A drop's items appear on the floor (item spawned) and the loot event
 * names their source a tick or two later, so recent untagged floor items are matched to the loot by id and quantity.
 * A tagged item that leaves the floor while the inventory gains that item within a couple of ticks was picked up;
 * one that despawns without an inventory gain timed out or was taken by someone else. Ids are canonical (notes
 * folded into their item), and tiles are whatever key the caller gives, so this needs nothing from the client.
 */
public class PickupTracker
{
	/** How long a floor item waits for its loot event, and how far apart a despawn and an inventory gain may be. */
	static final int MATCH_TICKS = 2;
	/** Drops stay on the floor for at most three minutes (300 ticks) before they vanish. */
	static final int FORGET_TICKS = 320;

	public static final class Pickup
	{
		public final String source;
		public final String sourceType;
		public final int itemId;
		public final int quantity;

		Pickup(String source, String sourceType, int itemId, int quantity)
		{
			this.source = source;
			this.sourceType = sourceType;
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	private static final class Floor
	{
		final Object tile;
		final int itemId;
		final int quantity;
		final int tick;
		String source;
		String sourceType;

		Floor(Object tile, int itemId, int quantity, int tick)
		{
			this.tile = tile;
			this.itemId = itemId;
			this.quantity = quantity;
			this.tick = tick;
		}
	}

	private static final class Taken
	{
		final Floor floor;
		final int tick;

		Taken(Floor floor, int tick)
		{
			this.floor = floor;
			this.tick = tick;
		}
	}

	private final List<Floor> floor = new ArrayList<>();
	private final List<Taken> taken = new ArrayList<>();
	/** Inventory gains per item, with the tick of each, waiting to be matched to a despawn. */
	private final List<int[]> gains = new ArrayList<>();
	private Map<Integer, Integer> inventory;

	public void spawned(Object tile, int itemId, int quantity, int tick)
	{
		floor.add(new Floor(tile, itemId, quantity, tick));
	}

	/** The loot event for a drop: tags the matching items that just appeared on the floor with its source. */
	public void loot(String source, String sourceType, Map<Integer, Integer> items, int tick)
	{
		Map<Integer, Integer> left = new HashMap<>(items);
		for (Floor f : floor)
		{
			Integer want = left.get(f.itemId);
			if (f.source != null || want == null || want < f.quantity || tick - f.tick > MATCH_TICKS)
			{
				continue;
			}
			f.source = source;
			f.sourceType = sourceType;
			left.put(f.itemId, want - f.quantity);
		}
	}

	public void despawned(Object tile, int itemId, int quantity, int tick)
	{
		for (Iterator<Floor> it = floor.iterator(); it.hasNext(); )
		{
			Floor f = it.next();
			if (f.itemId == itemId && f.quantity == quantity && f.tile.equals(tile))
			{
				it.remove();
				if (f.source != null)
				{
					taken.add(new Taken(f, tick));
				}
				return;
			}
		}
	}

	/** The inventory as it is now, canonical id to quantity. The first call only sets the baseline. */
	public void inventory(Map<Integer, Integer> now, int tick)
	{
		if (inventory != null)
		{
			for (Map.Entry<Integer, Integer> e : now.entrySet())
			{
				int gain = e.getValue() - inventory.getOrDefault(e.getKey(), 0);
				if (gain > 0)
				{
					gains.add(new int[]{e.getKey(), gain, tick});
				}
			}
		}
		inventory = new HashMap<>(now);
	}

	/** Forgets the inventory baseline (logging out, hopping): the next inventory read starts afresh. */
	public void reset()
	{
		floor.clear();
		taken.clear();
		gains.clear();
		inventory = null;
	}

	/** Called once a tick: returns the pickups it can now be sure of, merged per source and item. */
	public List<Pickup> tick(int tick)
	{
		Map<String, Pickup> out = new LinkedHashMap<>();
		for (Iterator<Taken> it = taken.iterator(); it.hasNext(); )
		{
			Taken t = it.next();
			int got = 0;
			for (Iterator<int[]> g = gains.iterator(); g.hasNext() && got < t.floor.quantity; )
			{
				int[] gain = g.next();
				if (gain[0] != t.floor.itemId || Math.abs(gain[2] - t.tick) > MATCH_TICKS)
				{
					continue;
				}
				int use = Math.min(gain[1], t.floor.quantity - got);
				got += use;
				gain[1] -= use;
				if (gain[1] <= 0)
				{
					g.remove();
				}
			}
			if (got > 0)
			{
				String key = t.floor.source + "\u0000" + t.floor.itemId;
				Pickup prev = out.get(key);
				out.put(key, new Pickup(t.floor.source, t.floor.sourceType, t.floor.itemId, got + (prev == null ? 0 : prev.quantity)));
				it.remove();
			}
			else if (tick - t.tick > MATCH_TICKS)
			{
				it.remove(); // gone without reaching the inventory
			}
		}
		gains.removeIf(g -> tick - g[2] > MATCH_TICKS);
		floor.removeIf(f -> tick - f.tick > (f.source == null ? MATCH_TICKS : FORGET_TICKS));
		return new ArrayList<>(out.values());
	}
}
