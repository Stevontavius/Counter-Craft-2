package dev.countercraft.client.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** CS2's agents (agents/agents.json from convert.py --step agents): id, name, team, model, voice, rarity. */
public final class Agents {
	public record Agent(String id, String name, String team, String model, String voice, String rarity, boolean thumb) {
		public Identifier thumbnail() {
			return CounterCraft.id("textures/gui/agents/" + id + ".png");
		}
	}

	private static Map<String, Agent> agents;

	private Agents() {
	}

	public static void clear() {
		agents = null;
	}

	private static void load() {
		if (agents != null) {
			return;
		}
		agents = new LinkedHashMap<>();
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("agents/agents.json"))) {
			for (JsonElement e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				Agent a = new Agent(o.get("id").getAsString(), o.get("name").getAsString(), o.get("team").getAsString(), o.get("model").getAsString(),
						o.get("voice").getAsString(), o.has("rarity") && !o.get("rarity").isJsonNull() ? o.get("rarity").getAsString() : null,
						o.has("thumb") && o.get("thumb").getAsBoolean());
				agents.put(a.id(), a);
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] no agent list ({}): run converter --step agents", e.toString());
		}
	}

	/** A team's agents ("t" / "ct"), CS2's catalog order. */
	public static List<Agent> forTeam(String team) {
		load();
		List<Agent> out = new ArrayList<>();
		for (Agent a : agents.values()) {
			if (a.team().equals(team)) {
				out.add(a);
			}
		}
		return out;
	}

	public static Agent get(String id) {
		load();
		return agents.get(id);
	}

	/** The agent whose model (and voice) a loadout side holds, or null. */
	public static Agent byModel(String model, String team) {
		load();
		for (Agent a : agents.values()) {
			if (a.model().equals(model) && a.team().equals(team)) {
				return a;
			}
		}
		return null;
	}
}
