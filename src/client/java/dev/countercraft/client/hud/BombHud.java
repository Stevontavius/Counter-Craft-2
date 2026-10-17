package dev.countercraft.client.hud;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.loadout.MusicKit;
import dev.countercraft.gun.PlantedC4;
import dev.countercraft.net.C4Payload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/**
 * The C4's client side (PlantedC4): CS2's bomb notices with the announcer, the equipped music kit's bomb countdown, CS2's
 * defuse progress bar ({@link DefuseBar}), and E to defuse.
 */
public final class BombHud {

	private BombHud() {
	}

	/** Dev (-Dcountercraft.grenadeTest=true): log the news, screenshot the planted alert and the defuse bar. */
	private static final boolean TEST = Boolean.getBoolean("countercraft.grenadeTest");
	private static int shotIn = -1;
	private static boolean defuseShot;
	private static int defuses;

	private static int heldC4;
	private static float lastFlash;
	private static int earlyRises;
	private static boolean earlyFades;
	private static boolean lateHard = true;
	private static int lateToggles;
	private static int hudTicks;
	private static int debugTicks;
	private static boolean hudLethal;
	private static net.minecraft.world.phys.Vec3 plantFrom;
	private static boolean rootLogged;
	private static int thirdShots;
	private static float thirdAt;
	private static boolean plantShot;
	private static boolean struckShot;
	private static boolean waveShot;
	private static boolean fireShot;
	private static boolean smokeShot;

