package com.xpulse;

import com.xpulse.tracker.GeOfferTracker;
import com.xpulse.tracker.GeOfferTracker.SavedOffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class GeOfferTrackerTest
{
	private static final class MemoryStore implements GeOfferTracker.Store
	{
		final Map<Integer, SavedOffer> slots = new HashMap<>();

		@Override
		public SavedOffer load(int slot)
		{
			return slots.get(slot);
		}

		@Override
		public void save(int slot, SavedOffer offer)
		{
			slots.put(slot, offer);
		}

		@Override
		public void clear(int slot)
		{
			slots.remove(slot);
		}
	}

	private static GrandExchangeOffer offer(GrandExchangeOfferState state, int item, long price, int total, int sold, long spent)
	{
		return new GrandExchangeOffer()
		{
			@Override
			public int getQuantitySold()
			{
				return sold;
			}

			@Override
			public int getItemId()
			{
				return item;
			}

			@Override
			public int getTotalQuantity()
			{
				return total;
			}

			@Override
			public long getPrice()
			{
				return price;
			}

			@Override
			public long getSpent()
			{
				return spent;
			}

			@Override
			public GrandExchangeOfferState getState()
			{
				return state;
			}
		};
	}

	@Test
	public void lifetimeOfABuyOffer()
	{
		MemoryStore store = new MemoryStore();
		AtomicInteger ids = new AtomicInteger();
		GeOfferTracker t = new GeOfferTracker(store, () -> "id-" + ids.incrementAndGet());

		Map<String, Object> placed = t.onOffer(2, offer(GrandExchangeOfferState.BUYING, 536, 3900, 100, 0, 0));
		assertNotNull(placed);
		assertEquals("id-1", placed.get("clientOfferId"));
		assertEquals("ACTIVE", placed.get("state"));
		assertEquals("BUY", placed.get("offerType"));

		// Login burst / repeated event with no progress: nothing to send, same id kept.
		assertNull(t.onOffer(2, offer(GrandExchangeOfferState.BUYING, 536, 3900, 100, 0, 0)));

		Map<String, Object> progress = t.onOffer(2, offer(GrandExchangeOfferState.BUYING, 536, 3900, 100, 40, 156_000));
		assertEquals("id-1", progress.get("clientOfferId"));
		assertEquals(40, progress.get("quantitySold"));

		Map<String, Object> done = t.onOffer(2, offer(GrandExchangeOfferState.BOUGHT, 536, 3900, 100, 100, 390_000));
		assertEquals("COMPLETED", done.get("state"));
		assertEquals(false, done.get("collected"));

		Map<String, Object> collected = t.onOffer(2, offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
		assertEquals("id-1", collected.get("clientOfferId"));
		assertEquals(true, collected.get("collected"));
		assertEquals("COMPLETED", collected.get("state"));
		assertNull(store.load(2));
		assertNull(t.onOffer(2, offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0)));
	}

	@Test
	public void aNewOfferInTheSameSlotGetsANewIdAndAnEmptiedActiveOfferCountsAsCancelled()
	{
		MemoryStore store = new MemoryStore();
		AtomicInteger ids = new AtomicInteger();
		GeOfferTracker t = new GeOfferTracker(store, () -> "id-" + ids.incrementAndGet());

		Map<String, Object> first = t.onOffer(0, offer(GrandExchangeOfferState.SELLING, 4151, 800_000, 2, 1, 800_000));
		Map<String, Object> second = t.onOffer(0, offer(GrandExchangeOfferState.SELLING, 4151, 810_000, 2, 0, 0)); // different price
		assertNotEquals(first.get("clientOfferId"), second.get("clientOfferId"));
		assertEquals("SELL", second.get("offerType"));

		Map<String, Object> emptied = t.onOffer(0, offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0));
		assertEquals("CANCELLED", emptied.get("state"));
		assertEquals(true, emptied.get("collected"));
	}
}
