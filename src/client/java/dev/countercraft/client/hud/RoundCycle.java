package dev.countercraft.client.hud;

import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.loadout.MusicKit;
import dev.countercraft.net.DaySurvivedPayload;
import dev.countercraft.team.DayCycle;
import dev.countercraft.team.Teams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Counter-Craft's day as a CS2 round, on the client ({@link DayCycle} has the rules): the equipped music kit's round
 * music and the ROUND WON banner. At dawn, if you survived, CS2's won round banner and track play out first; then the
 * buy phase loops the round's round start take until it ends, and its matching round action take plays once (one take
 * per day, picked at random among the kit's, as CS2 picks one per round); the last 40 s of the night play the bomb
 * planted track, then its 10 second warning. Volumes are CS2's per-track cvars. Only in hardcore worlds, only while
 * alive (the death cam has its own music) and never during the first team pick (its own music has the stage).
 */
public final class RoundCycle {
	private enum Phase { BUY, ACTION, BOMB, WARNING }

	/** How long the won round banner stays (CS2 shows it until the next round starts), and its fade out. */
	private static final double BANNER_SECONDS = 6.0;
	private static final double BANNER_FADE = 0.6;

	private static Phase phase;
	private static int bannerDay;
	private static int bannerReward;
	private static long bannerAt;

	private RoundCycle() {
	}

	private static boolean on(Minecraft mc) {
		return mc.level != null && mc.player != null && mc.level.getLevelData().isHardcore();
	}

	private static long timeOfDay(Minecraft mc) {
		return Math.floorMod(mc.level.getOverworldClockTime(), DayCycle.DAY);
	}

	/** In the buy phase now (always true outside Counter-Craft, where buying is free). */
	public static boolean buyPhase() {
		Minecraft mc = Minecraft.getInstance();
		return !on(mc) || timeOfDay(mc) < DayCycle.BUY_TICKS;
	}

	/** Seconds until the next buy phase starts (the next dawn). */
	public static int secondsToBuyPhase() {
		Minecraft mc = Minecraft.getInstance();
		return on(mc) ? (int) Math.ceil((DayCycle.DAY - timeOfDay(mc)) / 20.0) : 0;
	}

	/** The round's music take (round start and round action alike, e.g. "_02"), and the day it was picked for. */
	private static String take = "";
	private static long takeDay = Long.MIN_VALUE;
	/** The won round track is playing (the round start music waits for it and the banner). */
	private static boolean wonPlaying;
	/** Don't start the buy phase music in the first moments of the day: the won round news may still be on its way. */
	private static final long WON_GRACE_TICKS = 40;

	public static void onDaySurvived(DaySurvivedPayload p) {
		bannerDay = p.day();
		bannerReward = p.reward();
		bannerAt = System.nanoTime();
		wonPlaying = MusicKit.play("wonround", "snd_roundend_volume");
	}

	/**
	 * One take per round, like CS2: the kit's round start takes are numbered (startround_01..03) and each has its round
	 * action (startaction_01..03). Picked once per day and kept for the whole round, loops included.
	 */
	private static String take(Minecraft mc) {
		long day = Math.floorDiv(mc.level.getOverworldClockTime(), DayCycle.DAY);
		if (day != takeDay) {
			takeDay = day;
			java.util.List<String> takes = new java.util.ArrayList<>();
			for (int i = 1; i <= 9; i++) {
				String t = String.format("_%02d", i);
				if (MusicKit.has("startround" + t)) {
					takes.add(t);
				}
			}
			take = takes.isEmpty() ? "" : takes.get(mc.level.getRandom().nextInt(takes.size()));
		}
		return take;
	}

	private static void playRoundStart(Minecraft mc) {
		MusicKit.play("startround" + take(mc), "snd_roundstart_volume");
	}

	private static void playRoundAction(Minecraft mc) {
		String t = take(mc);
		MusicKit.play(MusicKit.has("startaction" + t) ? "startaction" + t : "startaction", "snd_roundaction_volume");
	}

	/** The won round presentation (banner and track) is still on: the round start music waits. */
	private static boolean wonSequence() {
		if (wonPlaying && !MusicKit.mvpPlaying()) {
			wonPlaying = false;
		}
		return wonPlaying || bannerShowing();
	}

	/** The first team pick of a Counter-Craft world: its own music only. */
	private static boolean pickingTeam(Minecraft mc) {
		return mc.gui.screen() instanceof dev.countercraft.client.loadout.TeamSelectScreen || !mc.player.hasAttached(Teams.TEAM);
	}

	/** Every client tick: the music follows the time of day. */
	public static void tick(Minecraft mc) {
		if (!on(mc) || mc.player.isDeadOrDying() || mc.player.isSpectator() || dev.countercraft.client.death.DeathCam.active()) {
			phase = null;
			return;
		}
		if (pickingTeam(mc)) {
			if (phase != null) {
				MusicKit.stopTrack(); // the team select music has the stage
			}
			phase = null;
			return;
		}
		long t = timeOfDay(mc);
		long toDawn = DayCycle.DAY - t;
		Phase now = t < DayCycle.BUY_TICKS ? Phase.BUY : toDawn <= DayCycle.WARNING_TICKS ? Phase.WARNING
				: toDawn <= DayCycle.BOMB_TICKS ? Phase.BOMB : Phase.ACTION;
		Phase was = phase;
		phase = now;
		switch (now) {
			case BUY -> {
				// the round start take loops for the rest of the buy phase, once the won round presentation is over
				if (t >= WON_GRACE_TICKS && !wonSequence() && !MusicKit.mvpPlaying()) {
					playRoundStart(mc);
				}
			}
			case ACTION -> {
				if (was == Phase.BUY) {
					playRoundAction(mc); // the buy phase is over: go
				}
			}
			case BOMB -> {
				if (was != Phase.BOMB) {
					MusicKit.play("bombplanted", "snd_mapobjective_volume");
				}
			}
			case WARNING -> {
				if (was != Phase.WARNING) {
					MusicKit.play("bombtenseccount", "snd_tensecondwarning_volume");
				}
			}
		}
	}

	private static boolean bannerShowing() {
		return bannerAt != 0 && (System.nanoTime() - bannerAt) / 1e9 < BANNER_SECONDS;
	}

	/** The won round banner: DAY N SURVIVED, the reward under it, in your team's colour. */
	public static void renderBanner(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (bannerAt == 0 || mc.player == null) {
			return;
		}
		double t = (System.nanoTime() - bannerAt) / 1e9;
		if (t > BANNER_SECONDS) {
			bannerAt = 0;
			return;
		}
		float alpha = (float) Math.min(1.0, (BANNER_SECONDS - t) / BANNER_FADE);
		boolean ct = Teams.CT.equals(Teams.of(mc.player));
		int accent = ct ? CsUi.CT_COLOR : CsUi.T_COLOR;
		int fill = ct ? 0xE0020A1E : 0xE01E1602;
		WinPanel.draw(g, g.guiWidth(), g.guiHeight(), t, "DAY " + bannerDay + " SURVIVED", "+$" + bannerReward + " for surviving the day", accent, fill, alpha);
	}

	/** Above the money: BUY PHASE (green) or the countdown to the next one. Null outside Counter-Craft. */
	public static String phaseText() {
		Minecraft mc = Minecraft.getInstance();
		if (!on(mc)) {
			return null;
		}
		if (buyPhase()) {
			return "BUY PHASE";
		}
		int s = secondsToBuyPhase();
		return String.format("Next Buy Phase: %02d:%02d", s / 60, s % 60);
	}
}
