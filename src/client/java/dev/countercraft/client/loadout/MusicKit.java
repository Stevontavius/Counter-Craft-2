package dev.countercraft.client.loadout;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.options.CcOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The equipped CS2 music kit (any of CS2's 102, picked in the loadout; CS2's default to start). Outside a world it replaces
 * Minecraft's menu music with the kit's main menu track; the team select screen plays its choose-team track; {@link #mvp}
 * plays its round MVP anthem. Which music plays at all follows Counter-Craft 2 Options (Minecraft / CS2 / both / none),
 * and kit tracks play at CS2's volume cvars (snd_musicvolume x snd_menumusic_volume / snd_mvp_volume). Tracks come from
 * the CS2 asset pack (converter --step musickits).
 */
public final class MusicKit {
	private static Music menu;
	private static Music chooseTeam;
	private static String kit = "";
	private static SoundInstance mvpPlaying;

	/** CS2's music kits (loadout/musickits.json from the CS2 asset pack): kit id -> name, in CS2's item order. */
	private static java.util.Map<String, String> kits;

	private MusicKit() {
	}

	public static java.util.Map<String, String> kits() {
		if (kits == null) {
			kits = new java.util.LinkedHashMap<>();
			try (java.io.InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("loadout/musickits.json"))) {
				for (com.google.gson.JsonElement e : com.google.gson.JsonParser.parseReader(
						new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray()) {
					com.google.gson.JsonObject o = e.getAsJsonObject();
					kits.put(o.get("kit").getAsString(), o.get("name").getAsString());
				}
			} catch (Exception e) {
				CounterCraft.LOG.warn("[countercraft] no music kit list ({}): enable the CS2 asset pack", e.toString());
				kits.put(LoadoutConfig.DEFAULT_MUSIC_KIT, "Valve, Counter-Strike 2");
			}
		}
		return kits;
	}

	public static String name(String kit) {
		return kits().getOrDefault(kit, kit);
	}

	/** After a resource reload (the pack may have changed). */
	public static void clear() {
		kits = null;
	}

	private static Music track(String name) {
		return track(LoadoutConfig.musicKit(), name);
	}

	private static Music track(String kit, String name) {
		SoundEvent e = SoundEvent.createVariableRangeEvent(CounterCraft.id("music." + kit + "." + name));
		return new Music(Holder.direct(e), 0, 0, true);
	}

	/** The credits always play CS2's default main menu track, whatever kit is equipped. */
	private static final Music CREDITS = track(LoadoutConfig.DEFAULT_MUSIC_KIT, "mainmenu");

	private static boolean credits(Minecraft mc) {
		return mc.gui.screen() instanceof net.minecraft.client.gui.screens.WinScreen;
	}

	/** The kit's track for the current screen (main menu / team select), or null when none applies. */
	private static Music kitTrack(Minecraft mc) {
		if (!kit.equals(LoadoutConfig.musicKit())) {
			kit = LoadoutConfig.musicKit();
			menu = track("mainmenu");
			chooseTeam = track("chooseteam");
		}
		if (mc.gui.screen() instanceof TeamSelectScreen) {
			return chooseTeam;
		}
		return mc.player == null ? menu : null;
	}

	/**
	 * Music for the current situation: the kit's track where it applies (if kits may play), {@code null} to leave it to
	 * Minecraft, or {@link #SILENCE} when the options allow neither.
	 */
	public static Music situational(Minecraft mc) {
		if (credits(mc)) {
			// the credits override the kit choice (and "Minecraft only"); only "No Music" silences them
			return CcOptions.musicMode() == CcOptions.MusicMode.NONE ? SILENCE : CREDITS;
		}
		Music k = CcOptions.kitMusic() ? kitTrack(mc) : null;
		if (k != null) {
			return k;
		}
		return CcOptions.minecraftMusic() && !mvpPlaying() ? null : SILENCE;
	}

	/** Stands for "no music": {@link dev.countercraft.client.mixin.MinecraftMixin} turns it into null. */
	public static final Music SILENCE = new Music(Holder.direct(SoundEvent.createVariableRangeEvent(CounterCraft.id("music.silence"))), 0, 0, true);

	/** The music gain multiplier while a kit track is the situational music (CS2's menu music volume). */
	public static float menuGain(Minecraft mc) {
		boolean kitPlays = credits(mc) ? CcOptions.musicMode() != CcOptions.MusicMode.NONE : CcOptions.kitMusic() && kitTrack(mc) != null;
		return kitPlays ? CcOptions.gain("snd_menumusic_volume") : 1f;
	}

	/**
	 * A kit track started by {@link #play} is on. A streamed track only reports active once its channel has started, a
	 * moment after it is asked to play, so it counts as on for a short while from the start.
	 */
	public static boolean mvpPlaying() {
		return mvpPlaying != null && (System.nanoTime() - startedAt < STARTUP_NANOS || Minecraft.getInstance().getSoundManager().isActive(mvpPlaying));
	}

	private static long startedAt;
	private static final long STARTUP_NANOS = 1_500_000_000L;

	/**
	 * Play the equipped kit's MVP anthem (CS2's roundmvpanthem) for this player, as CS2 does for the round MVP; other music
	 * stops while it plays. False if kits may not play.
	 */
	public static boolean mvp() {
		return play("mvp", "snd_mvp_volume");
	}

	/**
	 * Play one of the equipped kit's tracks once ("mvp", "deathcam", "lostround") at its CS2 volume cvar; other music
	 * stops while it plays. False if music kits may not play.
	 */
	public static boolean play(String track, String volumeCvar) {
		return playKit(LoadoutConfig.musicKit(), track, volumeCvar);
	}

	/** As {@link #play}, from another kit (a challenge MVP plays the achiever's kit). */
	public static boolean playKit(String kit, String track, String volumeCvar) {
		Minecraft mc = Minecraft.getInstance();
		if (!CcOptions.kitMusic()) {
			return false;
		}
		net.minecraft.resources.Identifier id = CounterCraft.id("music." + kit + "." + track);
		if (mc.getSoundManager().getSoundEvent(id) == null) {
			id = CounterCraft.id("music." + LoadoutConfig.DEFAULT_MUSIC_KIT + "." + track); // a kit this pack doesn't have
		}
		if (mc.getSoundManager().getSoundEvent(id) == null) {
			return false; // the asset pack doesn't have this track (an older conversion)
		}
		stopTrack();
		mc.getMusicManager().stopPlaying();
		mvpPlaying = new SimpleSoundInstance(id, SoundSource.MUSIC, CcOptions.gain(volumeCvar),
				1f, RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
		mc.getSoundManager().play(mvpPlaying);
		startedAt = System.nanoTime();
		return true;
	}

	/** Whether the equipped kit has this track (the asset pack converted it). */
	public static boolean has(String track) {
		return Minecraft.getInstance().getSoundManager().getSoundEvent(CounterCraft.id("music." + LoadoutConfig.musicKit() + "." + track)) != null;
	}

	/** Stop the kit track started by {@link #play} (e.g. the death music on respawn). */
	public static void stopTrack() {
		if (mvpPlaying != null) {
			Minecraft.getInstance().getSoundManager().stop(mvpPlaying);
			mvpPlaying = null;
		}
	}
}
