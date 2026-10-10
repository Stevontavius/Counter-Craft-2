package dev.countercraft.client.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * CS2's third-person animation skeleton (worldmodel.vnmskel): bone order, parents, reference pose (bone-local, root in
 * glTF axes, metres) and the named bone masks (one weight per bone).
 */
public final class AgentSkeleton {
	public final String[] names;
	public final int[] parents;
	public final float[] refR;
	public final float[] refT;
	public final Map<String, float[]> masks = new HashMap<>();
	private final Map<String, Integer> index = new HashMap<>();

	AgentSkeleton(JsonObject j) {
		JsonArray b = j.getAsJsonArray("bones");
		int n = b.size();
		names = new String[n];
		parents = new int[n];
		refR = new float[n * 4];
		refT = new float[n * 3];
		JsonArray par = j.getAsJsonArray("parents");
		JsonArray ref = j.getAsJsonArray("ref");
		for (int i = 0; i < n; i++) {
			names[i] = b.get(i).getAsString();
			index.put(names[i], i);
			parents[i] = par.get(i).getAsInt();
			JsonObject rt = ref.get(i).getAsJsonObject();
			JsonArray r = rt.getAsJsonArray("r");
			JsonArray t = rt.getAsJsonArray("t");
			for (int k = 0; k < 4; k++) {
				refR[i * 4 + k] = r.get(k).getAsFloat();
			}
			for (int k = 0; k < 3; k++) {
				refT[i * 3 + k] = t.get(k).getAsFloat();
			}
		}
		for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("masks").entrySet()) {
			JsonArray w = e.getValue().getAsJsonArray();
			float[] m = new float[n];
			for (int i = 0; i < n; i++) {
				m[i] = w.get(i).getAsFloat();
			}
			masks.put(e.getKey(), m);
		}
	}

	public int bones() {
		return names.length;
	}

	public int index(String name) {
		return index.getOrDefault(name, -1);
	}
}
