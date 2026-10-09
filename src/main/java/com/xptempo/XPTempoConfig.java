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
	String KEY_API_BASE_URL = "apiBaseUrl";

	@ConfigItem(
		keyName = KEY_ENABLED,
		name = "Send data to XPTempo",
		description = "Uploads your own character's skills and XP, activity and session time to your XPTempo account. Nothing is sent until you pair.",
		warning = "This sends your character's data (skills and XP, activity, session time; later bank, inventory and GE offers) and your IP address to XPTempo's servers, which are not run or verified by RuneLite developers.",
		position = 0
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = KEY_PAIRING_CODE,
		name = "Pairing code",
		description = "The 8-character code shown in the XPTempo app. It is exchanged for an API key once, then cleared.",
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
		description = "Each category can be switched off; skills and session time are always sent while enabled.",
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

	@ConfigSection(
		name = "Advanced",
		description = "Settings you should not need to change.",
		position = 10,
		closedByDefault = true
	)
	String advanced = "advanced";

	@ConfigItem(
		keyName = KEY_API_BASE_URL,
		name = "API base URL",
		description = "Where batches are sent. Only change this for a self-hosted XPTempo API.",
		section = advanced,
		position = 11
	)
	default String apiBaseUrl()
	{
		return "http://127.0.0.1:3000";
	}
}
