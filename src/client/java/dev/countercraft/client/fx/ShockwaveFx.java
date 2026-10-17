package dev.countercraft.client.fx;

import dev.countercraft.CounterCraft;
import dev.countercraft.gun.PlantedC4;
import dev.countercraft.net.BlastWavePayload;
import dev.countercraft.net.ShockwavePayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * A C4's shockwave on this client.
 * <ul>
 * <li>As it goes off (BlastWavePayload): the wave, spreading through the world from the bomb in every direction (drawn
 * by {@link dev.countercraft.client.gpu.ShockwavePass}), and its rumble (c4.shockwave.rumble, built from CS2's audio),
 * coming from the bomb's side and swelling as the front closes in on you, fading once it's past.</li>
 * <li>As it reaches you, if it struck you (ShockwavePayload, timed by the server along its real path): a loud crack
 * (c4.shockwave.crack) with CS2's debris after it, and the struck effect: the screen shakes, with a quick blur easing
 * in and out.</li>
 * </ul>
 */
public final class ShockwaveFx {
	/** How long the struck effect lasts. */
	public static final double HIT_SECONDS = 0.9;
	/** The rumble swells over the front's last this-many blocks to you (about two thirds of a second). */
	private static final double RUMBLE_LEAD = 40;
	private static final RandomSource RANDOM = RandomSource.create();

	/** The grey dust the wave leaves in the air where it went clears with the black smoke: kept until it's gone. */
	public static final double DUST_SECONDS = C4Explosion.SMOKE_SECONDS;

	/** A wave on its way out (then its dust settling): where from, and when (System.nanoTime). */
	public record Wave(Vec3 centre, long start) {
		/** How far its front has got (blocks). */
		public double front() {
			return age() * PlantedC4.WAVE_SPEED;
		}

		/** Seconds since it went off. */
		public double age() {
			return (System.nanoTime() - start) / 1e9;
		}
	}

	private static final List<Wave> WAVES = new ArrayList<>();
	private static long hitAt = -1;
	private static float strength;
	private static Vec3 dir = new Vec3(0, 0, 1);
	private static float seed;

	private ShockwaveFx() {
	}

	/** A bomb went off: its bang, its wave, and the wave's rumble if it can reach us. */
	public static void wave(BlastWavePayload p) {
		Wave w = new Wave(new Vec3(p.x(), p.y(), p.z()), System.nanoTime());
		WAVES.add(w);
		C4Explosion.start(w.centre());
		LocalPlayer pl = Minecraft.getInstance().player;
		if (pl == null) {
			return;
		}
		double d = pl.getEyePosition().distanceTo(w.centre());
		float bang = bangVolume(d);
		if (Boolean.getBoolean("countercraft.grenadeTest")) {
			boolean curve = bangVolume(0) == 1f && bangVolume(16) == 1f && Math.abs(bangVolume(96) - 0.15f) < 1e-4
					&& bangVolume(60) < bangVolume(40) && bangVolume(128) == 0f;
			CounterCraft.LOG.info("[countercraft] selftest c4_bang_range: {} here {} m: {}", curve && bang > 0 ? "PASS" : "FAIL",
					String.format("%.1f", d), String.format("%.2f", bang));
		}
		if (bang > 0f) {
			Vec3 toward = w.centre().subtract(pl.getEyePosition());
			Vec3 from = toward.lengthSqr() < 1e-6 ? pl.getEyePosition() : pl.getEyePosition().add(toward.normalize().scale(Math.min(d, 6)));
			// c4.explode's own volume (sounds.json) is 0.5: twice this is full
			sound("c4.explode", from, 2 * bang, 0);
		}
		if (d <= PlantedC4.WAVE_HEARD) {
			Minecraft.getInstance().getSoundManager().play(new Rumble(w));
		}
	}

	/**
	 * The bang's loudness at {@code d} blocks (0..1): full within a chunk of the bomb, easing to faint (0.15) six
	 * chunks out, gone by eight. Minecraft's own fall-off went silent at about four.
	 */
	static float bangVolume(double d) {
		if (d <= 16) {
			return 1f;
		}
		if (d <= 96) {
			return (float) (1 - 0.85 * Math.pow((d - 16) / 80, 0.7));
		}
		return (float) Math.max(0, 0.15 * (1 - (d - 96) / 32));
	}

	/** The waves still spreading, or whose dust is still settling ({@link #DUST_SECONDS}). */

	public static List<Wave> waves() {
		WAVES.removeIf(w -> w.age() > DUST_SECONDS);
		return WAVES;
	}

	/** The wave reached us. */
	public static void on(ShockwavePayload p) {
		LocalPlayer pl = Minecraft.getInstance().player;
		if (pl == null) {
			return;
		}
		if (Boolean.getBoolean("countercraft.grenadeTest")) {
			CounterCraft.LOG.info("[countercraft] selftest c4_shockwave_reached: {} strike={} loudness={}", p.loudness() > 0 ? "PASS" : "FAIL",
					String.format("%.2f", p.strike()), String.format("%.2f", p.loudness()));
		}
		if (p.strike() <= 0f) {
			return; // it went past (the rumble says so): only a hit cracks
		}
		Vec3 d = new Vec3(p.dx(), p.dy(), p.dz());
		Vec3 eye = pl.getEyePosition();
		Vec3 from = eye.subtract(d.scale(3));
		// in the blast, the full crack and debris raining down; past it (no damage) a weaker crack, fainter further out
		boolean blast = p.strike() >= PlantedC4.STRUCK_EDGE;
		sound("c4.shockwave.crack", from, blast ? 0.75 + 0.25 * p.strike() : 0.75 * (0.35 + 0.65 * p.strike() / PlantedC4.STRUCK_EDGE), 0);
		if (blast) {
			sound("c4.shockwave.debris", eye, 1.0, 2);
		}
		if (hitTime() < 0 || p.strike() >= strength) {
			hitAt = System.nanoTime();
			strength = p.strike();
			dir = d;
			seed = RANDOM.nextFloat() * 100f;
		}
	}

	/** Seconds since it struck you, or -1 when the struck effect isn't running. */
	public static double hitTime() {
		if (hitAt < 0) {
			return -1;
		}
		double t = (System.nanoTime() - hitAt) / 1e9;
		return t > HIT_SECONDS ? -1 : t;
	}

	public static float strength() {
		return strength;
	}

	/** Which way the wave was going where it struck you (a unit vector). */
	public static Vec3 direction() {
		return dir;
	}

	public static float seed() {
		return seed;
	}

	/**
	 * Positioned for direction, without Minecraft's distance attenuation (the loudness is worked out here). The volume
	 * multiplies the sound's own (sounds.json); Minecraft caps the result at full.
	 */
	private static void sound(String id, Vec3 at, double volume, int delayTicks) {
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(CounterCraft.id(id), SoundSource.BLOCKS,
				(float) volume, 1f, RANDOM, false, delayTicks, SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
	}

	/**
	 * The rumble of a wave on its way: a loop from the bomb's side, silent until the front is {@link #RUMBLE_LEAD}
	 * blocks out, then swelling (and rising in pitch) as it closes in, quieter the further you are from the bomb; once
	 * the front has passed it dies away over half a second.
	 */
	private static final class Rumble extends AbstractTickableSoundInstance {
		private final Wave wave;

		Rumble(Wave wave) {
			super(SoundEvent.createVariableRangeEvent(CounterCraft.id("c4.shockwave.rumble")), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.wave = wave;
			this.looping = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.volume = 0.001f;
			place();
		}

		private void place() {
			LocalPlayer p = Minecraft.getInstance().player;
			if (p == null) {
				return;
			}
			Vec3 eye = p.getEyePosition();
			Vec3 toward = wave.centre().subtract(eye);
			Vec3 at = toward.lengthSqr() < 1e-6 ? eye : eye.add(toward.normalize().scale(3));
			x = at.x;
			y = at.y;
			z = at.z;
		}

		@Override
		public void tick() {
			LocalPlayer p = Minecraft.getInstance().player;
			if (p == null) {
				stop();
				return;
			}
			place();
			double d = p.getEyePosition().distanceTo(wave.centre());
			double ahead = d - wave.front();
			float far = (float) Math.max(0.2, 1 - d / PlantedC4.WAVE_HEARD);
			if (ahead > 0) {
				float k = (float) Math.max(0, 1 - ahead / RUMBLE_LEAD);
				volume = Math.max(0.001f, far * k * k);
				pitch = 0.75f + 0.25f * k;
			} else {
				double since = -ahead / PlantedC4.WAVE_SPEED;
				volume = Math.max(0.001f, far * (float) Math.max(0, 1 - since / 0.5));
				if (since > 0.5) {
					stop();
				}
			}
		}
	}
}
