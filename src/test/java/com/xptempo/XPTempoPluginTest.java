package com.xptempo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Dev-client launcher: `./gradlew run` (VM flag -ea and --developer-mode are set by the run task). */
public class XPTempoPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(XPTempoPlugin.class);
		RuneLite.main(args);
	}
}
