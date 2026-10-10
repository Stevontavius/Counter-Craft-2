package dev.countercraft.client.viewmodel;

import java.util.Arrays;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;

/**
 * Plays one clip at a time on a viewmodel. Non-looping clips fall back to "idle" when they end. Fires the clip's
 * sound events as playback passes them. A clip can repeat one section a set number of times first (CS2's shotgun
 * reload: intro, one loop per shell, outro).
 */
public final class ViewmodelAnimator {
	private final ViewmodelAsset asset;
	private ViewmodelAsset.ClipData clip;
	private long startNanos;
	private float rate = 1f;
	private int nextEvent;
	private final float[] pose;
	/** Clip to rest in when nothing else plays ("idle", or "idle_empty" with the slide locked back). */
	private String idleRole;
	private float loopStart;
	private float loopEnd;
	private int loopsLeft;
	/** Crossfade from the pose shown when the clip changed (CS2's animgraph blends transitions instead of snapping). */
	private float[] blendFrom;
	private long blendStart;
	private float blendSeconds;

	public ViewmodelAnimator(ViewmodelAsset asset) {
		this(asset, "idle");
	}

	/** {@code idleRole}: the resting clip, e.g. "ads_idle" for the aim-down-sights pose of an AUG / SG 553. */
	public ViewmodelAnimator(ViewmodelAsset asset, String idleRole) {
		this.asset = asset;
		this.pose = new float[asset.boneCount * 12];
		this.idleRole = idleRole;
		play(idleRole, 1f);
	}

	public ViewmodelAsset asset() {
		return asset;
	}

	public String current() {
		return clip == null ? "" : clip.name();
	}

	/** The resting clip ("idle", "idle_empty", "ads_idle"). */
	public String idleRole() {
		return idleRole;
	}

	public boolean has(String role) {
		return asset.clips.containsKey(role);
	}

	/** Switch the resting clip (e.g. to "idle_empty" when the magazine runs dry); applies at once if resting. */
	public void setIdle(String role) {
		String r = has(role) ? role : "idle";
		if (!r.equals(idleRole)) {
			boolean resting = clip != null && clip.name().equals(idleRole);
			idleRole = r;
			if (resting) {
				play(r, 1f);
			}
		}
	}

	/** Start {@code role} from the beginning; {@code rate} > 1 plays faster. Unknown roles are ignored. */
	public void play(String role, float rate) {
		play(role, rate, defaultBlend(role));
	}

	/**
	 * A draw starts from its own first frame (the weapon comes up from below); shots and the R8's hammer pull cut in
	 * almost at once; everything else (inspect, back to idle, reloads, an interrupted inspect) eases over 0.2 s.
	 */
	private static float defaultBlend(String role) {
		if (role.startsWith("draw")) {
			return 0f;
		}
		return role.startsWith("shoot") || role.startsWith("ads_shoot") || role.equals("dryfire") || role.equals("prepare") ? 0.03f : 0.2f;
	}

	/** Start {@code role}, crossfading from the current pose over {@code blend} seconds (0 = cut). */
	public void play(String role, float rate, float blend) {
		ViewmodelAsset.ClipData c = asset.clips.get(role);
		if (c == null) {
			return;
		}
		if (clip != null && blend > 0f) {
			blendFrom = pose().clone();
			blendStart = System.nanoTime();
			blendSeconds = blend;
		} else {
			blendFrom = null;
		}
		clip = c;
		this.rate = rate;
		startNanos = System.nanoTime();
		nextEvent = 0;
		loopsLeft = 0;
	}

	/** Play {@code role}, running its [loopStart, loopEnd) section {@code passes} times in all (seconds of clip time). */
	public void playLooped(String role, float loopStart, float loopEnd, int passes) {
		play(role, 1f);
		if (clip != null && clip.name().equals(role) && loopEnd > loopStart) {
			this.loopStart = loopStart;
			this.loopEnd = loopEnd;
			this.loopsLeft = Math.max(0, passes - 1);
		}
	}

	/** Seconds into the current clip. */
	public float clipTime() {
		return clip == null ? 0f : Math.min(time(), clip.duration());
	}

	private float time() {
		return (System.nanoTime() - startNanos) / 1e9f * rate;
	}

	/** Advance: fire due sound events, return to idle when a clip finishes. Call once per frame. */
	public void update(Consumer<Identifier> sound) {
		float t = time();
		ViewmodelAsset.Event[] ev = clip.events();
		if (loopsLeft > 0 && t >= loopEnd) {
			while (nextEvent < ev.length && ev[nextEvent].time() < loopEnd) {
				sound.accept(ev[nextEvent++].sound());
			}
			// back to the start of the loop section (its sounds, e.g. the shell going in, play again)
			startNanos += (long) ((loopEnd - loopStart) / rate * 1e9);
			loopsLeft--;
			t = time();
			nextEvent = 0;
			while (nextEvent < ev.length && ev[nextEvent].time() < loopStart) {
				nextEvent++;
			}
		}
		while (nextEvent < ev.length && ev[nextEvent].time() <= t) {
			sound.accept(ev[nextEvent++].sound());
		}
		if (!clip.name().equals(idleRole) && t >= clip.duration()) {
			play(idleRole, 1f);
		}
	}

	/** Collapse a bone's vertices to a point (hides e.g. an unscrewed silencer). Call after {@link #pose()}. */
	public void hideBone(float[] pose, String bone) {
		Integer i = asset.bones.get(bone);
		if (i != null) {
			Arrays.fill(pose, i * 12, i * 12 + 12, 0f);
		}
	}

	/** Current skinning matrices (bones x 12 floats), linearly blended between the two nearest baked frames. */
	public float[] pose() {
		float f = Math.min(time(), clip.duration()) * clip.fps();
		int f0 = Math.min((int) f, clip.frames() - 1);
		int f1 = Math.min(f0 + 1, clip.frames() - 1);
		float a = f - f0;
		int n = asset.boneCount * 12;
		float[] m = clip.mats();
		int o0 = f0 * n;
		int o1 = f1 * n;
		for (int i = 0; i < n; i++) {
			pose[i] = m[o0 + i] + (m[o1 + i] - m[o0 + i]) * a;
		}
		if (blendFrom != null) {
			float w = (System.nanoTime() - blendStart) / 1e9f / blendSeconds;
			if (w >= 1f) {
				blendFrom = null;
			} else {
				float k = w * w * (3f - 2f * w); // smoothstep
				for (int i = 0; i < n; i++) {
					pose[i] = blendFrom[i] + (pose[i] - blendFrom[i]) * k;
				}
			}
		}
		return pose;
	}
}
