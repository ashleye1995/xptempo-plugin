package com.xptempo.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BatchQueueTest
{
	private int seq;

	private Map<String, Object> event(String type, String owner)
	{
		Map<String, Object> ev = new LinkedHashMap<>();
		ev.put("seq", ++seq);
		ev.put("at", String.format("2026-10-10T12:%02d:%02d.000Z", (seq / 60) % 60, seq % 60));
		ev.put("type", type);
		ev.put("owner", owner);
		return ev;
	}

	private Map<String, Object> xp(String owner, int delta)
	{
		Map<String, Object> ev = event("xp", owner);
		ev.put("windowStart", ev.get("at"));
		ev.put("windowEnd", ev.get("at"));
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("skill", "PRAYER");
		d.put("xpDelta", delta);
		d.put("xp", 1000 + seq);
		d.put("level", 50);
		List<Map<String, Object>> deltas = new ArrayList<>();
		deltas.add(d);
		ev.put("deltas", deltas);
		return ev;
	}

	private static Payloads.Batch batch(String hash, String session, List<Map<String, Object>> events)
	{
		Payloads.Batch b = new Payloads.Batch();
		b.batchId = hash + session + events.hashCode();
		b.account = new Payloads.AccountInfo();
		b.account.hash = hash;
		b.sessionId = session;
		b.events = events;
		return b;
	}

	private static void assertNoMixing(BatchQueue q)
	{
		for (Payloads.Batch b : q.snapshot())
		{
			for (Map<String, Object> ev : b.events)
			{
				assertEquals("event of another stream merged into " + b.account.hash + "/" + b.sessionId,
					b.account.hash + "/" + b.sessionId, ev.get("owner"));
			}
		}
	}

	@Test
	public void mergesOnlyBatchesOfTheSameCharacterAndSession()
	{
		BatchQueue q = new BatchQueue();
		// A main and an alt alternate, plus a second session of the main: nothing may be merged across them.
		for (int i = 0; i < 120; i++)
		{
			String hash = i % 2 == 0 ? "main" : "alt";
			String session = i < 60 ? "s1" : "s2";
			List<Map<String, Object>> evs = new ArrayList<>();
			evs.add(event("heartbeat", hash + "/" + session));
			q.add(batch(hash, session, evs));
		}
		assertTrue(q.size() <= BatchQueue.MAX_BATCHES);
		assertNoMixing(q);
	}

	@Test
	public void mergingSumsXpAndKeepsSessionEvents()
	{
		BatchQueue q = new BatchQueue();
		String owner = "main/s1";
		List<Map<String, Object>> first = new ArrayList<>();
		first.add(event("session_start", owner));
		first.add(xp(owner, 100));
		q.add(batch("main", "s1", first));
		for (int i = 0; i < BatchQueue.MAX_BATCHES; i++)
		{
			List<Map<String, Object>> evs = new ArrayList<>();
			evs.add(xp(owner, 10));
			evs.add(event("heartbeat", owner));
			q.add(batch("main", "s1", evs));
		}
		assertEquals(BatchQueue.MAX_BATCHES, q.size());
		Payloads.Batch head = q.snapshot().get(0);
		assertEquals("session_start", head.events.get(0).get("type"));
		long xpEvents = head.events.stream().filter(e -> "xp".equals(e.get("type"))).count();
		assertEquals(1, xpEvents);
		long heartbeats = head.events.stream().filter(e -> "heartbeat".equals(e.get("type"))).count();
		assertEquals(1, heartbeats);
		int total = 0;
		for (Payloads.Batch b : q.snapshot())
		{
			for (Map<String, Object> ev : b.events)
			{
				if ("xp".equals(ev.get("type")))
				{
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> deltas = (List<Map<String, Object>>) ev.get("deltas");
					total += ((Number) deltas.get(0).get("xpDelta")).intValue();
				}
			}
		}
		assertEquals(100 + 10 * BatchQueue.MAX_BATCHES, total); // nothing lost, nothing counted twice
	}

	@Test
	public void neverMergesTheBatchBeingSent()
	{
		BatchQueue q = new BatchQueue();
		String owner = "main/s1";
		List<Map<String, Object>> evs = new ArrayList<>();
		evs.add(xp(owner, 5));
		q.add(batch("main", "s1", evs));
		Payloads.Batch sending = q.take();
		for (int i = 0; i < BatchQueue.MAX_BATCHES + 5; i++)
		{
			List<Map<String, Object>> more = new ArrayList<>();
			more.add(xp(owner, 1));
			q.add(batch("main", "s1", more));
		}
		assertSame(sending, q.snapshot().get(0));
		assertEquals(1, sending.events.size());
		q.removeHead(sending);
		assertTrue(q.snapshot().stream().noneMatch(b -> b == sending));
	}

	@Test
	public void trimmingKeepsSessionsSkillsAndEventsButDropsSupersededSnapshots()
	{
		List<Map<String, Object>> evs = new ArrayList<>();
		evs.add(event("session_start", "x"));
		evs.add(event("skills", "x"));
		for (int i = 0; i < 400; i++)
		{
			Map<String, Object> c = event("container", "x");
			c.put("container", "INVENTORY");
			evs.add(c);
			evs.add(event("heartbeat", "x"));
		}
		evs.add(event("event", "x"));
		evs.add(event("session_end", "x"));
		List<Map<String, Object>> out = BatchQueue.trim(evs);
		assertTrue(out.size() <= BatchQueue.MAX_EVENTS);
		assertEquals("session_start", out.get(0).get("type"));
		assertEquals("session_end", out.get(out.size() - 1).get("type"));
		assertEquals(1, out.stream().filter(e -> "container".equals(e.get("type"))).count());
		assertEquals(1, out.stream().filter(e -> "heartbeat".equals(e.get("type"))).count());
		assertEquals(1, out.stream().filter(e -> "event".equals(e.get("type"))).count());
	}

	@Test
	public void clearDropsEverythingIncludingTheBatchOnTheWire()
	{
		BatchQueue q = new BatchQueue();
		List<Map<String, Object>> a = new ArrayList<>();
		a.add(event("heartbeat", "main/s1"));
		q.add(batch("main", "s1", a));
		Payloads.Batch sending = q.take();
		List<Map<String, Object>> b = new ArrayList<>();
		b.add(event("heartbeat", "main/s1"));
		q.add(batch("main", "s1", b));
		q.clear(); // uploads switched off: if the request on the wire fails, it is not retried
		assertEquals(0, q.size());
		q.release(sending); // the failed request comes back: nothing to retry
		assertTrue(q.isEmpty());
	}

	@Test
	public void aBatchThatMayHaveBeenAppliedIsNeverMergedIntoANewOne()
	{
		BatchQueue q = new BatchQueue();
		String owner = "main/s1";
		for (int i = 0; i < BatchQueue.MAX_BATCHES; i++)
		{
			List<Map<String, Object>> evs = new ArrayList<>();
			evs.add(xp(owner, 100));
			q.add(batch("main", "s1", evs));
		}
		Payloads.Batch tried = q.take();
		q.release(tried); // timed out: the server may or may not have applied it
		List<Map<String, Object>> more = new ArrayList<>();
		more.add(xp(owner, 1));
		q.add(batch("main", "s1", more));
		assertSame(tried, q.snapshot().get(0)); // re-sent unchanged, same batchId, so the server can deduplicate
	}

	@Test
	public void aLongLootHeavyOutageNeverBuildsAnOversizedBatchAndKeepsXpAndTheSession()
	{
		BatchQueue q = new BatchQueue();
		String owner = "main/s1";
		int xpGenerated = 0;
		for (int i = 0; i < 1500; i++)
		{
			List<Map<String, Object>> evs = new ArrayList<>();
			if (i == 0)
			{
				evs.add(event("session_start", owner));
				evs.add(event("skills", owner));
			}
			evs.add(xp(owner, 10));
			xpGenerated += 10;
			evs.add(event("heartbeat", owner));
			for (int k = 0; k < 25; k++)
			{
				evs.add(event("loot", owner));
			}
			q.add(batch("main", "s1", evs));
		}
		int xpQueued = 0;
		boolean sessionStart = false;
		for (Payloads.Batch b : q.snapshot())
		{
			assertTrue("batch of " + b.events.size(), b.events.size() <= BatchQueue.MAX_EVENTS);
			for (Map<String, Object> ev : b.events)
			{
				sessionStart |= "session_start".equals(ev.get("type"));
				if ("xp".equals(ev.get("type")))
				{
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> deltas = (List<Map<String, Object>>) ev.get("deltas");
					xpQueued += ((Number) deltas.get(0).get("xpDelta")).intValue();
				}
			}
		}
		assertTrue(q.size() <= BatchQueue.MAX_BATCHES);
		assertTrue("the session_start survives", sessionStart);
		// Batches are dropped only as a last resort (whole loot-heavy batches); XP inside kept batches is never trimmed.
		assertTrue("xp " + xpQueued + " of " + xpGenerated, xpQueued > 0);
	}

	@Test
	public void trimmingNeverDropsXp()
	{
		List<Map<String, Object>> evs = new ArrayList<>();
		evs.add(xp("x", 42));
		for (int i = 0; i < 600; i++)
		{
			evs.add(event("heartbeat", "x"));
		}
		List<Map<String, Object>> out = BatchQueue.trim(BatchQueue.compactXp(evs));
		assertEquals(1, out.stream().filter(e -> "xp".equals(e.get("type"))).count());
	}

	@Test
	public void anOversizedHeadIsSplitBeforeSending()
	{
		BatchQueue q = new BatchQueue();
		List<Map<String, Object>> evs = new ArrayList<>();
		for (int i = 0; i < BatchQueue.MAX_EVENTS + 20; i++)
		{
			evs.add(event("loot", "main/s1"));
		}
		q.add(batch("main", "s1", evs));
		Payloads.Batch head = q.take();
		assertEquals(BatchQueue.MAX_EVENTS, head.events.size());
		assertEquals(2, q.size());
	}

	@Test
	public void aBatchThatNeverReachedTheServerCanBeMergedAgain()
	{
		BatchQueue q = new BatchQueue();
		String owner = "main/s1";
		for (int i = 0; i < BatchQueue.MAX_BATCHES; i++)
		{
			List<Map<String, Object>> evs = new ArrayList<>();
			evs.add(xp(owner, 1));
			q.add(batch("main", "s1", evs));
		}
		Payloads.Batch tried = q.take();
		q.notApplied(tried); // connection refused: certainly not applied
		List<Map<String, Object>> more = new ArrayList<>();
		more.add(xp(owner, 1));
		q.add(batch("main", "s1", more));
		assertTrue(q.snapshot().get(0) != tried); // merged into a new batch, nothing double-counted
	}

	@Test
	public void switchingACategoryOffRemovesItsEventsFromQueuedBatches()
	{
		BatchQueue q = new BatchQueue();
		List<Map<String, Object>> a = new ArrayList<>();
		Map<String, Object> bank = event("container", "main/s1");
		bank.put("container", "BANK");
		a.add(bank);
		a.add(xp("main/s1", 5));
		q.add(batch("main", "s1", a));
		List<Map<String, Object>> b = new ArrayList<>();
		Map<String, Object> bankOnly = event("container", "main/s1");
		bankOnly.put("container", "BANK");
		b.add(bankOnly);
		q.add(batch("main", "s1", b));
		q.removeEvents(ev -> "container".equals(ev.get("type")) && "BANK".equals(ev.get("container")));
		assertEquals(1, q.size()); // the bank-only batch is gone
		assertEquals("xp", q.snapshot().get(0).events.get(0).get("type"));
	}
}
