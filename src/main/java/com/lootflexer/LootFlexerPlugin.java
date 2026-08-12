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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
	private ScheduledExecutorService executor;

	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

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
			int gePrice = itemManager.getItemPrice(itemId);
            int totalValue = gePrice * quantity;
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
					// 2. Offload the file writing to a background thread to prevent game lag
					executor.submit(() -> {
						try {
							boolean success = false;
							Path tempFile = Files.createTempFile("drop_", ".png");
							// 3. Translate the BufferedImage into a PNG file
							// Cast the generic Image to a BufferedImage (which implements RenderedImage)
							if (image instanceof BufferedImage) {
								BufferedImage bufferedImage = (BufferedImage) image;
								success = ImageIO.write(bufferedImage, "png", tempFile.toFile());
							} else {
								// Safe fallback conversion if it isn't an instance of BufferedImage
								BufferedImage bufferedImage = new BufferedImage(
										image.getWidth(null),
										image.getHeight(null),
										BufferedImage.TYPE_INT_ARGB
								);
								Graphics2D g2d = bufferedImage.createGraphics();
								g2d.drawImage(image, 0, 0, null);
								g2d.dispose();

								success = ImageIO.write(bufferedImage, "png", tempFile.toFile());
							}
							if (success) {
								// PNG successfully saved
								String boundary = "----WebKitFormBoundary" + new Random().nextInt(1000000);
								String payload = String.format(
										"{\"content\": \"🎉 %s %s **%s** from %s 🎉   *Value: %s GP*\"}",
										client.getLocalPlayer().getName(),
										getRandomLootVerb(),
										def.getName(),
										npcName,
										String.format("%,d", totalValue)
								);

								log.debug(payload);
								byte[] body = buildMultipartBody(payload, tempFile, boundary);

								HttpRequest request = HttpRequest.newBuilder()
										.uri(URI.create(webhookUrl))
										.header("Content-Type", "multipart/form-data; boundary=" + boundary)
										.POST(HttpRequest.BodyPublishers.ofByteArray(body))
										.build();

								HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
										.thenAccept(response -> {
											if (response.statusCode() != 200 && response.statusCode() != 204) {
												log.warn("Discord webhook failed: {}", response.statusCode());
											}
										})
										.exceptionally(e -> {
											log.error("Failed to post to Discord", e);
											return null;
										})
										.whenComplete((res, err) -> {
											try {
												Files.deleteIfExists(tempFile);
											} catch (IOException e) {
												log.warn("Failed to delete temp screenshot", e);
											}
										});
							}

						} catch (IOException e) {
							log.error(e.getMessage(), e);
						}
					});
				});
			} catch (Exception e) {
				log.error("Error preparing Discord webhook request", e);
			}
		}
	}

	private byte[] buildMultipartBody(String payload, Path imageFile, String boundary) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("--").append(boundary).append("\r\n")
				.append("Content-Disposition: form-data; name=\"payload_json\"\r\n\r\n")
				.append(payload).append("\r\n");

		sb.append("--").append(boundary).append("\r\n")
				.append("Content-Disposition: form-data; name=\"file\"; filename=\"drop.png\"\r\n")
				.append("Content-Type: image/png\r\n\r\n");

		byte[] imageBytes = Files.readAllBytes(imageFile);
		byte[] prefix = sb.toString().getBytes();
		byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes();

		byte[] fullBody = new byte[prefix.length + imageBytes.length + suffix.length];
		System.arraycopy(prefix, 0, fullBody, 0, prefix.length);
		System.arraycopy(imageBytes, 0, fullBody, prefix.length, imageBytes.length);
		System.arraycopy(suffix, 0, fullBody, prefix.length + imageBytes.length, suffix.length);
		return fullBody;
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
