package com.xptempo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(XPTempoConfig.GROUP)
public interface XPTempoConfig extends Config
{
	String GROUP = "xptempo";
	String KEY_ENABLED = "enabled";
	String KEY_PAIRING_CODE = "pairingCode";
	String KEY_API_KEY = "apiKey";

	@ConfigItem(
		keyName = KEY_ENABLED,
		name = "Enable XPTempo",
		description = "Sends your own characters' skills and XP, activity, sessions and deaths, quest, achievement diary and combat achievement progress, and (each switchable below) inventory and worn items, bank, Grand Exchange offers, and drops and kill counts to your XPTempo account. Nothing is sent until you pair.",
		warning = "<html>This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers.<br>"
			+ "XPTempo (api.xptempo.com) receives, for every character you play on this RuneLite profile: name, account ID, account type and world,<br>"
			+ "skills and XP, what you are doing, sessions and deaths, quest, diary and combat achievement progress,<br>"
			+ "and, unless switched off under \"What to send\": inventory, worn items, bank, GE offers, drops and kill counts.<br>"
			+ "Nothing is sent until you pair. You can switch this off at any time. Privacy policy: xptempo.com/privacy</html>",
		position = 0
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_PAIRING_CODE,
		name = "Pairing code",
		description = "The 8-character code shown in the XPTempo app. Switch on \"Enable XPTempo\" first; the code is exchanged for an API key once, then cleared. One pairing covers every character played on this RuneLite profile.",
		position = 1
	)
	default String pairingCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = KEY_API_KEY,
		name = "API key",
		description = "Filled in automatically after pairing. Revoke it from the XPTempo app if this computer is lost.",
		secret = true,
		position = 2
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigSection(
		name = "What to send",
		description = "These four can be switched off. Skills and XP, activity, sessions, deaths and quest, diary and combat achievement progress are always sent while XPTempo is enabled.",
		position = 3
	)
	String categories = "categories";

	@ConfigItem(keyName = "uploadInventory", name = "Inventory and equipment", position = 4, section = categories,
		description = "Send your inventory and worn equipment (item ids and quantities, valued on the server).")
	default boolean uploadInventory()
	{
		return true;
	}

	@ConfigItem(keyName = "uploadBank", name = "Bank", position = 5, section = categories,
		description = "Send your bank contents when you open and close the bank.")
	default boolean uploadBank()
	{
		return true;
	}

	@ConfigItem(keyName = "uploadGe", name = "Grand Exchange offers", position = 6, section = categories,
		description = "Send your GE offers and their progress.")
	default boolean uploadGe()
	{
		return true;
	}

	@ConfigItem(keyName = "uploadLoot", name = "Drops and kill counts", position = 7, section = categories,
		description = "Send what monsters and chests drop you (item ids and quantities, valued on the server), the kill counts the game announces, and the collection log and combat achievement totals when you open them.")
	default boolean uploadLoot()
	{
		return true;
	}

}
