package dev.countercraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.options.CcOptions;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * CC2 Options > Music & Sounds > Weapon Sound Style. CS:GO's weapon sounds are only there if the asset pack was built
 * with them (converter --csgo, from the player's own CS:GO install): the pack's csgo/weapon_sounds.json lists the weapon
 * sounds that have a CS:GO version, under csgo.&lt;id&gt; in sounds.json. With CS:GO chosen, each of those plays its
 * CS:GO version instead (SoundManagerMixin: every weapon sound, whoever's it is and however it reaches this client);
 * without the file, the option is greyed out and stays on Counter-Strike 2.
 */
public final class CsgoSounds {
	private static final Identifier FLAG = CounterCraft.id("csgo/weapon_sounds.json");
	private static volatile Set<String> ids = Set.of();
	/** The pack was built with CS:GO's weapon sounds only (installer: Weapon audio "CS:GO Legacy"): nothing to choose. */
	private static volatile boolean exclusive;

	private CsgoSounds() {
	}

	/** Resource reload: which weapon sounds the pack has a CS:GO version of. */
	public static void reload(ResourceManager rm) {
		Set<String> out = Set.of();
		boolean only = false;
		var res = rm.getResource(FLAG);
		if (res.isPresent()) {
			try (var reader = res.get().openAsReader()) {
				JsonObject j = JsonParser.parseReader(reader).getAsJsonObject();
				only = j.has("exclusive") && j.get("exclusive").getAsBoolean();
				// CS:GO-only packs have CS:GO's sounds under the weapon sounds' own names: nothing to redirect
				out = only ? Set.of() : Set.copyOf(j.getAsJsonObject("sounds").keySet());
			} catch (Exception e) {
				CounterCraft.LOG.warn("[countercraft] unreadable {}: {}", FLAG, e.toString());
			}
		}
		ids = out;
		exclusive = only;
	}

	/** The pack only has CS:GO's weapon sounds (the option shows CS:GO, greyed out). */
	public static boolean exclusive() {
		return exclusive;
	}

	/** The asset pack has CS:GO's weapon sounds. */
	public static boolean available() {
		return !ids.isEmpty();
	}

	/** The sound to play for {@code id}: its CS:GO version when that style is chosen and the pack has one, else itself. */
	public static Identifier redirect(Identifier id) {
		if (CcOptions.weaponSoundStyle() == CcOptions.WeaponSoundStyle.CSGO && id.getNamespace().equals(CounterCraft.MOD_ID)
				&& ids.contains(id.getPath())) {
			return CounterCraft.id("csgo." + id.getPath());
		}
		return id;
	}
}
