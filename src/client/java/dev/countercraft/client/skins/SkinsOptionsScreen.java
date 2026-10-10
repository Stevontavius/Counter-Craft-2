package dev.countercraft.client.skins;

import com.mojang.blaze3d.platform.NativeImage;
import dev.countercraft.CounterCraft;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Skins Options (title screen / pause menu): Skinchanger Mode unlocks every cosmetic locally; the Inventory Grabber
 * reads a Steam profile's public CS2 inventory (display name, avatar, Steam level, Skinport value) and can copy it:
 * only those items are unlocked then. View Profile opens the profile in the browser (after Minecraft's link prompt).
 */
public final class SkinsOptionsScreen extends Screen {
	private static final Identifier AVATAR = CounterCraft.id("dynamic/steam_avatar");
	private final Screen parent;
	private EditBox input;
	private Button copy;
	private Button view;
	/** Survives re-inits (window resize) while the screen is open. */
	private static SteamGrabber.Profile profile;
	private static String status = "";
	private static Double value;
	private static boolean pricing;
	private static boolean avatarReady;
	private static Set<String> unlocks = Set.of();
	private static String lastInput = "";

	public SkinsOptionsScreen(Screen parent) {
		super(Component.literal("Skins Options"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = 44;
		addRenderableWidget(Checkbox.builder(Component.literal("Skinchanger Mode: unlock every skin, knife, glove, agent and music kit locally"), font)
				.pos(cx - 170, y).maxWidth(340).selected(SkinsConfig.skinchanger()).onValueChange((c, on) -> {
					SkinsConfig.setSkinchanger(on);
					if (!on) {
						dev.countercraft.client.loadout.LoadoutConfig.unequipUnowned(); // hidden again: back to what you own
					}
				}).build());
		y += 46;
		input = new EditBox(font, cx - 170, y, 260, 20, Component.literal("SteamID / profile URL / custom URL name"));
		input.setHint(Component.literal("SteamID64, profile URL or custom URL name").withColor(0xFF8A8A8A));
		input.setMaxLength(200);
		input.setValue(lastInput);
		input.setResponder(s -> lastInput = s);
		addRenderableWidget(input);
		addRenderableWidget(Button.builder(Component.literal("Fetch"), b -> fetch()).bounds(cx + 95, y, 75, 20).build());
		y += 104;
		copy = addRenderableWidget(Button.builder(Component.literal("Copy Inventory"), b -> copyInventory()).bounds(cx - 170, y, 165, 20).build());
		view = addRenderableWidget(Button.builder(Component.literal("View Profile"), b -> {
			if (profile != null) {
				ConfirmLinkScreen.confirmLinkNow(this, URI.create("https://steamcommunity.com/profiles/" + profile.id64()));
			}
		}).bounds(cx + 5, y, 165, 20).build());
		Button reset = addRenderableWidget(Button.builder(Component.literal("Reset Inventory"), b -> {
			SkinsConfig.resetOwned();
			dev.countercraft.client.loadout.LoadoutConfig.unequipUnowned();
			dev.countercraft.client.loadout.LoadoutConfig.sync();
			status = "Inventory reset: only CS2's default items are unlocked" + (SkinsConfig.skinchanger() ? " (Skinchanger Mode still unlocks everything)" : "");
		}).bounds(cx - 170, height - 52, 120, 20).build());
		reset.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Clears the items unlocked by Copy Inventory and takes off any you no longer own")));
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, height - 28, 200, 20).build());
		updateButtons();
	}

	private void updateButtons() {
		copy.active = profile != null && !unlocks.isEmpty();
		view.active = profile != null;
	}

	private void fetch() {
		String in = input.getValue().trim();
		if (in.isEmpty()) {
			return;
		}
		status = "Fetching...";
		profile = null;
		value = null;
		avatarReady = false;
		unlocks = Set.of();
		updateButtons();
		SteamGrabber.fetch(in).whenComplete((p, err) -> minecraft.execute(() -> {
			if (err != null) {
				Throwable c = err.getCause() != null ? err.getCause() : err;
				if (c instanceof SteamGrabber.PrivateProfileException) {
					// a private profile: refuse, with a popup
					status = "";
					minecraft.gui.setScreen(new net.minecraft.client.gui.screens.AlertScreen(() -> minecraft.gui.setScreen(this),
							Component.literal("Private Profile"),
							Component.literal(c.getMessage() + "'s Steam profile is private. Cannot fetch this profile.")));
					return;
				}
				status = "Couldn't fetch: " + c.getMessage();
				return;
			}
			profile = p;
			unlocks = SteamGrabber.unlocks(p.items());
			status = p.inventoryPrivate() ? "" : p.note() != null ? p.note() : p.items().size() + " items, " + unlocks.size() + " unlockable in Counter-Craft";
			updateButtons();
			loadAvatar(p.avatarUrl());
			pricing = true;
			SteamGrabber.prices().whenComplete((prices, perr) -> minecraft.execute(() -> {
				pricing = false;
				if (perr == null && profile == p) {
					value = SteamGrabber.value(p.items(), prices);
				}
			}));
		}));
	}

	private void loadAvatar(String url) {
		if (url == null) {
			return;
		}
		HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
				.thenAccept(r -> minecraft.execute(() -> {
					try {
						NativeImage img = NativeImage.read(toPng(r.body()));
						minecraft.getTextureManager().register(AVATAR, new DynamicTexture(() -> "steam avatar", img));
						avatarReady = true;
					} catch (Exception e) {
						CounterCraft.LOG.info("[countercraft] avatar not loaded: {}", e.toString());
					}
				}));
	}

	/** Steam avatars are JPEGs; Minecraft's NativeImage only reads PNG: re-encode through ImageIO. */
	private static byte[] toPng(byte[] data) throws java.io.IOException {
		if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P') {
			return data;
		}
		java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
		if (img == null) {
			throw new java.io.IOException("unreadable image");
		}
		java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
		rgb.getGraphics().drawImage(img, 0, 0, null);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(rgb, "png", out);
		return out.toByteArray();
	}

	private void copyInventory() {
		if (profile == null) {
			return;
		}
		SkinsConfig.setOwned(unlocks, profile.name(), profile.id64());
		dev.countercraft.client.loadout.LoadoutConfig.unequipUnowned();
		dev.countercraft.client.loadout.LoadoutConfig.sync(); // someone else's inventory: the Genuine Skins tick goes at once
		status = "Copied " + unlocks.size() + " items from " + profile.name() + (SkinsConfig.skinchanger() ? " (Skinchanger Mode is on: everything stays unlocked until you turn it off)"
				: SkinsConfig.foreignInventory() && dev.countercraft.client.steam.SteamLinkClient.steamId() != null
						? " (not your linked account: Genuine Skins \u2713 off until you Refresh inventory)" : "");
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xF0101214);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int cx = width / 2;
		g.centeredText(font, "SKINS OPTIONS", cx, 16, 0xFFFFFFFF);
		g.text(font, "Inventory Grabber", cx - 170, 78, 0xFFBFBFBF, true);
		int y = 116;
		if (profile != null) {
			if (avatarReady) {
				g.blit(RenderPipelines.GUI_TEXTURED, AVATAR, cx - 170, y, 0f, 0f, 64, 64, 184, 184, 184, 184);
			}
			g.text(font, profile.name(), cx - 98, y + 2, 0xFFFFFFFF, true);
			g.text(font, profile.level() >= 0 ? "Steam Level " + profile.level() : "Steam level hidden", cx - 98, y + 16, 0xFFB0C3D9, true);
			String v = value != null ? String.format("Skinport value: $%,.2f", value) : pricing ? "Skinport value: loading prices..." : "Skinport value: unavailable";
			g.text(font, v, cx - 98, y + 30, 0xFF8BC34A, true);
			g.text(font, "SteamID64 " + profile.id64(), cx - 98, y + 44, 0xFF808080, true);
			if (profile.inventoryPrivate()) {
				g.text(font, "User's Inventory is Private. Cannot fetch skins", cx - 170, y + 74, 0xFFFF4040, true);
			}
		}
		g.text(font, status, cx - 170, y + 74 + 26, 0xFFBFBFBF, true);
		String src = SkinsConfig.source().isEmpty() ? "No inventory copied" : "Unlocked from " + SkinsConfig.source() + " (" + SkinsConfig.ownedCount() + " items)";
		g.text(font, src, cx - 40, height - 46, 0xFF808080, true);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** For the title screen / pause menu button. */
	public static void open(Screen parent) {
		net.minecraft.client.Minecraft.getInstance().gui.setScreen(new SkinsOptionsScreen(parent));
	}
}
