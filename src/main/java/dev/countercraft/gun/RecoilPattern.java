package dev.countercraft.gun;

import java.util.Random;

/**
 * CS2 spray recoil, driven by patterns measured in CS2 itself (measure/: offline local server,
 * weapon_accuracy_nospread 1, sv_showimpacts markers read from a slow-motion recording; on the AK two independent
 * sprays agree to 0.06 deg).
 *
 * <p>Model: the aim punch {@code B} (bullet degrees: where the next bullet goes relative to the crosshair) recovers
 * between shots as {@code dB/dt = -a*B - b} along its own direction (fitted to CS2's recovery on camera). Each shot
 * fires at the current {@code B}, then adds that shot's kick. Kicks are solved from the measured offsets and shot
 * times, so firing at the measured rhythm reproduces the measured pattern exactly; taps and pauses recover the way
 * CS2 does. The camera shows {@link #CAMERA_RATIO} of {@code B} (CS2's view punch is half the bullet offset).</p>
 *
 * <p>Guns whose shots are far apart (snipers, shotguns, R8, Deagle) recover fully between shots, so their offsets
 * say nothing about the kick. For those the camera replays a single-shot punch curve measured on CS2's camera
 * ({@link #withCurve}; shots that overlap add up), and their bullets follow the same curve unless a clean spray was
 * measured (Deagle).</p>
 */
public final class RecoilPattern {
	/** Camera punch / bullet offset, measured in CS2 (0.494). */
	public static final float CAMERA_RATIO = 0.494f;

	/** [shot][0 = up, 1 = right]: kick added after shot i, bullet degrees. */
	private final float[][] kicks;
	/** Seconds from shot i to shot i+1 in the measured rhythm. */
	private final double[] gaps;
	private final double decayExp;
	private final double decayLin;
	/** Measured camera punch after one shot, [frame][up, right] camera degrees; null = the camera follows B. */
	private float[][] curve;
	private float curveFps = 60f;
	private boolean bulletsFromCurve;
	/** Kick scale in the alternate mode (scoped AWP/SSG, R8 fan fire): CS2's recoil magnitude alt / normal. */
	private float altScale = 1f;

	private RecoilPattern(float[][] kicks, double[] gaps, double decayExp, double decayLin) {
		this.kicks = kicks;
		this.gaps = gaps;
		this.decayExp = decayExp;
		this.decayLin = decayLin;
	}

	/** Replay {@code curve} as the camera punch of every shot (and as the bullet offset too if {@code bullets}). */
	public RecoilPattern withCurve(float[][] curve, float fps, boolean bullets, float altScale) {
		this.curve = curve;
		this.curveFps = fps;
		this.bulletsFromCurve = bullets;
		this.altScale = altScale;
		return this;
	}

	public boolean hasCurve() {
		return curve != null;
	}

	/** One shot's camera punch {@code seconds} after it, scaled. */
	private void curveAt(double seconds, float scale, float[] acc) {
		double f = seconds * curveFps;
		if (curve == null || !(f >= 0) || f >= curve.length - 1) {
			return; // before the shot, long after it, or an empty slot (infinitely long ago)
		}
		int i = (int) f;
		float a = (float) (f - i);
		acc[0] += scale * (curve[i][0] + (curve[i + 1][0] - curve[i][0]) * a);
		acc[1] += scale * (curve[i][1] + (curve[i + 1][1] - curve[i][1]) * a);
	}

