package com.xptempo;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiaryVarpsTest
{
	/** Must match `varps` in the XPTempo API's diary table; the server rejects any other id. */
	private static final int[] SERVER_VARPS = {
		1000, 1001, 1002, 1176, 1177, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187,
		1188, 1189, 1190, 1191, 1192, 1193, 1194, 1195, 1196, 1197, 1198, 1199, 1200, 2085, 2086,
	};

	@Test
	public void sendsExactlyTheVarpsTheServerDecodes()
	{
		int[] sent = DiaryVarps.VARPS.clone();
		Arrays.sort(sent);
		assertArrayEquals(SERVER_VARPS, sent);
		assertEquals(30, Arrays.stream(sent).distinct().count());
	}

	@Test
	public void tracksTaskAndCountVarpsOnly()
	{
		assertTrue(DiaryVarps.isTracked(1196)); // Ardougne tasks
		assertTrue(DiaryVarps.isTracked(1000)); // Karamja tasks
		assertTrue(DiaryVarps.isTracked(1721)); // per-tier task counts
		assertFalse(DiaryVarps.isTracked(101)); // quest points
		assertFalse(DiaryVarps.isTracked(300));
	}
}
