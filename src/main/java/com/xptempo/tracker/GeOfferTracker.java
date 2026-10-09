package com.xptempo.tracker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Turns GrandExchangeOfferChanged into ingest events with a stable client offer id per offer.
 * Mirrors the core GE plugin's "saved offer per slot" trick: the last known offer of each slot is
 * persisted (RS-profile config), so a restart or the login burst never mints a second id for the
 * same offer, and a slot going EMPTY after a finished offer becomes a "collected" update.
 */
public final class GeOfferTracker
{
	/** Persisted per slot (Gson). Field names are part of the saved format; do not rename. */
	public static final class SavedOffer
	{
		public String clientOfferId;
		public int itemId;
		public long price;
		public int totalQuantity;
		public int quantitySold;
		public long spent;
		public String state; // ACTIVE | COMPLETED | CANCELLED
		public String type;  // BUY | SELL
	}

	public interface Store
	{
		SavedOffer load(int slot);

		void save(int slot, SavedOffer offer);

		void clear(int slot);
	}

	private final Store store;
	private final Supplier<String> idGenerator;

	public GeOfferTracker(Store store, Supplier<String> idGenerator)
	{
		this.store = store;
		this.idGenerator = idGenerator;
	}

	/** Returns the `ge_offer` event fields to send, or null when nothing changed. */
	public Map<String, Object> onOffer(int slot, GrandExchangeOffer offer)
	{
		GrandExchangeOfferState rlState = offer.getState();
		SavedOffer saved = store.load(slot);
		if (rlState == GrandExchangeOfferState.EMPTY)
		{
			if (saved == null)
			{
				return null;
			}
			// The slot was emptied: a finished offer was collected, or an active one was cancelled and
			// collected between two observations.
			String finalState = saved.state;
			if ("ACTIVE".equals(saved.state))
			{
				finalState = saved.quantitySold >= saved.totalQuantity ? "COMPLETED" : "CANCELLED";
			}
			saved.state = finalState;
			store.clear(slot);
			return fields(slot, saved, true);
		}

		String type = isBuy(rlState) ? "BUY" : "SELL";
		String state = mapState(rlState);
		boolean sameOffer = saved != null
			&& saved.itemId == offer.getItemId()
			&& saved.price == offer.getPrice()
			&& saved.totalQuantity == offer.getTotalQuantity()
			&& type.equals(saved.type)
			&& offer.getQuantitySold() >= saved.quantitySold;
		boolean changed;
		if (!sameOffer)
		{
			saved = new SavedOffer();
			saved.clientOfferId = idGenerator.get();
			saved.itemId = offer.getItemId();
			saved.price = offer.getPrice();
			saved.totalQuantity = offer.getTotalQuantity();
			saved.type = type;
			changed = true;
		}
		else
		{
			changed = saved.quantitySold != offer.getQuantitySold()
				|| saved.spent != offer.getSpent()
				|| !state.equals(saved.state);
		}
		saved.quantitySold = offer.getQuantitySold();
		saved.spent = offer.getSpent();
		saved.state = state;
		store.save(slot, saved);
		return changed ? fields(slot, saved, false) : null;
	}

	private static Map<String, Object> fields(int slot, SavedOffer o, boolean collected)
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("clientOfferId", o.clientOfferId);
		m.put("slot", slot);
		m.put("itemId", o.itemId);
		m.put("offerType", o.type);
		m.put("price", o.price);
		m.put("totalQuantity", o.totalQuantity);
		m.put("quantitySold", o.quantitySold);
		m.put("spent", o.spent);
		m.put("state", o.state);
		m.put("collected", collected);
		return m;
	}

	static boolean isBuy(GrandExchangeOfferState s)
	{
		return s == GrandExchangeOfferState.BUYING || s == GrandExchangeOfferState.BOUGHT
			|| s == GrandExchangeOfferState.CANCELLED_BUY;
	}

	static String mapState(GrandExchangeOfferState s)
	{
		switch (s)
		{
			case BUYING:
			case SELLING:
				return "ACTIVE";
			case BOUGHT:
			case SOLD:
				return "COMPLETED";
			default:
				return "CANCELLED";
		}
	}
}
