package dev.countercraft.client.death;

import com.mojang.blaze3d.platform.InputConstants;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.GunKeys;
import dev.countercraft.client.hud.VitalsHud;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.net.ChancePayload;
import dev.countercraft.net.SpectatePayload;
import dev.countercraft.team.AnotherChance;
import dev.countercraft.team.Teams;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Another Chance on the client ({@link AnotherChance}): waiting for dawn, you spectate the players still alive. Left /
 * right click switch to the next / previous one (CS2's spectator controls), jump (Space) switches between their view and
 * a third person camera you turn with the mouse, X shows players through walls (Minecraft's glow, in each one's team colour), and N frees
 * the cursor to pick who to watch ({@link SpectateScreen}). At the bottom, a banner in the watched
 * player's team colour: their skin's face and name, the weapon in hand, health, armor, hunger and oxygen. At dawn the
 * cursor frees itself to choose where to respawn (after {@link AnotherChance#AUTO_RESPAWN_SECONDS} without a choice: at
 * your bed or the world spawn). If the last player alive dies: the kit's round lost music and GAME OVER.
 */
public final class Spectate {
	private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(CounterCraft.id("spectate"));
	public static final KeyMapping XRAY = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.spectate_xray", InputConstants.Type.KEYBOARD, InputConstants.KEY_X, CATEGORY));
	public static final KeyMapping CURSOR = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.spectate_cursor", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, CATEGORY));
	private static final double GAME_OVER_SECONDS = 9;
	/** The third person camera's distance from the watched player's eyes (blocks). */
	private static final double ORBIT = 3.5;

	private static boolean thirdPerson;
	private static boolean xray;
	private static SpectatePayload info;
	private static int lastState = -1;
	private static long gameOverAt = -1;
	/** When the respawn choice appeared (the server respawns you at home after AUTO_RESPAWN_SECONDS). */
	private static long readyAt = -1;
	private static int gameOverDays;

	private Spectate() {
	}

	public static void init() {
	}

	/** Dead in Counter-Craft with the run still going: spectating. */
	public static boolean locked(Minecraft mc) {
		return mc.player != null && mc.player.isSpectator() && AnotherChance.waiting(mc.player);
	}

	/** The player being watched, or null. */
	public static AbstractClientPlayer target(Minecraft mc) {
		return locked(mc) && mc.getCameraEntity() instanceof AbstractClientPlayer p && p != mc.player ? p : null;
	}

	public static SpectatePayload info() {
		return info;
	}

	public static void onInfo(SpectatePayload p) {
		info = p;
	}

	static void send(int action, java.util.UUID who) {
		ClientPlayNetworking.send(new ChancePayload(action, who));
	}

	static void toggleThirdPerson() {
		Minecraft mc = Minecraft.getInstance();
		thirdPerson = !thirdPerson;
		AbstractClientPlayer t = target(mc);
		if (thirdPerson && t != null && mc.player != null) {
			// the camera starts behind them, looking where they look
			mc.player.setYRot(t.getYRot());
			mc.player.setXRot(Math.max(0f, t.getXRot()));
			mc.player.yRotO = mc.player.getYRot();
			mc.player.xRotO = mc.player.getXRot();
		}
	}

	static void toggleXray() {
		xray = !xray;
	}

	static boolean xrayOn() {
		return xray;
	}

	static boolean thirdPersonOn() {
		return thirdPerson;
	}

	/** Start of each client tick, before the game's own key handling. */
	public static void startTick(Minecraft mc) {
		if (mc.player == null) {
			lastState = -1;
			info = null;
			thirdPerson = false;
			xray = false;
			return;
		}
		AnotherChance.State s = AnotherChance.of(mc.player);
		if (s.state() != lastState) {
			if ((lastState == AnotherChance.DOWN || lastState == AnotherChance.READY) && s.state() == AnotherChance.OUT) {
				// the last player alive died: the run is over
				gameOverAt = System.nanoTime();
				gameOverDays = s.days();
				dev.countercraft.client.loadout.MusicKit.play("lostround", "snd_roundend_volume");
			}
			readyAt = s.state() == AnotherChance.READY ? System.nanoTime() : -1;
			if (s.state() == AnotherChance.READY && (mc.gui.screen() == null || mc.gui.screen() instanceof SpectateScreen)) {
				mc.gui.setScreen(new SpectateScreen()); // dawn: the cursor is free to choose where to respawn
			}
			lastState = s.state();
		}
		if (!locked(mc)) {
			thirdPerson = false;
			xray = false;
			info = null;
			while (XRAY.consumeClick() | CURSOR.consumeClick()) {
				// not spectating: X and N do nothing (and don't pile up for later)
			}
			return;
		}
		if (mc.gui.screen() != null) {
			return;
		}
		while (CURSOR.consumeClick()) {
			mc.gui.setScreen(new SpectateScreen());
		}
		while (XRAY.consumeClick()) {
			toggleXray();
		}
		while (mc.options.keyJump.consumeClick()) {
			toggleThirdPerson();
		}
		boolean next = false;
		boolean previous = false;
		while (mc.options.keyAttack.consumeClick() | GunKeys.FIRE.consumeClick()) {
			next = true;
		}
		while (mc.options.keyUse.consumeClick() | GunKeys.ALT_FIRE.consumeClick()) {
			previous = true;
		}
		if (next) {
			send(ChancePayload.NEXT, ChancePayload.NOBODY);
		} else if (previous) {
			send(ChancePayload.PREVIOUS, ChancePayload.NOBODY);
		}
	}

	/**
	 * X-Ray: players drawn through walls in Minecraft's glow, each in their team's colour (EntityRenderer / Minecraft
	 * mixins). Spectating in Another Chance: everyone alive, toggled with X. Alive, with CC2 Options > HUD > X-Ray on: in
	 * hardcore (where everyone is on one side) every player, otherwise your own team only.
	 */
	public static boolean xray(Entity e) {
		Minecraft mc = Minecraft.getInstance();
		if (!(e instanceof Player p) || p == mc.player || p.isSpectator() || !p.isAlive() || mc.player == null) {
			return false;
		}
		if (locked(mc)) {
			return xray;
		}
		if (!dev.countercraft.client.options.CcOptions.xray() || !mc.player.isAlive() || mc.player.isSpectator()) {
			return false;
		}
		return mc.level.getLevelData().isHardcore() || dev.countercraft.team.Teams.of(p).equals(dev.countercraft.team.Teams.of(mc.player));
	}

	/** The third person camera is on (CameraMixin). */
	public static boolean orbiting() {
		return thirdPerson && target(Minecraft.getInstance()) != null;
	}

	/** Third person: around the watched player's eyes, turned with the mouse (the local player's own view angles). */
	public static DeathCam.Pose orbit(float partial) {
		Minecraft mc = Minecraft.getInstance();
		AbstractClientPlayer t = target(mc);
		float yaw = mc.player.getViewYRot(partial);
		float pitch = mc.player.getViewXRot(partial);
		Vec3 pivot = t.getEyePosition(partial);
		Vec3 wanted = pivot.subtract(Vec3.directionFromRotation(pitch, yaw).scale(ORBIT));
		HitResult hit = mc.level.clip(new ClipContext(pivot, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, t));
		Vec3 pos = wanted;
		if (hit.getType() != HitResult.Type.MISS) {
			pos = pivot.add(wanted.subtract(pivot).normalize().scale(Math.max(0.3, pivot.distanceTo(hit.getLocation()) - 0.2)));
		}
		return new DeathCam.Pose(pos, yaw, pitch, 1f);
	}

	/** HUD: GAME OVER when the run ends, the spectating banner while watching someone. */
	public static void hud(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (gameOverAt >= 0) {
			double t = (System.nanoTime() - gameOverAt) / 1e9;
			if (t < GAME_OVER_SECONDS) {
				dev.countercraft.client.hud.WinPanel.gameOver(g, w, h, t, "Your team survived " + gameOverDays + (gameOverDays == 1 ? " day" : " days"),
						(float) Math.min(1, (GAME_OVER_SECONDS - t) / 0.5));
			} else {
				gameOverAt = -1;
			}
		}
		if (!locked(mc)) {
			return;
		}
		AbstractClientPlayer t = target(mc);
		float k = CsUi.k(h);
		float bw = 660 * k;
		float bh = 76 * k;
		float x = (w - bw) / 2;
		float y = h - 30 * k - bh;
		if (!(mc.gui.screen() instanceof SpectateScreen)) {
			hints(g, mc, w, h, y - 22 * k); // (the screen lists its own keys)
		}
		if (t == null) {
			String msg = info != null && info.mates().isEmpty() ? "Nobody left alive to watch" : "Finding a player to watch...";
			CsUi.text(g, msg, w / 2f, y + bh / 2 - 9 * k, 18, h, 0xFFCCCCCC, 0);
			return;
		}
		int team = Teams.CT.equals(Teams.of(t)) ? Teams.CT_COLOR : Teams.T_COLOR;
		// the banner in the team's colour: a tinted dark card, a solid strip on top
		g.fillGradient((int) x, (int) y, (int) (x + bw), (int) (y + bh), 0x60000000 | team, 0xD0101010);
		g.fill((int) x, (int) y, (int) (x + bw), (int) (y + 3 * k), 0xFF000000 | team);
		float face = bh - 16 * k;
		PlayerFaceExtractor.extractRenderState(g, t.getSkin(), (int) (x + 8 * k), (int) (y + 10 * k), (int) face);
		float tx = x + 8 * k + face + 12 * k;
		CsUi.text(g, "SPECTATING", tx, y + 9 * k, 14, h, 0xB0FFFFFF, -1);
		CsUi.text(g, CsUi.fit(t.getGameProfile().name(), 220 * k, 26, h), tx, y + 24 * k, 26, h, 0xFF000000 | team, -1);
		CsUi.text(g, CsUi.fit(weapon(t.getMainHandItem()), 220 * k, 16, h), tx, y + 52 * k, 16, h, 0xFFCCCCCC, -1);
		// vitals, as the HUD's own: health, armor, hunger, oxygen
		float icon = 24 * k;
		float vx = x + 310 * k;
		float vy = y + (bh - icon) / 2 + 2 * k;
		vx = vital(g, Identifier.withDefaultNamespace("hud/heart/full"), null, t.getHealth() / t.getMaxHealth(), vx, vy, icon, h);
		SpectatePayload i = info != null && info.target() == t.getId() ? info : null;
		boolean kevlar = i != null && i.kevlar() > 0;
		float armor = i == null ? 0 : kevlar ? i.kevlar() / 100f : i.armor() / 20f;
		vx = vital(g, null, new ItemStack(kevlar && i.helmet() ? Items.IRON_HELMET : Items.IRON_CHESTPLATE), armor, vx, vy, icon, h);
		vx = vital(g, Identifier.withDefaultNamespace("hud/food_full"), null, i == null ? 1f : i.food() / 20f, vx, vy, icon, h);
		vital(g, Identifier.withDefaultNamespace("hud/air"), null, Math.max(0, t.getAirSupply()) / (float) t.getMaxAirSupply(), vx, vy, icon, h);
	}

	private static float vital(GuiGraphicsExtractor g, Identifier sprite, ItemStack item, float f, float x, float y, float icon, int h) {
		if (sprite != null) {
			VitalsHud.sprite(g, sprite, x, y, icon);
		} else {
			VitalsHud.item(g, item, x, y, icon, 1f);
		}
		String pct = Math.max(0, Math.round(f * 100)) + "%";
		float k = CsUi.k(h);
		CsUi.text(g, pct, x + icon + 5 * k, y + icon * 0.18f, 18, h, 0xFFFFFFFF, -1);
		return x + icon + 5 * k + Math.max(CsUi.textWidth("100%", 18, h), CsUi.textWidth(pct, 18, h)) + 16 * k;
	}

	/** The weapon in hand as the kill feed would name it ("Fists" with nothing). */
	private static String weapon(ItemStack held) {
		return held.isEmpty() ? "Fists" : held.getHoverName().getString();
	}

	/** The controls, above the banner, with the keys as bound. */
	private static void hints(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float y) {
		boolean ready = AnotherChance.of(mc.player).state() == AnotherChance.READY;
		String s = (ready ? "[" + key(CURSOR) + "] Respawn (" + autoRespawnIn() + " s)   " : "")
				+ "[" + key(mc.options.keyAttack) + " / " + key(mc.options.keyUse) + "] Switch player   "
				+ "[" + key(mc.options.keyJump) + "] " + (thirdPerson ? "First person" : "Third person") + "   "
				+ "[" + key(XRAY) + "] X-Ray " + (xray ? "off" : "on")
				+ (ready ? "" : "   [" + key(CURSOR) + "] Cursor");
		CsUi.text(g, s, w / 2f, y, 15, h, ready ? 0xFF8FD18F : 0xC0FFFFFF, 0);
	}

	/** Seconds left before the server respawns you at your bed / the world spawn. */
	static int autoRespawnIn() {
		double t = readyAt < 0 ? 0 : (System.nanoTime() - readyAt) / 1e9;
		return (int) Math.max(0, Math.ceil(AnotherChance.AUTO_RESPAWN_SECONDS - t));
	}

	static String key(KeyMapping m) {
		return m.getTranslatedKeyMessage().getString();
	}
}
