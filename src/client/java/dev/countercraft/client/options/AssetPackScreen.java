package dev.countercraft.client.options;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * The CS2 asset pack (weapons, agents, sounds, HUD art, built from your own CS2 install by the converter) is required:
 * while it isn't loaded this stands in front of the title screen, and only loading it (or quitting) gets past it (Esc
 * doesn't). Installed but off: "Load CS2 Assets" turns it on (Minecraft's own resource pack selection, saved to
 * options.txt, then a resource reload). Not installed or out of date: how to get it, the resource packs folder, Check
 * Again. In a world (the pack turned off from the pause menu's options), the pause screen's "Load CS2 Assets" opens it.
 */
public final class AssetPackScreen extends Screen {
	/** The converter's pack (cs2-pack/CounterCraft-CS2-Assets). */
	public static final String PACK = "CounterCraft-CS2-Assets";
	/** A file only the pack has: its agent list. */
	private static final Identifier PROBE = CounterCraft.id("agents/agents.json");
	private static final int ACCENT = 0xFFDCB45A;
	private static final int TEXT = 0xFFE8E8E8;
	private static final int DIM = 0xFF9A9A9A;
	private static final int W = 340;

	private final Screen parent;
	private Pack pack;
	private boolean loading;

	public AssetPackScreen(Screen parent) {
		super(Component.literal("CS2 Assets"));
		this.parent = parent;
	}

	/** The pack's files are in the game's resources now. */
	public static boolean loaded() {
		return Minecraft.getInstance().getResourceManager().getResource(PROBE).isPresent();
	}

	/** The title screen opened: without the pack, this takes its place until the pack is loaded. */
	public static void require(Minecraft mc, Screen title) {
		if (!loaded()) {
			mc.execute(() -> mc.gui.setScreen(new AssetPackScreen(title)));
		}
	}

	/** The pack among the resource packs Minecraft found: by its folder name, else any pack that has its files. */
	private static Pack find(PackRepository repo) {
		for (Pack p : repo.getAvailablePacks()) {
			if (p.getId().contains(PACK)) {
				return p;
			}
		}
		for (Pack p : repo.getAvailablePacks()) {
			if (p.getId().startsWith("file/") && hasFiles(p)) {
				return p; // renamed
			}
		}
		return null;
	}

	private static boolean hasFiles(Pack p) {
		try (Stream<PackResources> all = p.open()) {
			return all.anyMatch(r -> {
				try (r) {
					return r.getResource(PackType.CLIENT_RESOURCES, PROBE) != null;
				} catch (Exception e) {
					return false;
				}
			});
		} catch (Exception e) {
			return false;
		}
	}

	private boolean selected() {
		return pack != null && minecraft.getResourcePackRepository().getSelectedIds().contains(pack.getId());
	}

	@Override
	protected void init() {
		PackRepository repo = minecraft.getResourcePackRepository();
		pack = find(repo);
		int x = (width - 200) / 2;
		int y = height / 2 + 6;
		if (pack != null && !selected()) {
			addRenderableWidget(Button.builder(Component.literal("Load CS2 Assets").withColor(ACCENT & 0xFFFFFF), b -> load()).bounds(x, y, 200, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal("Check Again"), b -> {
				repo.reload(); // a pack just copied in (or rebuilt)
				minecraft.reloadResourcePacks();
				rebuildWidgets();
			}).bounds(x, y, 200, 20).build());
		}
		y += 24;
		addRenderableWidget(Button.builder(Component.literal("Open Pack Folder"),
				b -> com.mojang.blaze3d.Blaze3D.openPath(minecraft.getResourcePackDirectory())).bounds(x, y, 98, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("options.resourcepack"), b -> minecraft.gui.setScreen(new PackSelectionScreen(repo, r -> {
			minecraft.options.updateResourcePacks(r);
			minecraft.gui.setScreen(this);
		}, minecraft.getResourcePackDirectory(), Component.translatable("resourcePack.title")))).bounds(x + 102, y, 98, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("menu.quit"), b -> minecraft.stop()).bounds(x, y, 200, 20).build());
	}

	/** Turn the pack on, as Minecraft's resource pack screen would: saved to options.txt, then a resource reload. */
	private void load() {
		PackRepository repo = minecraft.getResourcePackRepository();
		repo.reload();
		pack = find(repo);
		if (pack == null) {
			rebuildWidgets();
			return;
		}
		loading = true;
		repo.addPack(pack.getId());
		minecraft.options.updateResourcePacks(repo); // saves, and reloads the resources (the loading overlay)
	}

	@Override
	public void tick() {
		if (loaded()) {
			minecraft.gui.setScreen(parent); // loaded: on to the title screen
		}
	}

	/** Required: Esc doesn't close it. */
	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xF20E1012);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int x = (width - W) / 2;
		int top = height / 2 - 92;
		int bottom = height / 2 + 6 + 72 + 10;
		CsUi.panel(g, x, top, W, bottom - top);
		g.fill(x, top, x + W, top + 2, ACCENT);
		String title;
		String[] lines;
		if (loading) {
			title = "LOADING CS2 ASSETS";
			lines = new String[] {"Minecraft is reloading its resources with the asset pack.", "", "This takes a moment for a pack this size."};
		} else if (pack == null) {
			title = "CS2 ASSETS REQUIRED";
			lines = new String[] {"Counter-Craft 2 needs its CS2 asset pack,", PACK + " (CS2's weapons, agents, sounds",
					"and HUD art), and it isn't in your resource packs folder.", "Build it from your own CS2 install with the converter",
					"(converter/convert.py), put it in the folder, Check Again."};
		} else if (selected()) {
			title = "CS2 ASSETS OUT OF DATE";
			lines = new String[] {"The " + PACK + " pack is on, but it doesn't have", "Counter-Craft 2's files. Rebuild it with the converter",
					"(converter/convert.py), then Check Again."};
		} else {
			title = "CS2 ASSETS REQUIRED";
			lines = new String[] {"Counter-Craft 2 needs its CS2 asset pack,", PACK + " (CS2's weapons, agents, sounds",
					"and HUD art). It's installed but turned off:", "load it to play."};
		}
		g.centeredText(font, Component.literal(title), width / 2, top + 12, ACCENT);
		int ly = top + 30;
		for (String l : lines) {
			g.centeredText(font, Component.literal(l), width / 2, ly, l.isEmpty() ? DIM : TEXT);
			ly += 11;
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
