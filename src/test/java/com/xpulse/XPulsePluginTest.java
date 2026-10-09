package com.xpulse;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Dev-client launcher: `./gradlew run` (VM flag -ea and --developer-mode are set by the run task). */
public class XPulsePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(XPulsePlugin.class);
		RuneLite.main(args);
	}
}
