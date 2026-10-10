package dev.countercraft.client.options;

import dev.countercraft.client.crosshair.CrosshairScreen;
import dev.countercraft.client.loadout.LoadoutConfig;
import dev.countercraft.client.loadout.MusicKit;
import dev.countercraft.client.movement.MovementSettings;
import dev.countercraft.client.viewmodel.ViewmodelSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Counter-Craft 2 Options (title screen and pause menu): a CS2-style settings panel with a tab sidebar. Music (which
 * music plays, CS2's music volume sliders, the challenge MVP anthem), HUD &amp; Gameplay (kill feed, first-person legs) and
 * Viewmodel (CS2's viewmodel_fov / viewmodel_offset_x/y/z, live: in a world the panel moves left so the weapon shows),
 * plus Crosshairs, which opens the crosshair editor.
 */
public final class CcOptionsScreen extends Screen {
	private static final int ACCENT = 0xFFDCB45A;
	private static final int TEXT = 0xFFE8E8E8;
	private static final int DIM = 0xFF8C9096;
	private static final int ROW = 24;
	private static final String[] TABS = {"Music & Sounds", "HUD & Gameplay", "Video", "Viewmodel", "Crosshairs", "About"};
	private static final String[] TAB_TITLES = {"MUSIC & SOUNDS", "HUD & GAMEPLAY", "VIDEO", "VIEWMODEL", "CROSSHAIRS", "ABOUT"};
	private static final int VIDEO = 2;
	private static final int VIEWMODEL = 3;
	/** Opens the crosshair editor instead of a tab. */
	private static final int CROSSHAIRS = 4;
	private static final int ABOUT = 5;
	/** Remembered while the game runs. */
	private static int tab;

	private final Screen parent;
	/** Row labels of the current tab: text, y (unscrolled), colour, note (spans the row). */
	private final List<Label> labels = new ArrayList<>();
	/** Widgets of the current tab with their unscrolled y. */
	private final List<Placed> placed = new ArrayList<>();
	private int scroll;
	private int contentHeight;
	/** The warning before turning GPU Player Rendering off is up: only its two buttons take input. */
	private boolean gpuWarning;
	private Button warnCancel;
	private Button warnDisable;
	private Component status = Component.empty();

	private record Label(String text, int y, int color, boolean note, boolean value) {
		Label(String text, int y, int color, boolean note) {
			this(text, y, color, note, false);
		}
	}

	private record Placed(AbstractWidget widget, int y) {
	}

	public CcOptionsScreen(Screen parent) {
		super(Component.literal("Counter-Craft 2 Options"));
		this.parent = parent;
	}

	/** Straight to the About tab (the title screen's About button). */
	public static void openAbout(Screen parent) {
		tab = ABOUT;
		open(parent);
	}

	public static void open(Screen parent) {
		net.minecraft.client.Minecraft.getInstance().gui.setScreen(new CcOptionsScreen(parent));
	}

	// ---------------------------------------------------------------- layout

	private boolean inWorld() {
		return minecraft != null && minecraft.level != null;
	}

	private int panelW() {
		return Math.min(width - 24, 560);
	}

	/** In a world the Video and Viewmodel tabs sit on the left so the weapon stays visible on the right. */
	private boolean showsWeapon() {
		return (tab == VIDEO || tab == VIEWMODEL) && inWorld();
	}

	private int panelX() {
		return showsWeapon() ? 12 : (width - panelW()) / 2;
	}

	private int panelTop() {
		return 36;
	}

	private int panelBottom() {
		return height - 36;
	}

	private int sidebarW() {
		return 112;
	}

	private int contentX() {
		return panelX() + sidebarW() + 12;
	}

	private int contentW() {
		return panelW() - sidebarW() - 24;
	}

	/** Top of the scrolling rows (below the tab title). */
	private int rowsTop() {
		return panelTop() + 30;
	}

	private int rowsBottom() {
		return panelBottom() - 8;
	}

	private int controlW() {
		return Math.min(190, contentW() / 2);
	}

	private int controlX() {
		return contentX() + contentW() - controlW();
	}

	// ---------------------------------------------------------------- widgets

	@Override
	protected void init() {
		labels.clear();
		placed.clear();
		int y = 0;
		switch (tab) {
			case 0 -> y = music(y);
			case 1 -> y = hud(y);
			case VIDEO -> y = video(y);
			case VIEWMODEL -> y = viewmodel(y);
			case ABOUT -> y = about(y);
			default -> {
			}
		}
		contentHeight = y;
		if (gpuWarning) {
			for (Placed p : placed) {
				p.widget().active = false;
				p.widget().setTooltip(null);
			}
			int[] box = warnBox();
			int by = box[1] + box[3] - 10 - 20;
			int right = box[0] + box[2] - 12;
			warnDisable = addWidget(Button.builder(Component.literal("Disable Anyway").withColor(0xFF6A6A), b -> {
				gpuWarning = false;
				CcOptions.setGpuAgents(false);
				CcOptions.save();
				rebuildWidgets();
			}).bounds(right - 104, by, 104, 20).build());
			warnCancel = addWidget(Button.builder(Component.literal("Cancel"), b -> {
				gpuWarning = false;
				rebuildWidgets();
			}).bounds(right - 104 - 6 - 64, by, 64, 20).build());
		}
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
		layout();
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build()).active =
				!gpuWarning;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (rowsBottom() - rowsTop()));
	}

	/** Position the widgets for the scroll; hide those outside the panel. */
	private void layout() {
		for (Placed p : placed) {
			int sy = rowsTop() + p.y() - scroll;
			p.widget().setY(sy);
			p.widget().visible = sy >= rowsTop() - 2 && sy + p.widget().getHeight() <= rowsBottom() + 2;
		}
	}

	private <T extends AbstractWidget> T place(T w, int y) {
		placed.add(new Placed(w, y));
		return addRenderableWidget(w);
	}

	private int label(String text, int y) {
		labels.add(new Label(text, y, TEXT, false));
		return y;
	}

	private int note(String text, int y) {
		labels.add(new Label(text, y, DIM, true));
		return y + 14;
	}

	private int section(String text, int y) {
		labels.add(new Label(text, y + 6, ACCENT, true));
		return y + 22;
	}

	private int toggle(String text, String tip, boolean on, java.util.function.Consumer<Boolean> set, int y) {
		label(text, y + 6);
		Button b = Button.builder(onOff(on), btn -> {
			boolean now = !btn.getMessage().getString().equals("ON");
			set.accept(now);
			CcOptions.save();
			btn.setMessage(onOff(now));
		}).bounds(controlX(), 0, controlW(), 20).build();
		if (tip != null) {
			b.setTooltip(Tooltip.create(Component.literal(tip)));
		}
		place(b, y);
		return y + ROW;
	}

	private static Component onOff(boolean on) {
		return Component.literal(on ? "ON" : "OFF").withColor(on ? 0x7ED37E : 0xE07070);
	}

	private int button(String text, String buttonText, String tip, Button.OnPress press, int y) {
		label(text, y + 6);
		Button b = Button.builder(Component.literal(buttonText), press).bounds(controlX(), 0, controlW(), 20).build();
		if (tip != null) {
			b.setTooltip(Tooltip.create(Component.literal(tip)));
		}
		place(b, y);
		return y + ROW;
	}

	private int slider(String text, double min, double max, double step, double current, java.util.function.DoubleFunction<String> fmt,
			java.util.function.DoubleConsumer apply, int y) {
		label(text, y + 6);
		place(new OptionSlider(controlX(), 0, controlW(), min, max, step, current, fmt, apply), y);
		return y + ROW;
	}

	private int music(int y) {
		y = section("SOURCE", y);
		label("Music", y + 6);
		Button mode = Button.builder(Component.literal(CcOptions.musicMode().label), b -> {
			CcOptions.MusicMode[] all = CcOptions.MusicMode.values();
			CcOptions.setMusicMode(all[(CcOptions.musicMode().ordinal() + 1) % all.length]);
			CcOptions.save();
			b.setMessage(Component.literal(CcOptions.musicMode().label));
		}).bounds(controlX(), 0, controlW(), 20).build();
		mode.setTooltip(Tooltip.create(Component.literal(
				"Both: your music kit in menus, Minecraft's music in the world.\nCS2 only / Minecraft only / none: just that.")));
		place(mode, y);
		y += ROW;
		y = section("WEAPONS", y);
		boolean csgo = dev.countercraft.client.CsgoSounds.available();
		label("Weapon Sound Style", y + 6);
		Button style = Button.builder(Component.literal(CcOptions.weaponSoundStyle().button), b -> {
			CcOptions.WeaponSoundStyle[] all = CcOptions.WeaponSoundStyle.values();
			CcOptions.setWeaponSoundStyle(all[(CcOptions.weaponSoundStyle().ordinal() + 1) % all.length]);
			CcOptions.save();
			b.setMessage(Component.literal(CcOptions.weaponSoundStyle().button));
		}).bounds(controlX(), 0, controlW(), 20).build();
		style.active = csgo;
		style.setTooltip(Tooltip.create(Component.literal(dev.countercraft.client.CsgoSounds.exclusive()
				? "Your asset pack was built with CS:GO's weapon sounds only (the installer's Weapon audio: CS:GO Legacy). Rebuild it with "
						+ "Both to choose in game"
				: csgo
				? "Counter-Strike 2 or Counter-Strike: Global Offensive: whose gunshots, reloads, knives and grenades you hear. CS:GO's come "
						+ "from your own CS:GO install, added to the asset "
						+ "pack by the converter. Sounds CS:GO never had stay CS2's"
				: "CS:GO's weapon sounds aren't in your asset pack. The converter adds them from your own CS:GO install (Steam: "
						+ "Counter-Strike: Global Offensive): convert.py --step csgo")));
		place(style, y);
		y += ROW;
		if (!csgo && !dev.countercraft.client.CsgoSounds.exclusive()) {
			y = note("Not in your asset pack: hover the button for how.", y);
		}
		y = section("MUSIC KIT", y);
		y = toggle("Challenges play MVP Track", "Completing a challenge advancement plays your equipped music kit's MVP anthem instead of the challenge chime.",
				CcOptions.challengeMvp(), CcOptions::setChallengeMvp, y);
		y = button("MVP Anthem: " + MusicKit.name(LoadoutConfig.musicKit()), "▶ Preview", "Plays your music kit's MVP anthem", b -> {
			status = Component.literal(MusicKit.mvp() ? "Playing " + MusicKit.name(LoadoutConfig.musicKit()) + " MVP anthem"
					: "Music kits are off (Music: " + CcOptions.musicMode().label + ")");
		}, y);
		y += 4;
		y = section("VOLUME", y);
		for (var e : CcOptions.VOLUMES.entrySet()) {
			String cvar = e.getKey();
			y = slider(e.getValue(), 0, 1, 0.01, CcOptions.slider(CcOptions.volume(cvar)), v -> Math.round(v * 100) + "%", v -> {
				CcOptions.setVolume(cvar, CcOptions.fromSlider(v));
				CcOptions.save();
			}, y);
		}
		y = note("Round, bomb, ten-second and death camera tracks will play once rounds exist.", y + 2);
		y += 2;
		y = button("Copy volumes from CS2", "Import from CS2", "Reads the music volumes from your own CS2 settings (read only)", b -> {
			int n = CcOptions.importVolumesFromCs2();
			status = Component.literal(n < 0 ? "No CS2 config found" : "Imported " + n + " music volumes from CS2");
			rebuildWidgets();
		}, y);
		return y;
	}

	private int hud(int y) {
		y = section("HUD", y);
		y = toggle("Kill Feed / Death Notices", "CS2's death notices in the top right corner", CcOptions.killFeed(), CcOptions::setKillFeed, y);
		y = toggle("Compass", "A heading strip at the top of the screen, under the player locator bar", CcOptions.compass(), CcOptions::setCompass, y);
		label("Paperdoll", y + 6);
		Button doll = Button.builder(Component.literal(CcOptions.paperdoll().label), b -> {
			CcOptions.Paperdoll[] all = CcOptions.Paperdoll.values();
			CcOptions.setPaperdoll(all[(CcOptions.paperdoll().ordinal() + 1) % all.length]);
			CcOptions.save();
			b.setMessage(Component.literal(CcOptions.paperdoll().label));
		}).bounds(controlX(), 0, controlW(), 20).build();
		doll.setTooltip(Tooltip.create(Component.literal("A still 3D figure of you above your health in survival: your Minecraft skin or your equipped CS2 agent")));
		place(doll, y);
		y += ROW;
		y += 4;
		y = section("MOVEMENT", y);
		label("Edge Guard", y + 6);
		Button edge = Button.builder(Component.literal(MovementSettings.edgeGuard.label), b -> {
			MovementSettings.edgeGuard = MovementSettings.edgeGuard.next();
			MovementSettings.save();
			b.setMessage(Component.literal(MovementSettings.edgeGuard.label));
		}).bounds(controlX(), 0, controlW(), 20).build();
		edge.setTooltip(Tooltip.create(Component.literal(
				"CS2 movement: which keys stop you walking off a block edge, like sneaking in Minecraft.\nCrouch / Walk / Both / None")));
		place(edge, y);
		y += ROW;
		y += 4;
		y = section("FIRST PERSON", y);
		y = toggle("First Person Legs", "See your agent's legs and body when you look down", CcOptions.firstPersonLegs(), CcOptions::setFirstPersonLegs, y);
		y = button("Crosshair", "Open Crosshair Editor", null, b -> minecraft.gui.setScreen(new CrosshairScreen(this)), y);
		y = button("Viewmodel", "Open Viewmodel Settings", null, b -> setTab(VIEWMODEL), y);
		if (minecraft.player != null) {
			// your survival record in this world (also /survival)
			dev.countercraft.SurvivalRecord r = minecraft.player.getAttached(dev.countercraft.SurvivalRecord.RECORD);
			if (r == null) {
				r = dev.countercraft.SurvivalRecord.NEW;
			}
			y += 4;
			y = section("THIS WORLD", y);
			label("Days survived (since last death)", y + 2);
			labels.add(new Label(Integer.toString(r.streak()) + " days", y + 2, ACCENT, false, true));
			y += 16;
			label("Best streak", y + 2);
			labels.add(new Label(r.best() + " days", y + 2, TEXT, false, true));
			y += 16;
			label("Total days survived", y + 2);
			labels.add(new Label(r.total() + " days", y + 2, TEXT, false, true));
			y += 16;
			label("Deaths", y + 2);
			labels.add(new Label(Integer.toString(r.deaths()), y + 2, TEXT, false, true));
			y += 16;
		}
		return y;
	}

	private int viewmodel(int y) {
		y = section("VIEWMODEL (CS2 CVARS)", y);
		y = slider("Field of View  (viewmodel_fov)", 54, 68, 1, ViewmodelSettings.fov, v -> Integer.toString((int) Math.round(v)), v -> {
			ViewmodelSettings.fov = (float) v;
			ViewmodelSettings.save();
		}, y);
		y = slider("Offset X: right  (viewmodel_offset_x)", -2.5, 2.5, 0.1, ViewmodelSettings.offsetX, CcOptionsScreen::num, v -> {
			ViewmodelSettings.offsetX = (float) v;
			ViewmodelSettings.save();
		}, y);
		y = slider("Offset Y: forward  (viewmodel_offset_y)", -2, 2, 0.1, ViewmodelSettings.offsetY, CcOptionsScreen::num, v -> {
			ViewmodelSettings.offsetY = (float) v;
			ViewmodelSettings.save();
		}, y);
		y = slider("Offset Z: up  (viewmodel_offset_z)", -2, 2, 0.1, ViewmodelSettings.offsetZ, CcOptionsScreen::num, v -> {
			ViewmodelSettings.offsetZ = (float) v;
			ViewmodelSettings.save();
		}, y);
		y = toggle("Left Hand  (cl_righthand 0)", "Mirror the viewmodel into the left hand, like CS:GO (key: H)", !ViewmodelSettings.rightHand, v -> {
			ViewmodelSettings.rightHand = !v;
			ViewmodelSettings.save();
		}, y);
		y = button("Defaults", "Reset to CS2 Defaults", "viewmodel_fov 68, offsets 1 / 1 / -1", b -> {
			ViewmodelSettings.fov = 68f;
			ViewmodelSettings.offsetX = 1f;
			ViewmodelSettings.offsetY = 1f;
			ViewmodelSettings.offsetZ = -1f;
			ViewmodelSettings.rightHand = true;
			ViewmodelSettings.save();
			rebuildWidgets();
		}, y);
		y = note(inWorld() ? "Changes show live on the weapon in your hands." : "Open this in a world to see your weapon change live.", y + 4);
		return y;
	}

	/**
	 * About: the legal notices (about/about.txt) and the Q&amp;A (about/faq.txt), both plain text in the mod's assets so
	 * they can be edited (or overridden by a resource pack). "# " lines are headings; faq.txt uses Q: / A: lines.
	 */
	private int about(int y) {
		for (String line : readText("about/about.txt")) {
			y = aboutLine(line, y);
		}
		y += 4;
		y = section("Q & A", y);
		for (String line : readText("about/faq.txt")) {
			if (line.startsWith("Q:")) {
				y = wrapped(line.substring(2).trim(), 0xFFF0D898, y + 4); // not ACCENT: that colour draws a section rule
			} else if (line.startsWith("A:")) {
				y = wrapped(line.substring(2).trim(), TEXT, y);
			}
		}
		return y;
	}

	private int aboutLine(String line, int y) {
		if (line.startsWith("# ")) {
			return section(line.substring(2).trim(), y);
		}
		return line.isBlank() ? y + 6 : wrapped(line, TEXT, y);
	}

	/** A paragraph wrapped to the content width. */
	private int wrapped(String text, int color, int y) {
		for (var part : font.getSplitter().splitLines(text, contentW() - 10, net.minecraft.network.chat.Style.EMPTY)) {
			labels.add(new Label(part.getString(), y, color, true));
			y += 11;
		}
		return y + 2;
	}

	private java.util.List<String> readText(String path) {
		var res = minecraft.getResourceManager().getResource(dev.countercraft.CounterCraft.id(path));
		if (res.isEmpty()) {
			return java.util.List.of("(" + path + " is missing)");
		}
		try (var r = res.get().openAsReader()) {
			return r.lines().toList();
		} catch (java.io.IOException e) {
			return java.util.List.of("(couldn't read " + path + ")");
		}
	}

	private static String num(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private int video(int y) {
		y = section("GPU RENDERING", y);
		y = toggle("GPU Player Rendering", "Players, ragdolls and dropped guns drawn on the graphics card (much faster). Turn off only if they look wrong",
				CcOptions.gpuAgents(), v -> {
					if (v) {
						CcOptions.setGpuAgents(true);
					} else {
						gpuWarning = true; // asks first (warning below)
					}
					rebuildWidgets(); // anti-aliasing's availability
				}, y);
		if (!CcOptions.gpuAgents()) {
			((Button) placed.getLast().widget()).setMessage(Component.literal("OFF – NOT RECOMMENDED!!").withColor(0xE05050));
		}
		y = toggle("GPU Environment Rendering", "Mobs and animals drawn on the graphics card like the agents: each model goes up once, then "
				+ "only its pose each frame (much faster with many mobs). Translucent, tinted or glowing mobs draw as before. "
				+ "Restart the game to apply", CcOptions.gpuEnvironment(), v -> {
					CcOptions.setGpuEnvironment(v);
					rebuildWidgets(); // the restart note and anti-aliasing's availability
				}, y);
		if (CcOptions.gpuEnvironment() != CcOptions.gpuEnvironmentActive()) {
			y = note("Restart the game to apply GPU Environment Rendering.", y);
		}
		y = section("TEXTURES", y);
		label("Texture Quality", y + 6);
		Button tq = Button.builder(Component.literal(CcOptions.textureQuality().label), b -> {
			CcOptions.TextureQuality[] all = CcOptions.TextureQuality.values();
			CcOptions.setTextureQuality(all[(CcOptions.textureQuality().ordinal() + 1) % all.length]);
			CcOptions.save();
			dev.countercraft.client.gpu.TextureStream.reloadAll();
			b.setMessage(Component.literal(CcOptions.textureQuality().label));
		}).bounds(controlX(), 0, controlW(), 20).build();
		tq.setTooltip(Tooltip.create(Component.literal("The largest size Counter-Craft's own textures load at (guns, agents, skins, gloves). "
				+ "Lower saves video memory and loading time; Minecraft's textures aren't affected. Applies straight away")));
		place(tq, y);
		y += ROW;
		y = section("VIEWMODEL", y);
		y = toggle("Viewmodel Shaders", "CS:GO-style lighting on your first-person weapon and arms (shine, reflections, normal maps). "
				+ "Off: plain Minecraft shading; the weapon keeps its textures. Needs GPU Player Rendering (without it the viewmodel is always plain)",
				CcOptions.viewmodelShaders(), CcOptions::setViewmodelShaders, y);
		y = section("AGENTS", y);
		y = toggle("Agent Shaders", "CS:GO-style lighting on agents (players, ragdolls, the menus' previews) and the guns they hold: shine, "
				+ "reflections, roughness and normal maps. Off: plain Minecraft shading, same textures. Needs GPU Player Rendering",
				CcOptions.agentShaders(), CcOptions::setAgentShaders, y);
		y = section("SKINS", y);
		y = slider("Pearlescence", 0, 2, 0.05, CcOptions.pearlescence(), v -> Math.round(v * 100) + "%", v -> {
			CcOptions.setPearlescence((float) v);
			CcOptions.save();
		}, y);
		y = note("Printstream's sheen. 100%: CS2's own strength.", y);
		y = section("ANTI-ALIASING", y);
		String why = dev.countercraft.client.gpu.AntiAliasingPass.unavailableReason();
		label("Anti-Aliasing", y + 6);
		Button aa = Button.builder(Component.literal(why == null ? CcOptions.antiAliasing().label : "Off"), b -> {
			CcOptions.AntiAliasing[] all = CcOptions.AntiAliasing.values();
			CcOptions.setAntiAliasing(all[(CcOptions.antiAliasing().ordinal() + 1) % all.length]);
			CcOptions.save();
			b.setMessage(Component.literal(CcOptions.antiAliasing().label));
		}).bounds(controlX(), 0, controlW(), 20).build();
		aa.active = why == null;
		aa.setTooltip(Tooltip.create(Component.literal(why != null ? why
				: "FXAA: smooths edges on the finished frame (cheap, a little soft). TAA: blends jittered frames over time (smoothest, "
						+ "the viewmodel isn't included; fast motion can trail briefly)")));
		place(aa, y);
		y += ROW;
		if (why != null) {
			y = note("Unavailable: hover the button to see why.", y);
		}
		return y;
	}

	private void setTab(int t) {
		if (t == CROSSHAIRS) {
			minecraft.gui.setScreen(new CrosshairScreen(this));
			return;
		}
		tab = t;
		scroll = 0;
		status = Component.empty();
		rebuildWidgets();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int px = panelX();
		int pw = panelW();
		if (!inWorld()) {
			super.extractBackground(g, mouseX, mouseY, delta);
			g.fill(0, 0, width, height, 0xB0080A0C);
		} else if (!showsWeapon()) {
			g.fill(0, 0, width, height, 0xA0000000);
		}
		// panel, sidebar and the header strip
		g.fill(px, panelTop(), px + pw, panelBottom(), 0xE6121518);
		g.fill(px, panelTop(), px + sidebarW(), panelBottom(), 0xF00B0D10);
		g.fill(px, panelTop() - 1, px + pw, panelTop(), 0xFF2A2F36);
		g.fill(px, panelBottom(), px + pw, panelBottom() + 1, 0xFF2A2F36);
		g.fill(px, panelTop(), px + 2, panelBottom(), ACCENT);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int px = panelX();
		// title
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(px, 12);
		pose.scale(1.5f, 1.5f);
		g.text(font, "COUNTER-CRAFT 2", 0, 0, TEXT, true);
		pose.popMatrix();
		g.text(font, "OPTIONS", px + Math.round(font.width("COUNTER-CRAFT 2") * 1.5f) + 8, 16, ACCENT, true);
		// sidebar tabs
		for (int i = 0; i < TABS.length; i++) {
			int ty = panelTop() + 8 + i * 22;
			boolean hover = mouseX >= px && mouseX < px + sidebarW() && mouseY >= ty && mouseY < ty + 20;
			boolean selected = i == tab;
			if (selected) {
				g.fill(px + 2, ty, px + sidebarW(), ty + 20, 0x40DCB45A);
				g.fill(px + 2, ty, px + 4, ty + 20, ACCENT);
			} else if (hover) {
				g.fill(px + 2, ty, px + sidebarW(), ty + 20, 0x20FFFFFF);
			}
			String name = i == CROSSHAIRS ? TABS[i] + " →" : TABS[i];
			g.text(font, name, px + 12, ty + 6, selected ? 0xFFFFFFFF : hover ? TEXT : DIM, selected);
		}
		// content title
		int cx = contentX();
		g.text(font, TAB_TITLES[tab], cx, panelTop() + 10, TEXT, true);
		g.fill(cx, panelTop() + 22, cx + contentW(), panelTop() + 23, 0xFF2A2F36);
		// rows
		g.enableScissor(cx, rowsTop() - 2, cx + contentW(), rowsBottom() + 2);
		for (Label l : labels) {
			int ly = rowsTop() + l.y() - scroll;
			if (ly < rowsTop() - 10 || ly > rowsBottom()) {
				continue;
			}
			if (l.note()) {
				g.text(font, l.text(), cx, ly, l.color(), l.color() != DIM);
				if (l.color() == ACCENT) {
					g.fill(cx + font.width(l.text()) + 6, ly + 4, cx + contentW(), ly + 5, 0x30DCB45A);
				}
			} else if (l.value()) {
				g.text(font, l.text(), controlX(), ly, l.color(), true);
			} else {
				String t = font.plainSubstrByWidth(l.text(), controlX() - cx - 8);
				g.text(font, t, cx, ly, l.color(), true);
			}
		}
		g.disableScissor();
		// scroll bar
		if (maxScroll() > 0) {
			int track = rowsBottom() - rowsTop();
			int bar = Math.max(16, track * track / (track + maxScroll()));
			int by = rowsTop() + (track - bar) * scroll / maxScroll();
			int sx = panelX() + panelW() - 6;
			g.fill(sx, rowsTop(), sx + 3, rowsBottom(), 0x30FFFFFF);
			g.fill(sx, by, sx + 3, by + bar, 0xA0DCB45A);
		}
		g.centeredText(font, status, width / 2, height - 42 + 2, 0xFFBFBFBF);
		super.extractRenderState(g, mouseX, mouseY, delta);
		if (gpuWarning) {
			warning(g, mouseX, mouseY, delta);
		}
	}

	// ---------------------------------------------------------------- GPU Player Rendering warning

	private static final int WARN_W = 320;
	private static final Component WARN_TITLE = Component.literal("WARNING — GPU Player Rendering").withStyle(net.minecraft.ChatFormatting.BOLD);
	private static final Component WARN_LOSS = Component.literal("Disabling GPU rendering for players may cause ")
			.append(Component.literal("SIGNIFICANT performance loss").withStyle(net.minecraft.ChatFormatting.BOLD))
			.append(", especially when multiple Counter-Strike agents are visible.");
	private static final Component WARN_FALLBACK = Component.literal(
			"If GPU rendering fails or becomes unavailable, Counter-Craft 2 will automatically switch to CPU rendering and notify you.");

	/** The warning box {x, y, w, h}: centred, as tall as its wrapped text. */
	private int[] warnBox() {
		int w = Math.min(WARN_W, width - 24);
		int textW = w - 24;
		int h = 12 + 14 + 6 + font.split(WARN_LOSS, textW).size() * 10 + 6 + font.split(WARN_FALLBACK, textW).size() * 10 + 12 + 20 + 10;
		return new int[] {(width - w) / 2, (height - h) / 2, w, h};
	}

	private void warning(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.nextStratum();
		g.fill(0, 0, width, height, 0xA0000000);
		int[] b = warnBox();
		int x = b[0];
		int y = b[1];
		g.fill(x, y, x + b[2], y + b[3], 0xF5141619);
		g.outline(x, y, b[2], b[3], 0xFF3A3F46);
		g.fill(x, y, x + 2, y + b[3], 0xFFE05050);
		int tx = x + 12;
		int ty = y + 12;
		g.text(font, Component.literal("⚠").withStyle(net.minecraft.ChatFormatting.BOLD), tx, ty, 0xFFE05050, false);
		g.text(font, WARN_TITLE, tx + 14, ty, 0xFFFFFFFF, false);
		ty += 14 + 6;
		for (var line : font.split(WARN_LOSS, b[2] - 24)) {
			g.text(font, line, tx, ty, 0xFFD0D0D0, false);
			ty += 10;
		}
		ty += 6;
		for (var line : font.split(WARN_FALLBACK, b[2] - 24)) {
			g.text(font, line, tx, ty, 0xFFD0D0D0, false);
			ty += 10;
		}
		warnCancel.extractRenderState(g, mouseX, mouseY, delta);
		warnDisable.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (gpuWarning) {
			super.mouseClicked(e, doubleClick); // only the warning's buttons are active
			return true;
		}
		if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			int px = panelX();
			for (int i = 0; i < TABS.length; i++) {
				int ty = panelTop() + 8 + i * 22;
				if (e.x() >= px && e.x() < px + sidebarW() && e.y() >= ty && e.y() < ty + 20) {
					setTab(i);
					minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
							net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
					return true;
				}
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (gpuWarning) {
			return true;
		}
		if (maxScroll() > 0 && mx >= contentX() && mx < panelX() + panelW() && my >= rowsTop() && my < rowsBottom()) {
			scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - dy * ROW));
			layout();
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public void onClose() {
		if (gpuWarning) {
			gpuWarning = false; // Escape: the same as Cancel
			rebuildWidgets();
			return;
		}
		CcOptions.save();
		minecraft.gui.setScreen(parent);
	}
}
