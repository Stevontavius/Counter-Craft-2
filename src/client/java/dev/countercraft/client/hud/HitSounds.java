package dev.countercraft.client.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.net.HitSoundPayload;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * CS2's hit and kill feedback sounds: the shooter hears Player.(Damage|Death)(HeadShot|Body)[Armor].AttackerFeedback
 * (the helmet dink, the kevlar thud, flesh), the victim the .Victim variant, everyone else the .Onlooker one at the
 * hit. Composite events (the helmet kill) play all their sounds together (hud/feedback.json from the converter).
 * Knife hits have no attacker feedback in CS2 (a null sound).
 */
public final class HitSounds {
	private static final Map<String, List<String>> EVENTS = new HashMap<>();

	private HitSounds() {
	}

	public static void reload(ResourceManager rm) {
		EVENTS.clear();
		try (var in = rm.open(CounterCraft.id("hud/feedback.json"))) {
			JsonObject j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : j.entrySet()) {
				List<String> ids = new ArrayList<>();
				e.getValue().getAsJsonArray().forEach(x -> ids.add(x.getAsString()));
				EVENTS.put(e.getKey(), ids);
			}
		} catch (Exception e) {
			CounterCraft.LOG.info("[countercraft] no hud/feedback.json ({}): run converter/convert.py", e.toString());
		}
	}

	public static void onHit(HitSoundPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		int me = mc.player.getId();
		boolean head = (p.flags() & HitSoundPayload.F_HEAD) != 0;
		boolean lethal = (p.flags() & HitSoundPayload.F_LETHAL) != 0;
		boolean armor = (p.flags() & HitSoundPayload.F_ARMOR) != 0;
		boolean knife = (p.flags() & HitSoundPayload.F_KNIFE) != 0;
		String role = p.attacker() == me ? "AttackerFeedback" : p.victim() == me ? "Victim" : "Onlooker";
		if (knife && role.equals("AttackerFeedback")) {
			return;
		}
		String event = "Player." + (lethal ? "Death" : "Damage") + (head ? "HeadShot" : "Body") + (armor ? "Armor" : "") + "." + role;
		List<String> ids = EVENTS.get(event);
		if (ids == null) {
			return;
		}
		boolean positional = role.equals("Onlooker");
		for (String id : ids) {
			mc.getSoundManager().play(new SimpleSoundInstance(CounterCraft.id(id), SoundSource.PLAYERS, 1f, 1f, RandomSource.create(), false, 0,
					positional ? SoundInstance.Attenuation.LINEAR : SoundInstance.Attenuation.NONE, p.x(), p.y(), p.z(), !positional));
		}
	}
}
