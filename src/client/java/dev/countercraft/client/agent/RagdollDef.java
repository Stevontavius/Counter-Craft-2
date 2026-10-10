package dev.countercraft.client.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;

/**
 * An agent's CS2 ragdoll (agents/ragdoll/&lt;agent&gt;.json, converter/ragdoll.py): bodies on animation-skeleton bones with
 * their shapes (bone frame, metres), masses and damping, and the joints with CS2's swing / twist limits.
 */
public final class RagdollDef {
	public record Shape(boolean capsule, float[] a, float[] b, float r) {
	}

	public record Body(int bone, float mass, float linearDamping, float angularDamping, List<Shape> shapes) {
	}

	/** frame position (body1 / body2 bone frame, metres) and rotation (x, y, z, w). */
	public record Joint(int type, int body1, int body2, float[] p1, float[] q1, float[] p2, float[] q2, float[] swing, float[] twist) {
	}

	public final List<Body> bodies = new ArrayList<>();
	public final List<Joint> joints = new ArrayList<>();
	private static final Map<String, Optional<RagdollDef>> CACHE = new HashMap<>();

	private RagdollDef() {
	}

	/** The ragdoll of an agent model, or null (not converted). */
	public static RagdollDef of(String agent, AgentSkeleton skel) {
		return CACHE.computeIfAbsent(agent, a -> {
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("agents/ragdoll/" + a + ".json"))) {
				return Optional.of(parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), skel));
			} catch (Exception e) {
				CounterCraft.LOG.info("[countercraft] no ragdoll for {} ({})", a, e.toString());
				return Optional.empty();
			}
		}).orElse(null);
	}

	public static void clear() {
		CACHE.clear();
	}

	private static float[] vec(JsonElement e) {
		JsonArray a = e.getAsJsonArray();
		float[] out = new float[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsFloat();
		}
		return out;
	}

	static RagdollDef parse(JsonObject j, AgentSkeleton skel) {
		RagdollDef d = new RagdollDef();
		Map<Integer, Integer> remap = new HashMap<>();
		JsonArray bodies = j.getAsJsonArray("bodies");
		for (int i = 0; i < bodies.size(); i++) {
			JsonObject b = bodies.get(i).getAsJsonObject();
			int bone = skel.index(b.get("bone").getAsString());
			if (bone < 0) {
				continue;
			}
			List<Shape> shapes = new ArrayList<>();
			for (JsonElement se : b.getAsJsonArray("shapes")) {
				JsonObject s = se.getAsJsonObject();
				if ("capsule".equals(s.get("type").getAsString())) {
					shapes.add(new Shape(true, vec(s.get("a")), vec(s.get("b")), s.get("r").getAsFloat()));
				} else {
					float[] c = vec(s.get("c"));
					shapes.add(new Shape(false, c, c, s.get("r").getAsFloat()));
				}
			}
			remap.put(i, d.bodies.size());
			d.bodies.add(new Body(bone, b.get("mass").getAsFloat(), b.get("linearDamping").getAsFloat(), b.get("angularDamping").getAsFloat(), shapes));
		}
		for (JsonElement je : j.getAsJsonArray("joints")) {
			JsonObject o = je.getAsJsonObject();
			Integer b1 = remap.get(o.get("body1").getAsInt());
			Integer b2 = remap.get(o.get("body2").getAsInt());
			if (b1 == null || b2 == null) {
				continue;
			}
			JsonObject f1 = o.getAsJsonObject("frame1");
			JsonObject f2 = o.getAsJsonObject("frame2");
			d.joints.add(new Joint(o.get("type").getAsInt(), b1, b2, vec(f1.get("p")), vec(f1.get("q")), vec(f2.get("p")), vec(f2.get("q")),
					o.get("swing").isJsonNull() ? null : vec(o.get("swing")), o.get("twist").isJsonNull() ? null : vec(o.get("twist"))));
		}
		return d;
	}
}
