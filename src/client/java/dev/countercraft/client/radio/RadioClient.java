package dev.countercraft.client.radio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.net.RadioFxPayload;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Client side of the radio: the speaker's agent's voice lines per talker concept (radio/phoenix.json for T, radio/sas.json
 * for CT, converted from CS2's talker rules, lines weighted by their odds) and the chat wheel's options (radio/chatwheel.json: label, concept,
 * icon). Lines play without distance falloff, like CS2's radio; chat wheel calls also print a chat line.
 */
public final class RadioClient {
	/** One chat wheel option. */
	public record Option(String key, String text, String concept, String icon) {
	}

	private record Line(Identifier sound, int odds) {
	}

	/** Voice (CS2 vo/agents folder) -> concept -> lines. */
	private static final Map<String, Map<String, List<Line>>> VOICES = new HashMap<>();
	/** The default agents' voices: T Phoenix, CT SAS. */
	private static final String T_VOICE = "phoenix";
	private static final String CT_VOICE = "sas";
	/** CS2's team colours (csgostyles.css color-T / color-CT). */
	private static final Map<String, Option> OPTIONS = new HashMap<>();
	private static String locationFormat = " %s4%s1﹫%s2: %s3";

	private RadioClient() {
	}

	public static void reload(ResourceManager rm) {
		VOICES.clear();
		OPTIONS.clear();
		for (String v : new String[] {T_VOICE, CT_VOICE}) {
			voice(rm, v);
		}
		JsonObject wheel = read(rm, "radio/chatwheel.json");
		if (wheel != null) {
			for (JsonElement el : wheel.getAsJsonArray("options")) {
				JsonObject o = el.getAsJsonObject();
				String icon = o.has("icon") && !o.get("icon").isJsonNull() ? o.get("icon").getAsString() : null;
				OPTIONS.put(o.get("key").getAsString(), new Option(o.get("key").getAsString(), o.get("text").getAsString(),
						o.get("concept").getAsString(), icon));
			}
			if (wheel.has("location") && !wheel.get("location").getAsString().isEmpty()) {
				// CS2's string carries its chat colour codes (control characters): Minecraft's font would draw them as boxes
				locationFormat = wheel.get("location").getAsString().replaceAll("\\p{Cntrl}", "");
			}
		}
		CounterCraft.LOG.info("[countercraft] radio: {} voices, {} chat wheel options", VOICES.keySet(), OPTIONS.size());
	}

	/** A voice's lines (radio/&lt;voice&gt;.json), loaded on first use; null if the pack doesn't have it. */
	private static Map<String, List<Line>> voice(ResourceManager rm, String v) {
		if (VOICES.containsKey(v)) {
			return VOICES.get(v);
		}
		JsonObject voice = read(rm, "radio/" + v + ".json");
		Map<String, List<Line>> concepts = null;
		if (voice != null) {
			concepts = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : voice.entrySet()) {
				List<Line> lines = new ArrayList<>();
				for (JsonElement l : e.getValue().getAsJsonArray()) {
					JsonArray a = l.getAsJsonArray();
					lines.add(new Line(CounterCraft.id(a.get(0).getAsString()), a.get(1).getAsInt()));
				}
				concepts.put(e.getKey(), lines);
			}
		}
		VOICES.put(v, concepts);
		return concepts;
	}

	private static JsonObject read(ResourceManager rm, String path) {
		try (InputStream in = rm.open(CounterCraft.id(path))) {
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] no {} ({}): run converter/convert.py and enable the CS2 asset pack", path, e.toString());
			return null;
		}
	}

	public static Option option(String key) {
		return OPTIONS.get(key);
	}

	/** Someone's radio call: the voice line (same pick on every client), and the chat line for chat wheel calls. */
	public static void onRadio(RadioFxPayload p) {
		Minecraft mc = Minecraft.getInstance();
		// the speaker's team picks the voice and the name colour
		net.minecraft.world.entity.Entity speaker = mc.level == null ? null : mc.level.getEntity(p.speaker());
		boolean ct = speaker instanceof net.minecraft.world.entity.player.Player sp && dev.countercraft.team.Teams.CT.equals(dev.countercraft.team.Teams.of(sp));
		// the speaker's agent's voice (yours from the loadout, others' synced), else the team's default agent's
		String chosen = "";
		if (speaker instanceof net.minecraft.world.entity.player.Player sp) {
			chosen = sp == mc.player ? dev.countercraft.client.loadout.LoadoutConfig.side(dev.countercraft.team.Teams.of(sp)).voice()
					: dev.countercraft.team.Cosmetics.of(sp).voice();
		}
		Map<String, List<Line>> voice = chosen.isEmpty() ? null : voice(mc.getResourceManager(), chosen);
		if (voice == null) {
			voice = VOICES.get(ct ? CT_VOICE : T_VOICE) != null ? VOICES.get(ct ? CT_VOICE : T_VOICE) : VOICES.get(T_VOICE);
		}
		List<Line> lines = voice == null ? null : voice.get(p.concept());
		if (lines != null && !lines.isEmpty()) {
			int total = lines.stream().mapToInt(Line::odds).sum();
			int r = new Random(p.seed()).nextInt(Math.max(1, total));
			Line pick = lines.get(lines.size() - 1);
			for (Line l : lines) {
				r -= l.odds();
				if (r < 0) {
					pick = l;
					break;
				}
			}
			mc.getSoundManager().play(new SimpleSoundInstance(pick.sound(), SoundSource.VOICE, 1f, 1f, RandomSource.create(), false, 0,
					SoundInstance.Attenuation.NONE, 0, 0, 0, true));
		}
		if (!p.label().isEmpty() && mc.gui != null) {
			Option o = OPTIONS.get(p.label());
			String text = o != null ? o.text() : p.label();
			String where = p.location().isEmpty() ? "" : Component.translatable(p.location()).getString();
			// CS2's Game_radio_location: " %s4%s1@%s2: %s3" (team prefix, name, place, message)
			String[] parts = locationFormat.replace("%s4", "").split("%s1|%s2|%s3", -1);
			// the name as chat shows it: the player's display name (with a Genuine Skins tick) in their faction's colour
			int nameColor = ct ? dev.countercraft.team.Teams.CT_COLOR : dev.countercraft.team.Teams.T_COLOR;
			Component name = speaker instanceof net.minecraft.world.entity.player.Player sp ? sp.getDisplayName().copy().withColor(nameColor)
					: Component.literal(p.name()).withColor(nameColor);
			MutableComponent line = Component.literal(parts.length > 0 ? parts[0] : " ").withColor(0xFFB0C3D9)
					.append(name)
					.append(Component.literal((parts.length > 1 ? parts[1] : " @ ") + where).withColor(0xFF9FB4C7))
					.append(Component.literal((parts.length > 2 ? parts[2] : ": ") + text).withColor(0xFFFFFFFF));
			mc.gui.chatListener().handleSystemMessage(line, false);
		}
	}
}