	/**
	 * From a measurement.
	 *
	 * @param offsets  bullet offsets [shot][up, right] (degrees) during sustained fire; shot 1 = {0, 0}
	 * @param times    seconds of each shot from the first
	 * @param decayExp a in dB/dt = -a*B - b (1/s)
	 * @param decayLin b in dB/dt = -a*B - b (bullet degrees / s)
	 */
	public static RecoilPattern measured(float[][] offsets, double[] times, double decayExp, double decayLin) {
		int n = offsets.length;
		float[][] kicks = new float[n][2];
		double[] gaps = new double[n];
		for (int i = 0; i + 1 < n; i++) {
			// B[i+1] = decay(B[i] + kick[i], gap)  =>  kick[i] = undecay(B[i+1], gap) - B[i]
			gaps[i] = Math.max(0.01, times[i + 1] - times[i]);
			float[] next = offsets[i + 1];
			double mag = Math.hypot(next[0], next[1]);
			double c = decayLin / decayExp;
			double before = mag <= 1e-6 ? 0 : (mag + c) * Math.exp(decayExp * gaps[i]) - c;
			double sx = mag <= 1e-6 ? 0 : next[0] / mag;
			double sy = mag <= 1e-6 ? 0 : next[1] / mag;
			kicks[i][0] = (float) (sx * before - offsets[i][0]);
			kicks[i][1] = (float) (sy * before - offsets[i][1]);
		}
		// after the last measured shot (unmeasurable: the magazine is empty): repeat the previous kick and rhythm
		kicks[n - 1] = kicks[Math.max(0, n - 2)].clone();
		gaps[n - 1] = gaps[Math.max(0, n - 2)];
		return new RecoilPattern(kicks, gaps, decayExp, decayLin);
	}

	/**
	 * Fallback when a gun has no measurement: kicks generated from CS2's recoil inputs (angle, variance, magnitude,
	 * seed). The shape is plausible, not CS2's exact pattern.
	 */
	public static RecoilPattern approximate(float angle, float angleVar, float magnitude, float magnitudeVar, int seed,
			int shots, double cycleSeconds) {
		Random rng = new Random(seed);
		int n = Math.max(1, shots);
		float[][] kicks = new float[n][2];
		double[] gaps = new double[n];
		float smoothed = angle;
		for (int i = 0; i < n; i++) {
			float ramp = Math.min(1f, i / 4f);
			float raw = angle + (rng.nextFloat() * 2f - 1f) * angleVar * ramp;
			smoothed = i == 0 ? raw : smoothed * 0.55f + raw * 0.45f;
			float kick = (magnitude + (rng.nextFloat() * 2f - 1f) * magnitudeVar) * 0.1f;
			double a = Math.toRadians(smoothed);
			kicks[i][0] = (float) (Math.cos(a) * kick);
			kicks[i][1] = (float) (Math.sin(a) * kick);
			gaps[i] = cycleSeconds;
		}
		return new RecoilPattern(kicks, gaps, 1.41, 21.5);
	}

	/**
	 * Kick after shot {@code index}. Past the end of the pattern (a magazine's worth; sv_infinite_ammo or a refill
	 * mid-spray) it loops back to the start, like CS2's recoil index wrapping: the spray climbs again from wherever
	 * the punch is. The kick after the last measured shot isn't measurable (the magazine is empty), so it repeats the
	 * one before.
	 */
	public float[] kick(int index) {
		return kicks[Math.floorMod(index, kicks.length)];
	}

	/** Measured seconds between shot {@code index} and the next (looping like {@link #kick}). */
	public double gap(int index) {
		return gaps[Math.floorMod(index, gaps.length)];
	}

	/** A punch vector's length after dt seconds of recovery (direction kept, stops at zero). */
	double decayed(double mag, double dt) {
		if (mag <= 0 || dt <= 0) {
			return Math.max(0, mag);
		}
		double c = decayLin / decayExp;
		return Math.max(0, (mag + c) * Math.exp(-decayExp * dt) - c);
	}

	/**
	 * Running recoil for one shooter. The server uses it for bullets and the client for the camera; both advance it
	 * on the same shots, so they agree.
	 */
	public static final class State {
		/** Current aim punch, bullet degrees. */
		public float up;
		public float right;
		/** Pattern position of the next shot. */
		public int shotIndex;
		private double lastShot = Double.NEGATIVE_INFINITY;
		private double lastDecay;
		private float punchAfterShot;
		private float upAfterShot;
		private float rightAfterShot;
		/** Recent shots (ticks) and their kick scale, for curve guns: punches that overlap add up. */
		private final double[] recentAt = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
		private final float[] recentScale = new float[4];
		private int recentNext;

