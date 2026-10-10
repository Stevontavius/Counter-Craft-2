package dev.countercraft.client.agent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One CS2 third-person clip (CAC1, written by converter/agent/agent.py): per bone of the animation skeleton a rotation
 * and translation track (one key if static) and optional scale, sampled at the clip's frame rate; whether it is
 * additive; its sync track and ID events (times as fractions of the clip); and the weapon skeleton's tracks.
 */
public final class AgentClip {
	public final String name;
	public final float duration;
	public final int frames;
	public final boolean additive;
	final Track[] tracks;
	public final List<Event> sync = new ArrayList<>();
	public final List<Event> events = new ArrayList<>();
	/** Weapon (secondary) skeleton bones, by name, with their parent name ("" = the skeleton root). */
	public final List<SecondaryBone> weapon = new ArrayList<>();
	public int syncStartOffset;

	public record Event(String id, float start, float duration) {
	}

	public record SecondaryBone(String name, String parent, Track track) {
	}

	static final class Track {
		float[] rot;
		float[] trans;
		float[] scale;

		void sample(int f0, int f1, float a, float[] r, int ro, float[] t, int to, float[] s, int so) {
			if (rot.length == 4) {
				System.arraycopy(rot, 0, r, ro, 4);
			} else {
				Pose.nlerp(rot, f0 * 4, rot, f1 * 4, a, r, ro);
			}
			if (trans.length == 3) {
				System.arraycopy(trans, 0, t, to, 3);
			} else {
				for (int k = 0; k < 3; k++) {
					t[to + k] = trans[f0 * 3 + k] + (trans[f1 * 3 + k] - trans[f0 * 3 + k]) * a;
				}
			}
			s[so] = scale == null ? 1f : scale[f0] + (scale[f1] - scale[f0]) * a;
		}
	}

	private AgentClip(String name, float duration, int frames, boolean additive, Track[] tracks) {
		this.name = name;
		this.duration = duration;
		this.frames = frames;
		this.additive = additive;
		this.tracks = tracks;
	}

	static AgentClip read(String name, ByteBuffer b) {
		b.order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[4];
		b.get(magic);
		if (!"CAC1".equals(new String(magic, StandardCharsets.US_ASCII))) {
			throw new IllegalArgumentException("not a CAC1 clip: " + name);
		}
		float duration = b.getFloat();
		int frames = b.getInt();
		boolean additive = b.get() != 0;
		Track[] tracks = new Track[b.getInt()];
		for (int i = 0; i < tracks.length; i++) {
			tracks[i] = track(b, frames);
		}
		AgentClip c = new AgentClip(name, duration, frames, additive, tracks);
		int ns = b.getInt();
		for (int i = 0; i < ns; i++) {
			c.sync.add(new Event(str(b), b.getFloat(), b.getFloat()));
		}
		int ne = b.getInt();
		for (int i = 0; i < ne; i++) {
			c.events.add(new Event(str(b), b.getFloat(), b.getFloat()));
		}
		int skels = b.getInt();
		for (int k = 0; k < skels; k++) {
			str(b); // skeleton name (the gun's)
			int nb = b.getInt();
			for (int i = 0; i < nb; i++) {
				String bone = str(b);
				String parent = str(b);
				c.weapon.add(new SecondaryBone(bone, parent, track(b, frames)));
			}
		}
		if (b.remaining() >= 4) {
			c.syncStartOffset = b.getInt();
		}
		return c;
	}

	private static Track track(ByteBuffer b, int frames) {
		int flags = b.get();
		Track t = new Track();
		t.rot = floats(b, ((flags & 1) != 0 ? 1 : frames) * 4);
		t.trans = floats(b, ((flags & 2) != 0 ? 1 : frames) * 3);
		t.scale = (flags & 4) != 0 ? null : floats(b, frames);
		return t;
	}

	/** Sample at a fraction of the clip (0..1) into {@code out}. */
	public void sample(float fraction, Pose out) {
		int f0;
		int f1;
		float a;
		if (frames <= 1) {
			f0 = f1 = 0;
			a = 0f;
		} else {
			float f = Math.max(0f, Math.min(1f, fraction)) * (frames - 1);
			f0 = Math.min(frames - 2, (int) f);
			f1 = f0 + 1;
			a = f - f0;
		}
		int n = Math.min(out.bones, tracks.length);
		for (int i = 0; i < n; i++) {
			tracks[i].sample(f0, f1, a, out.r, i * 4, out.t, i * 3, out.s, i);
		}
		out.additive = additive;
	}

	/** One weapon bone's local transform at a fraction (rotation into r[0..3], translation into t[0..2]). */
	public void sampleWeapon(SecondaryBone bone, float fraction, float[] r, float[] t) {
		int f0 = 0;
		int f1 = 0;
		float a = 0f;
		if (frames > 1) {
			float f = Math.max(0f, Math.min(1f, fraction)) * (frames - 1);
			f0 = Math.min(frames - 2, (int) f);
			f1 = f0 + 1;
			a = f - f0;
		}
		float[] s = new float[1];
		bone.track().sample(f0, f1, a, r, 0, t, 0, s, 0);
	}

	private static float[] floats(ByteBuffer b, int n) {
		float[] f = new float[n];
		b.asFloatBuffer().get(f);
		b.position(b.position() + n * 4);
		return f;
	}

	static String str(ByteBuffer b) {
		byte[] s = new byte[Short.toUnsignedInt(b.getShort())];
		b.get(s);
		return new String(s, StandardCharsets.UTF_8);
	}
}
