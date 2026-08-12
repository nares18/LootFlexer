package com.lootflexer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("Loot Flexer Settings")
public interface LootFlexerConfig extends Config
{
	@ConfigItem(
			keyName = "webhookURL",
			name = "Discord Webhook URL",
			description = "The webhook URL where drop notifications will be posted",
			position = 1)
	String getWebhookUrl();

	@ConfigItem(
			keyName = "minVal",
			name = "Minimum Value (GP)",
			description = "Only trigger notifications for items with a GE price above this threshold",
			position = 2)
	@Range(min = 1000000, max = Integer.MAX_VALUE)
	default int getMinValue() {
		return 1000000;
	}
}
