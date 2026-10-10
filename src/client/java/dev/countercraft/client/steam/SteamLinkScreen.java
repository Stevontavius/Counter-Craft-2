package dev.countercraft.client.steam;

import dev.countercraft.CounterCraft;
import java.net.URI;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Steam linking, said plainly before anything happens: what signing in means, what CC2 gets, what it reads with it,
 * what it never asks for, and how to undo it. Optional: nothing in CC2 needs it.
 */
public final class SteamLinkScreen extends Screen {
	private final Screen parent;

	private static final List<String> WHAT = List.of(
			"You sign in on steamcommunity.com, in your own web browser, with Steam's",
			"official \"Sign in through Steam\". Minecraft shows you the exact address first.",
			"",
			"CC2 never asks for, sees or stores your password, Steam Guard codes, trade",
			"confirmations or API keys. Anything that asks you for those is a scam.",
			"",
			"What CC2 gets: your SteamID (public). With it, it reads only public data:",
			"  - your Steam profile (name, avatar, level)",
			"  - your public CS2 inventory, to unlock your own skins in Counter-Craft",
			"  - your public Leetify profile, for My CS2 Stats",
			"",
			"Genuine Skins ✓: next to your name while your Steam account is linked and you play",
			"with your own inventory. Skinchanger Mode or a copied inventory turns it off. It's",
			"provenance, not a rank, and nobody without it is marked. Each server checks Steam itself.",
			"",
			"Unlink any time: CC2 then reads nothing from your account.");

	public SteamLinkScreen(Screen parent) {
		super(Component.literal("Steam Account"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height - 60;
		boolean linked = SteamLinkClient.steamId() != null || !SteamLinkClient.serverSteamId().isEmpty();
		addRenderableWidget(Button.builder(Component.literal(linked ? "Sign in again" : "Sign in through Steam"), b -> signIn())
				.bounds(cx - 204, y, 132, 20).build());
		Button unlink = Button.builder(Component.literal("Unlink"), b -> {
			SteamLinkClient.unlink();
			rebuildWidgets();
		}).bounds(cx - 66, y, 132, 20).build();
		unlink.active = linked;
		addRenderableWidget(unlink);
		addRenderableWidget(Button.builder(Component.literal("My CS2 Stats"), b -> minecraft.gui.setScreen(new CsStatsScreen(this)))
				.bounds(cx + 72, y, 132, 20).build());
		Button stats = Button.builder(Component.literal("Refresh stats"), b -> {
			CsStatsScreen.refresh();
			SteamProfileCard.refresh();
			SteamLinkClient.setMessage("Your stats load again from Steam and Leetify.");
		}).bounds(cx - 204, y + 26, 132, 20).build();
		stats.active = linked;
		addRenderableWidget(stats);
		Button inv = Button.builder(Component.literal("Refresh inventory"), b -> SteamLinkClient.refreshInventory()).bounds(cx + 72, y + 26, 132, 20).build();
		inv.active = linked;
		inv.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
				"Resets your copied inventory and copies your Steam inventory again (skins sold or bought since)."
						+ (SteamLinkClient.inWorld() ? " This world reads it again for the Genuine Skins tick too." : ""))));
		addRenderableWidget(inv);
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 66, y + 26, 132, 20).build());
	}

	private void signIn() {
		try {
			URI uri = SteamLinkClient.begin();
			// Minecraft's own "open this link?" screen shows the full Steam address before the browser opens
			ConfirmLinkScreen.confirmLinkNow(this, uri);
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] steam sign-in couldn't start: {}", e.toString());
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xF0101214);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int cx = width / 2;
		int y = 16;
		g.centeredText(font, Component.literal("STEAM ACCOUNT (OPTIONAL)"), cx, y, 0xFFFFFFFF);
		y += 18;
		for (String line : WHAT) {
			int color = line.startsWith("CC2 never") || line.startsWith("confirmations") ? 0xFF7ED37E : 0xFFBFBFBF;
			g.text(font, line, cx - 200, y, color, false);
			y += 11;
		}
		y += 8;
		String id = SteamLinkClient.steamId();
		String server = SteamLinkClient.serverSteamId();
		String state = id == null && server.isEmpty() ? "Not linked." : "Linked: SteamID " + (server.isEmpty() ? id : server)
				+ (SteamLinkClient.inWorld() ? server.isEmpty() ? " (not yet on this server: sign in again here)" : " (verified by this server)" : "");
		g.text(font, state, cx - 200, y, 0xFFFFFFFF, false);
		y += 12;
		if ("PRIVATE".equals(SteamLinkClient.serverInventory())) {
			g.text(font, "Your CS2 inventory is private: make it public on Steam for the Genuine Skins tick.", cx - 200, y, 0xFFE0B050, false);
			y += 12;
		}
		if (!SteamLinkClient.message().isEmpty()) {
			g.text(font, SteamLinkClient.message(), cx - 200, y, 0xFF8FC8FF, false);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}
}
