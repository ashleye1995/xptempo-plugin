package com.xpulse;

import com.xpulse.tracker.XpTracker;
import java.util.List;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class XpTrackerTest
{
	@Test
	public void accumulatesDeltasPerSkillAndDrains()
	{
		XpTracker t = new XpTracker();
		t.seed(Skill.PRAYER, 737_802, 70);
		t.seed(Skill.ATTACK, 1_000, 10);
		assertNull(t.record(Skill.PRAYER, 738_054, 70, 1_000));
		assertNull(t.record(Skill.PRAYER, 738_306, 70, 2_000));
		assertNull(t.record(Skill.ATTACK, 1_050, 10, 3_000));
		assertTrue(t.hasPending());
		assertEquals(Skill.ATTACK, t.getActiveSkill());

		List<XpTracker.Delta> deltas = t.drain();
		assertEquals(2, deltas.size());
		XpTracker.Delta prayer = deltas.stream().filter(d -> d.skill == Skill.PRAYER).findFirst().orElseThrow(AssertionError::new);
		assertEquals(504, prayer.xpDelta);
		assertEquals(738_306, prayer.xp);
		assertEquals(70, prayer.level);
		assertFalse(t.hasPending());
	}

	@Test
	public void boostsAndFirstSightRecordNothing()
	{
		XpTracker t = new XpTracker();
		assertNull(t.record(Skill.MAGIC, 5_000, 20, 0)); // no baseline yet
		assertFalse(t.hasPending());
		assertNull(t.record(Skill.MAGIC, 5_000, 25, 1)); // boosted level, same xp
		assertFalse(t.hasPending());
	}

	@Test
	public void detectsLevelUps()
	{
		XpTracker t = new XpTracker();
		t.seed(Skill.PRAYER, 814_000, 70);
		XpTracker.LevelUp up = t.record(Skill.PRAYER, 814_445, 71, 0);
		assertNotNull(up);
		assertEquals(Skill.PRAYER, up.skill);
		assertEquals(71, up.level);
	}

	@Test
	public void xpPerHourUsesATenMinuteWindowAndTheRealSpan()
	{
		XpTracker t = new XpTracker();
		t.seed(Skill.FISHING, 0, 1);
		long now = 20 * 60_000L;
		t.record(Skill.FISHING, 1_000, 1, now - 15 * 60_000L); // outside the window
		t.record(Skill.FISHING, 2_000, 1, now - 5 * 60_000L);  // inside: span starts here
		t.record(Skill.FISHING, 2_500, 1, now);                // inside
		assertEquals(1_500 * 12, t.xpPerHour(now));            // 1,500 XP over 5 minutes

		XpTracker fresh = new XpTracker();
		fresh.seed(Skill.PRAYER, 0, 1);
		fresh.record(Skill.PRAYER, 5_040, 1, now - 60_000L);   // one minute of training
		fresh.record(Skill.PRAYER, 5_041, 1, now);
		assertEquals(5_041 * 60, fresh.xpPerHour(now));        // not diluted to a ten-minute average
		assertEquals(0, new XpTracker().xpPerHour(now));
	}
}
