package com.xptempo;

import net.runelite.api.gameval.VarPlayerID;

/**
 * The player variables that hold achievement diary progress. Their raw values are sent with the milestones
 * event and decoded on the XPTempo server into individual tasks (the same variables WikiSync and Quest
 * Helper read). Nothing here touches the game beyond reading the player's own varps.
 */
final class DiaryVarps
{
	/** Every task bit, Karamja's task varbits, and the tier complete and reward flags. Sent as raw values. */
	static final int[] VARPS = {
		VarPlayerID.ATJUN_TASKS_1, VarPlayerID.ATJUN_TASKS_2, VarPlayerID.ATJUN_TASKS_3,
		VarPlayerID.VARROCK_ACHIEVEMENT_DIARY, VarPlayerID.VARROCK_ACHIEVEMENT_DIARY2,
		VarPlayerID.KANDARIN_ACHIEVEMENT_DIARY, VarPlayerID.KANDARIN_ACHIEVEMENT_DIARY2,
		VarPlayerID.MORYTANIA_ACHIEVEMENT_DIARY, VarPlayerID.MORYTANIA_ACHIEVEMENT_DIARY2,
		VarPlayerID.WESTERN_ACHIEVEMENT_DIARY, VarPlayerID.WESTERN_ACHIEVEMENT_DIARY2,
		VarPlayerID.FREMENNIK_ACHIEVEMENT_DIARY, VarPlayerID.FREMENNIK_ACHIEVEMENT_DIARY2,
		VarPlayerID.FALADOR_ACHIEVEMENT_DIARY, VarPlayerID.FALADOR_ACHIEVEMENT_DIARY2,
		VarPlayerID.ACHIEVEMENT_DIARY, VarPlayerID.ACHIEVEMENT_DIARY2,
		VarPlayerID.ACHIEVEMENT_DIARY_REWARDS, VarPlayerID.ACHIEVEMENT_DIARY_REWARDS2,
		VarPlayerID.WILDERNESS_ACHIEVEMENT_DIARY, VarPlayerID.WILDERNESS_ACHIEVEMENT_DIARY2,
		VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY, VarPlayerID.LUMB_DRAY_ACHIEVEMENT_DIARY2,
		VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY, VarPlayerID.ARDOUNGE_ACHIEVEMENT_DIARY2,
		VarPlayerID.DESERT_ACHIEVEMENT_DIARY, VarPlayerID.DESERT_ACHIEVEMENT_DIARY2,
		VarPlayerID.ATJUN_TASKS_4,
		VarPlayerID.KOUREND_ACHIEVEMENT_DIARY, VarPlayerID.KOUREND_ACHIEVEMENT_DIARY2,
	};

	/** The per-tier task counts live here; they are read as varbits, but a change to them should resend the snapshot. */
	static final int[] COUNT_VARPS = {
		VarPlayerID.ACHIEVEMENT_DIARY_TASKCOUNTS, VarPlayerID.ACHIEVEMENT_DIARY_TASKCOUNTS2, VarPlayerID.ACHIEVEMENT_DIARY_TASKCOUNTS3,
		VarPlayerID.ACHIEVEMENT_DIARY_TASKCOUNTS4, VarPlayerID.ACHIEVEMENT_DIARY_TASKCOUNTS5, VarPlayerID.ACHIEVEMENT_DIARY_TASKCOUNTS6,
	};

	private DiaryVarps()
	{
	}

	/** True when a change to this varp can change diary progress. */
	static boolean isTracked(int varp)
	{
		for (int v : VARPS)
		{
			if (v == varp)
			{
				return true;
			}
		}
		for (int v : COUNT_VARPS)
		{
			if (v == varp)
			{
				return true;
			}
		}
		return false;
	}
}
