package dev.countercraft.client.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.Hitboxes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Poses CS2's hitbox capsules (agents/hitboxes.json: bone-frame endpoints in Source units) in the agent's standing and
 * crouching rifle idles, and hands them to {@link Hitboxes} for the server's hit tests (the integrated server shares
 * this JVM; a dedicated server never gets them and keeps Minecraft's boxes).
 */
public final class HitboxPoser {
	private static final float INCH = 0.0254f;

	private HitboxPoser() {
	}

	/** {@code json} is hitboxes.json; returns the number of capsules, 0 if none could be placed. */
	public static int pose(AgentData data, byte[] json) {
		JsonArray boxes = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("boxes");
		Hitboxes.Capsule[] stand = capsules(data, boxes, 0f);
		Hitboxes.Capsule[] crouch = capsules(data, boxes, 1f);
		if (stand.length == 0) {
			return 0;
		}
		Hitboxes.set(stand, crouch);
		return stand.length;
	}

	/** Capsules in model space (metres, X left, Y up, Z forward) after 1.5 s of the AK idle at this crouch amount. */
	static Hitboxes.Capsule[] capsules(AgentData data, JsonArray boxes, float crouch) {
		AgentAnimator a = new AgentAnimator(data);
		AgentAnimator.Input in = new AgentAnimator.Input();
		in.dt = 1f / 60f;
		in.onGround = true;
		in.crouch = crouch;
		in.weaponType = "weapon_ak47";
		in.weaponCategory = "weapon_category_rifle";
		for (int i = 0; i < 90; i++) {
			a.update(in);
		}
		String[] names = data.skeleton().names;
		List<Hitboxes.Capsule> out = new ArrayList<>();
		for (JsonElement el : boxes) {
			JsonObject b = el.getAsJsonObject();
			if (b.get("shape").getAsInt() != 2) {
				continue; // capsules only (all of CS2's player hitboxes are)
			}
			int bone = -1;
			String want = b.get("bone").getAsString();
			for (int i = 0; i < names.length; i++) {
				if (names[i].equalsIgnoreCase(want)) {
					bone = i;
					break;
				}
			}
			if (bone < 0) {
				CounterCraft.LOG.warn("[countercraft] hitbox bone {} not in the agent skeleton", want);
				continue;
			}
			float[] p = point(a.model, bone, b.getAsJsonArray("a"));
			float[] q = point(a.model, bone, b.getAsJsonArray("b"));
			out.add(new Hitboxes.Capsule(p[0], p[1], p[2], q[0], q[1], q[2], b.get("r").getAsFloat() * INCH, b.get("group").getAsInt()));
		}
		return out.toArray(new Hitboxes.Capsule[0]);
	}

	/** A bone-frame point (Source units) through the bone's model matrix (3x4, metres). */
	private static float[] point(float[] m, int bone, JsonArray v) {
		float x = v.get(0).getAsFloat() * INCH, y = v.get(1).getAsFloat() * INCH, z = v.get(2).getAsFloat() * INCH;
		int o = bone * 12;
		return new float[] {m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 3], m[o + 4] * x + m[o + 5] * y + m[o + 6] * z + m[o + 7],
				m[o + 8] * x + m[o + 9] * y + m[o + 10] * z + m[o + 11]};
	}
}
