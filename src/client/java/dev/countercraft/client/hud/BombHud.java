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
 * The C4's client side (PlantedC4): CS2's bomb notices with the announcer, the equipped music kit's bomb countdown, the
 * planting / defusing progress bar, and E to defuse.
 */
public final class BombHud {
	private static final double ALERT_SECONDS = 5.0;
	private static String alert1;
	private static String alert2;
	private static long alertAt;

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
		mc.options.keyInventory.setDown(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() instanceof PlantedC4);
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
		float blast = dev.countercraft.gun.PlantedC4.damageTo(mc.player);
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

	/**
	 * Start of client tick, before vanilla's keys: holding E (the inventory key) while aiming at a planted bomb in reach
	 * defuses it (CS2's +use) instead of opening the inventory.
	 */
	public static void startTick(Minecraft mc) {
		if (mc.player == null || mc.gui.screen() != null || !mc.options.keyInventory.isDown()) {
			return;
		}
		if (mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() instanceof PlantedC4 bomb
				&& mc.player.distanceTo(bomb) <= PlantedC4.DEFUSE_REACH) {
			while (mc.options.keyInventory.consumeClick()) {
				// E defuses here
			}
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
					new dev.countercraft.net.C4ActionPayload(dev.countercraft.net.C4ActionPayload.DEFUSE, bomb.getId()));
		}
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
				// CS2's Cstrike_TitlesTXT_Bomb_Planted
				alert("The bomb has been planted", p.seconds() + " seconds till detonation");
			}
			case C4Payload.TEN_SECONDS -> MusicKit.play("bombtenseccount", "snd_tensecondwarning_volume");
			case C4Payload.DEFUSED -> {
				announce("announcer.bombdef");
				MusicKit.stopTrack();
				alert("The bomb has been defused", null); // SFUI_Notice_Bomb_Defused
			}
			case C4Payload.EXPLODED -> {
				MusicKit.stopTrack();
				if (TEST) {
					CounterCraft.LOG.info("[countercraft] selftest c4_flash_beats: {} early rises={} fades={} late on/off only={} toggles={}",
							earlyRises >= 3 && earlyFades && lateHard && lateToggles >= 4 ? "PASS" : "FAIL", earlyRises, earlyFades, lateHard, lateToggles);
				}
			}
			default -> {
			}
		}
	}

	private static void alert(String line1, String line2) {
		alert1 = line1;
		alert2 = line2;
		alertAt = System.nanoTime();
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
		int w = g.guiWidth();
		int h = g.guiHeight();
		float k = CsUi.k(h);
		double t = (System.nanoTime() - alertAt) / 1e9;
		if (alert1 != null && t < ALERT_SECONDS) {
			float a = (float) Math.min(1.0, Math.min(t / 0.15, (ALERT_SECONDS - t) / 0.5));
			int alpha = Math.round(a * 255) << 24;
			float tw = Math.max(CsUi.textWidth(alert1, 22, h), alert2 == null ? 0 : CsUi.textWidth(alert2, 16, h)) + 48 * k;
			float bh = (alert2 == null ? 40 : 62) * k;
			float y = 400 * k; // under a challenge's MVP strip (WinPanel: 286 to 376)
			g.fill((int) (w / 2f - tw / 2), (int) y, (int) (w / 2f + tw / 2), (int) (y + bh), Math.round(a * 0xB0) << 24 | 0x101214);
			CsUi.text(g, alert1, w / 2f, y + 9 * k, 22, h, alpha | 0xFFFFFF, 0);
			if (alert2 != null) {
				CsUi.text(g, alert2, w / 2f, y + 38 * k, 16, h, alpha | 0xD0D0D0, 0);
			}
		}
		if (mc.player == null || mc.level == null) {
			return;
		}
		// planting (holding use with the C4) or defusing (the bomb says it's you)
		String label = null;
		float progress = 0f;
		if (dev.countercraft.client.GunClient.plantProgress() >= 0f) {
			label = "PLANTING";
			progress = dev.countercraft.client.GunClient.plantProgress();
		} else {
			for (PlantedC4 bomb : mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(6))) {
				if (bomb.defuser() == mc.player.getId()) {
					label = "DEFUSING";
					progress = bomb.defuseProgress();
				}
			}
		}
		if (TEST && "DEFUSING".equals(label) && progress > 0.5f && !defuseShot) {
			defuseShot = true;
			shot(mc, "cc2-c4-defusing-" + (++defuses) + ".png");
		} else if (TEST && !"DEFUSING".equals(label)) {
			defuseShot = false;
		}
		if (label != null) {
			float bw = 300 * k;
			float x = w / 2f - bw / 2;
			float y = h * 0.62f;
			CsUi.text(g, label, w / 2f, y - 20 * k, 16, h, 0xFFFFFFFF, 0);
			g.fill((int) x, (int) y, (int) (x + bw), (int) (y + 8 * k), 0xA0000000);
			g.fill((int) x, (int) y, (int) (x + bw * Math.min(1f, progress)), (int) (y + 8 * k), 0xFFFFFFFF);
		}
	}
}
