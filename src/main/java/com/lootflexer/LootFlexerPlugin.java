package com.lootflexer;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.*;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;
import okhttp3.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@PluginDescriptor(
	name = "Loot Flexer",
		description = "Posts screenshots of high-value drops to Discord",
		tags = {"drop", "screenshot", "discord"}
)
public class LootFlexerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private LootFlexerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executor;

	@Override
	protected void startUp()
	{
		log.debug("Loot Flexer started!");
	}

	@Override
	protected void shutDown()
	{
		log.debug("Loot Flexer stopped!");
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{

		NPC npc = event.getNpc();
		String npcName = npc.getName();
		Collection<ItemStack> items = event.getItems();

		// Iterate through dropped items
		for (ItemStack item : items) {
			int itemId = item.getId();
			int quantity = item.getQuantity();

			if (itemId == -1) return;

			ItemComposition def = client.getItemDefinition(itemId);
			log.debug(String.format("got drop: %s",def.getName()));
			long gePrice = itemManager.getItemPrice(itemId);
			long totalValue = gePrice * quantity;
			if (totalValue < config.getMinValue()) {
				log.debug(String.format("Skipping drop: Value of %d is below threshold of %d",totalValue,config.getMinValue()));
				continue;
			}

			String webhookUrl = config.getWebhookUrl();
			if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
				log.debug("Skipping drops: No webhook URL configured");
				return;
			}


			try {
				drawManager.requestNextFrameListener(image -> {
					executor.submit(() -> {
						try {
							BufferedImage bufferedImage = ImageUtil.bufferedImageFromImage(image);
							if (bufferedImage == null) {
								return;
							}

							ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
							if (!ImageIO.write(bufferedImage, "png", outputStream)) {
								log.warn("Failed to encode screenshot as PNG");
								return;
							}
							byte[] imageBytes = outputStream.toByteArray();

							String payload = String.format(
									"{\"content\": \"🎉 %s %s **%s** from %s 🎉   *Value: %s GP*\"}",
									client.getLocalPlayer().getName(),
									getRandomLootVerb(),
									def.getName(),
									npcName,
									String.format("%,d", totalValue)
							);
							log.debug(payload);

							RequestBody imageBody = RequestBody.create(MediaType.parse("image/png"), imageBytes);
							RequestBody body = new MultipartBody.Builder()
									.setType(MultipartBody.FORM)
									.addFormDataPart("payload_json", payload)
									.addFormDataPart("file", "drop.png", imageBody)
									.build();

							Request request = new Request.Builder()
									.url(webhookUrl)
									.header("User-Agent", "LootFlexer/1.0 (+https://github.com/Nares18/loot-flexer)")
									.post(body)
									.build();

							okHttpClient.newCall(request).enqueue(new Callback()
							{
								@Override
								public void onResponse(Call call, Response response)
								{
									try
									{
										if (response.code() != 200 && response.code() != 204)
										{
											log.warn("Discord webhook failed: {}", response.code());
										}
									}
									finally
									{
										response.close();
									}
								}

								@Override
								public void onFailure(Call call, IOException e)
								{
									log.error("Failed to post to Discord", e);
								}
							});
						} catch (IOException e) {
							log.error("Screenshot encoding or request error", e);
						}
					});
				});
			} catch (Exception e) {
				log.error("Error preparing Discord webhook request", e);
			}
		}
	}

	public String getRandomLootVerb() {
		// 1. Define your array of string options
		String[] verbs = {"looted", "received", "snagged", "yoinked", "secured", "spooned", "milked", "nabbed", "plundered", "pocketed", "swiped", "bagged"};

		// 2. Initialize the random generator
		Random random = new Random();

		// 3. Select a random index based on the size of the array
		int randomIndex = random.nextInt(verbs.length);

		// 4. Return the selected string
		return verbs[randomIndex];
	}

	@Provides
	LootFlexerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootFlexerConfig.class);
	}
}
