package dev.countercraft.client.agent.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One compiled CS2 animation graph (agents/graphs/<name>.json, from animation/graphs/worldmodel/*.vnmgraph): the node
 * definitions exactly as CS2 compiled them, the control parameter names (node i < params.length is parameter i), and
 * the data slots (clip ids / referenced graph names).
 */
public final class GraphDef {
	public final String name;
	public final int root;
	public final String[] params;
	public final JsonObject[] nodes;
	public final String[] paths;
	/** Data slot -> clip id ("rifle/rifle_ak/shoot_ak") or null. */
	final String[] clipSlots;
	/** Data slot -> referenced graph name ("worldmodel_gun__ak47") or null. */
	final String[] graphSlots;
	public final String[] virtualParams;
	public final int[] virtualParamNodes;
	/** Referenced graph index (CNmReferencedGraphNode m_nReferencedGraphIdx) -> data slot. */
	final int[] refSlots;

	public GraphDef(JsonObject j) {
		name = j.get("name").getAsString();
		root = j.get("root").getAsInt();
		JsonArray p = j.getAsJsonArray("params");
		params = new String[p.size()];
		for (int i = 0; i < params.length; i++) {
			params[i] = p.get(i).getAsString();
		}
		virtualParams = strs(j, "virtualParams");
		virtualParamNodes = ints(j, "virtualParamNodes");
		JsonArray n = j.getAsJsonArray("nodes");
		nodes = new JsonObject[n.size()];
		for (int i = 0; i < nodes.length; i++) {
			nodes[i] = n.get(i).getAsJsonObject();
		}
		JsonArray ps = j.getAsJsonArray("paths");
		paths = new String[ps.size()];
		for (int i = 0; i < paths.length; i++) {
			paths[i] = ps.get(i).getAsString();
		}
		JsonArray res = j.getAsJsonArray("resources");
		clipSlots = new String[res.size()];
		graphSlots = new String[res.size()];
		for (int i = 0; i < res.size(); i++) {
			JsonObject r = res.get(i).getAsJsonObject();
			if (r.has("clip")) {
				clipSlots[i] = r.get("clip").getAsString();
			} else if (r.has("graph")) {
				graphSlots[i] = r.get("graph").getAsString();
			}
		}
		JsonArray rs = j.getAsJsonArray("refSlots");
		refSlots = new int[rs.size()];
		for (int i = 0; i < refSlots.length; i++) {
			refSlots[i] = rs.get(i).getAsJsonArray().get(1).getAsInt();
		}
	}

	public String path(int node) {
		return node >= 0 && node < paths.length ? paths[node] : "#" + node;
	}

	static int i(JsonObject o, String k, int dflt) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? dflt : e.getAsInt();
	}

	static float f(JsonObject o, String k, float dflt) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? dflt : e.getAsFloat();
	}

	static boolean b(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}

	static String s(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	static int[] ints(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonArray()) {
			return new int[0];
		}
		JsonArray a = e.getAsJsonArray();
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}
		return out;
	}

	static String[] strs(JsonObject o, String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonArray()) {
			return new String[0];
		}
		JsonArray a = e.getAsJsonArray();
		String[] out = new String[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).isJsonNull() ? "" : a.get(i).getAsString();
		}
		return out;
	}
}
