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
		description = "Sends your own character's skills and XP, activity and session time, bank, inventory, worn items and Grand Exchange offers to your XPTempo account. Nothing is sent until you pair.",
		warning = "Enabling XPTempo sends your character's data (skills and XP, activity and session time, bank, inventory, worn items and Grand Exchange offers) and your IP address to XPTempo's servers at api.xptempo.com, which are not run or verified by the RuneLite developers. Nothing is sent until you pair the plugin with your account, and you can turn this off at any time.",
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

}