		/** Camera punch {up, right} in degrees at {@code nowTicks}. */
		public float[] camera(RecoilPattern p, double nowTicks) {
			if (p.curve == null) {
				decay(p, nowTicks);
				return new float[] {up * CAMERA_RATIO, right * CAMERA_RATIO};
			}
			float[] acc = new float[2];
			for (int k = 0; k < recentAt.length; k++) {
				p.curveAt((nowTicks - recentAt[k]) / 20.0, recentScale[k], acc);
			}
			return acc;
		}

		/**
		 * Where a bullet fired now would land relative to the view centre {up, right} (bullet degrees), without firing:
		 * the aim punch for pattern guns, the curve for curve guns. The recoil-following crosshair sits here.
		 */
		public float[] aim(RecoilPattern p, double nowTicks) {
			if (p.curve != null && p.bulletsFromCurve) {
				float[] cam = camera(p, nowTicks);
				return new float[] {cam[0] / CAMERA_RATIO, cam[1] / CAMERA_RATIO};
			}
			double mag = Math.hypot(up, right);
			double k = mag > 1e-9 ? p.decayed(mag, (nowTicks - lastDecay) / 20.0) / mag : 0;
			return new float[] {(float) (up * k), (float) (right * k)};
		}

		private void decayBy(RecoilPattern p, double dt) {
			double mag = Math.hypot(up, right);
			double next = p.decayed(mag, dt);
			if (mag > 1e-9) {
				up *= (float) (next / mag);
				right *= (float) (next / mag);
			}
		}

		/** Recover the punch up to {@code nowTicks} (fractional ticks allowed). */
		public void decay(RecoilPattern p, double nowTicks) {
			double dt = (nowTicks - lastDecay) / 20.0;
			if (dt > 0) {
				decayBy(p, dt);
				lastDecay = nowTicks;
			}
		}

		/**
		 * A shot at {@code nowTicks} (fractional): returns this bullet's offset {up, right} in degrees, then applies its
		 * kick. A shot that follows the previous one in the measured rhythm (within a tick) recovers by the measured
		 * gap, so sustained fire reproduces the measured pattern exactly despite the 20 Hz tick.
		 */
		public float[] shoot(RecoilPattern p, double nowTicks) {
			return shoot(p, nowTicks, false);
		}

		/** As {@link #shoot(RecoilPattern, double)}; {@code alt} = alternate mode (scoped, R8 fan fire). */
		public float[] shoot(RecoilPattern p, double nowTicks, boolean alt) {
			if (p.curve != null) {
				float[] cam = camera(p, nowTicks);
				recentAt[recentNext] = nowTicks;
				recentScale[recentNext] = alt ? p.altScale : 1f;
				recentNext = (recentNext + 1) % recentAt.length;
				if (p.bulletsFromCurve) {
					shotIndex++;
					lastShot = nowTicks;
					return new float[] {cam[0] / CAMERA_RATIO, cam[1] / CAMERA_RATIO};
				}
			}
			double sinceLast = (nowTicks - lastShot) / 20.0;
			double measuredGap = shotIndex > 0 ? p.gap(shotIndex - 1) : 0;
			if (shotIndex > 0 && sinceLast <= measuredGap + 0.051) {
				// from the punch right after the last kick (the client may have decayed it for the camera since)
				up = upAfterShot;
				right = rightAfterShot;
				decayBy(p, measuredGap);
			} else {
				decayBy(p, (nowTicks - lastDecay) / 20.0);
				// paused: wind the pattern back as far as the punch has recovered (fully recovered = first bullet)
				// (past a loop of the pattern, from the position within the current loop)
				int n = p.kicks.length;
				int pos = shotIndex > n ? (shotIndex - 1) % n + 1 : shotIndex;
				shotIndex = punchAfterShot <= 1e-6 ? 0 : Math.round(pos * (float) (Math.hypot(up, right) / punchAfterShot));
			}
			float[] offset = {up, right};
			float[] k = p.kick(shotIndex++);
			up += k[0];
			right += k[1];
			punchAfterShot = (float) Math.hypot(up, right);
			upAfterShot = up;
			rightAfterShot = right;
			lastShot = nowTicks;
			lastDecay = nowTicks;
			return offset;
		}
	}
}