	public static void tick(Minecraft mc) {
		if (!TEST || mc.player == null) {
			return;
		}
		if (shotIn >= 0 && --shotIn == 0) {
			shot(mc, "cc2-c4-planted.png");
		}
		// the test's input: mouse 1 once the C4's draw is over, E while aiming at a planted bomb
		boolean c4 = dev.countercraft.team.CsInventory.active(mc.player) && mc.player.getMainHandItem().getItem() instanceof dev.countercraft.gun.C4Item;
		heldC4 = c4 ? heldC4 + 1 : 0;
		dev.countercraft.client.GunKeys.FIRE.setDown(c4 && heldC4 > 35);
		// (held on while the defuse is locked in: a mob in front doesn't let go of it)
		mc.options.keyInventory.setDown(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() instanceof PlantedC4
				|| locked != -1);
		if (invCheckIn > 0 && --invCheckIn == 0) {
			boolean open = mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
			CounterCraft.LOG.info("[countercraft] selftest c4_defuse_no_inventory: {} screen={}", open ? "FAIL" : "PASS",
					mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
			if (open) {
				mc.gui.setScreen(null);
			}
		}
		float plant = dev.countercraft.client.GunClient.plantProgress();
		// planting holds you still and crouched: walk forward through it and see
		mc.options.keyUp.setDown(plant > 0.2f && plant < 0.9f);
		if (plant > 0.2f && plantFrom == null) {
			plantFrom = mc.player.position();
		} else if (plant > 0.85f && plantFrom != null && !rootLogged) {
			rootLogged = true;
			CounterCraft.LOG.info("[countercraft] selftest c4_rooted_crouched: {} moved={} duck={}",
					mc.player.position().distanceTo(plantFrom) < 0.05 && dev.countercraft.client.movement.CsPlayerMovement.duck(1f) > 0.9f ? "PASS" : "FAIL",
					String.format("%.3f", mc.player.position().distanceTo(plantFrom)), dev.countercraft.client.movement.CsPlayerMovement.duck(1f));
		}
		// the warning's flash keeps time with the beeps: sampled every tick
		PlantedC4 soonest = null;
		for (PlantedC4 bomb : mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(PlantedC4.BLAST_RADIUS))) {
			soonest = soonest == null || bomb.fuseAtBeep() < soonest.fuseAtBeep() ? bomb : soonest;
		}
		if (soonest != null) {
			float f = soonest.warningFlash();
			if (soonest.fuseAtBeep() <= PlantedC4.HARD_FLASH_TICKS) {
				lateHard &= f == 0f || f == 1f;
				lateToggles += f != lastFlash ? 1 : 0;
			} else {
				earlyRises += f > lastFlash + 0.5f ? 1 : 0;
				earlyFades |= f > 0.2f && f < 0.9f;
			}
			lastFlash = f;
		}
		// the health HUD's warning: once lethal, once not
		var preview = mc.player.getAttached(dev.countercraft.gun.PlantedC4.PREVIEW);
		float blast = preview != null ? preview.damage() : 0f;
		boolean lethal = blast > 0 && blast >= mc.player.getHealth() + mc.player.getAbsorptionAmount();
		hudTicks = blast > 0 && lethal == hudLethal ? hudTicks + 1 : 0;
		hudLethal = lethal;
		if (hudTicks == 30 && blast > 0 && mc.gameMode != null && mc.gameMode.canHurtPlayer()) {
			shot(mc, lethal ? "cc2-c4-hud-lethal.png" : "cc2-c4-hud-damage.png");
		}
		debugTicks = dev.countercraft.client.BlastDebug.on() && soonest != null ? debugTicks + 1 : 0;
		if (debugTicks == 40) {
			shot(mc, "cc2-c4-debug.png");
		}
		// the wave spreading through the world, a moment after it went off
		var waves = dev.countercraft.client.fx.ShockwaveFx.waves();
		if (!waves.isEmpty() && waves.getFirst().front() > 9 && !fireShot) {
			fireShot = true;
			shot(mc, "cc2-c4-fireball.png");
		}
		if (!waves.isEmpty() && waves.getFirst().front() > 22 && !waveShot) {
			waveShot = true;
			shot(mc, "cc2-c4-wave.png");
		}
		if (!waves.isEmpty() && waves.getFirst().front() > 72 && !smokeShot) {
			smokeShot = true;
			shot(mc, "cc2-c4-smoke.png");
		}
		// struck by it: the shake and blur a moment in, and that it ran
		if (dev.countercraft.client.fx.ShockwaveFx.hitTime() > 0.12 && !struckShot) {
			struckShot = true;
			shot(mc, "cc2-c4-struck.png");
			CounterCraft.LOG.info("[countercraft] selftest c4_struck_shader: {} strength={}",
					dev.countercraft.client.gpu.ShockwavePass.failed() ? "FAIL" : "PASS", dev.countercraft.client.fx.ShockwaveFx.strength());
		}
		if (plant > 0.5f && !plantShot) {
			plantShot = true;
			shot(mc, "cc2-c4-planting.png");
		}
		// third person (from the front) late in the plant and the defuse: the agent's poses
		float defuse = 0f;
		for (PlantedC4 bomb : mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(6))) {
			defuse = bomb.defuser() == mc.player.getId() ? bomb.defuseProgress() : defuse;
		}
		float p = thirdShots == 0 ? plant : defuse; // one of the plant, one of the defuse
		var cam = thirdShots == 0 ? net.minecraft.client.CameraType.THIRD_PERSON_FRONT : net.minecraft.client.CameraType.THIRD_PERSON_BACK;
		if (p > 0.6f && p < 0.8f && mc.options.getCameraType() != cam && thirdShots < 2) {
			mc.options.setCameraType(cam);
			thirdAt = p;
		} else if (mc.options.getCameraType() == cam && p > thirdAt + 0.06f) {
			shot(mc, "cc2-c4-third-" + (++thirdShots) + ".png");
			mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
		}
	}

	private static void shot(Minecraft mc, String name) {
		net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
				msg -> CounterCraft.LOG.info("[countercraft] c4 test: {}", msg.getString()));
	}

	/** The bomb a defuse is locked onto (its entity id; -1: none), and whether E has started a defuse since it went down. */
	private static int locked = -1;
	private static boolean attempted;
	/** Dev test: ticks until checking that letting go of a defuse didn't open the inventory. */
	private static int invCheckIn;
	/** Ticks E's clicks are still swallowed after letting go of a defuse (so letting go never opens the inventory). */
	private static int swallow;
	/** How far off the bomb you can look (degrees) before a locked defuse lets go. */
	private static final double LOCK_ANGLE = 40;

	/**
	 * Start of client tick, before vanilla's keys: E (the inventory key) defuses (CS2's +use) instead of opening the
	 * inventory. Holding it while aiming at a planted bomb in reach starts a defuse, and locks it in: it carries on
	 * while E stays down, whatever walks in front (a mob between you and the bomb), until you let go of E or look more
	 * than {@link #LOCK_ANGLE} off the bomb (or go out of reach). From that first press until a few ticks after you let
	 * go, E's clicks are swallowed, so the inventory never opens over a defuse.
	 */
	public static void startTick(Minecraft mc) {
		boolean held = mc.player != null && mc.gui.screen() == null && mc.options.keyInventory.isDown();
		if (!held) {
			if (attempted) {
				swallow = 4;
			}
			attempted = false;
			locked = -1;
			if (swallow > 0) {
				swallow--;
				while (mc.options.keyInventory.consumeClick()) {
					// letting go of a defuse: no inventory
				}
			}
			return;
		}
		PlantedC4 bomb = null;
		if (locked != -1 && mc.level.getEntity(locked) instanceof PlantedC4 b && inView(mc, b)) {
			bomb = b; // locked in: whatever is in front of it
		} else if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() instanceof PlantedC4 b
				&& mc.player.distanceTo(b) <= PlantedC4.DEFUSE_REACH) {
			bomb = b; // a new defuse
		}
		locked = bomb == null ? -1 : bomb.getId();
		attempted |= bomb != null;
		if (attempted) {
			while (mc.options.keyInventory.consumeClick()) {
				// E defuses here
			}
		}
		if (bomb != null) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.countercraft.net.C4ActionPayload(dev.countercraft.net.C4ActionPayload.DEFUSE, bomb.getId()));
		}
	}

	/** The bomb is still where the defuser looks (within {@link #LOCK_ANGLE}) and in reach (the server's margin). */
	private static boolean inView(Minecraft mc, PlantedC4 bomb) {
		var eye = mc.player.getEyePosition();
		var to = bomb.getBoundingBox().getCenter().subtract(eye);
		if (mc.player.distanceTo(bomb) > PlantedC4.DEFUSE_REACH + 0.5 || to.lengthSqr() < 1e-6) {
			return to.lengthSqr() < 1e-6;
		}
		double cos = mc.player.getViewVector(1f).dot(to.normalize());
		return cos >= Math.cos(Math.toRadians(LOCK_ANGLE));
	}

	/** News from the server. */
	public static void on(C4Payload p) {
		if (TEST) {
			CounterCraft.LOG.info("[countercraft] selftest-c4 client event {}", p.event());
			if (p.event() == C4Payload.PLANTED && shotIn < 0 && !defuseShot) {
				shotIn = 25;
			}
		}
		switch (p.event()) {
			case C4Payload.PLANTED -> {
				announce("announcer.bombpl");
				MusicKit.play("bombplanted", "snd_mapobjective_volume");
				// CS2's Cstrike_TitlesTXT_Bomb_Planted, who planted it (the top left), the bomb at the top
				BombNotices.planted(p.planter(), p.team());
				BombNotices.alertLine(p.seconds() + " seconds till detonation");
			}
			case C4Payload.TEN_SECONDS -> MusicKit.play("bombtenseccount", "snd_tensecondwarning_volume");
			case C4Payload.DEFUSED -> {
				announce("announcer.bombdef");
				if (TEST && invCheckIn == 0) {
					invCheckIn = 12; // E let go after the defuse: no inventory
				}
				DefuseBar.defused();
				MusicKit.stopTrack();
				BombNotices.alert("The bomb has been defused", null, 0xB5D4EE); // SFUI_Notice_Bomb_Defused
				BombNotices.gone();
			}
			case C4Payload.EXPLODED -> {
				MusicKit.stopTrack();
				BombNotices.gone();
				if (TEST) {
					CounterCraft.LOG.info("[countercraft] selftest c4_flash_beats: {} early rises={} fades={} late on/off only={} toggles={}",
							earlyRises >= 3 && earlyFades && lateHard && lateToggles >= 4 ? "PASS" : "FAIL", earlyRises, earlyFades, lateHard, lateToggles);
				}
			}
			default -> {
			}
		}
	}

	/** The announcer's line (a UI sound: everyone hears it alike). */
	private static void announce(String id) {
		Minecraft mc = Minecraft.getInstance();
		var rid = CounterCraft.id(id);
		if (mc.getSoundManager().getSoundEvent(rid) != null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(rid), 1f, 1f));
		}
	}

	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		BombNotices.draw(g, mc);
		if (mc.player == null || mc.level == null) {
			return;
		}
		// defusing: CS2's progress bar (planting has none in CS2: the C4's own screen shows it)
		DefuseBar.draw(g, mc);
		if (TEST) {
			float progress = 0f;
			for (PlantedC4 bomb : mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(6))) {
				progress = bomb.defuser() == mc.player.getId() ? bomb.defuseProgress() : progress;
			}
			// three stages of it (red, yellow, green), as CS2's
			float[] at = {0.08f, 0.5f, 0.88f};
			if (defuses < at.length && progress > at[defuses]) {
				shot(mc, "cc2-c4-defusing-" + (++defuses) + ".png");
			}
		}
	}
}
