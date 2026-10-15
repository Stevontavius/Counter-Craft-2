package dev.countercraft.client.movement;

import com.mojang.blaze3d.platform.InputConstants;
import dev.countercraft.CounterCraft;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * CS2's movement keys (Options > Controls > Counter-Craft 2 Movement): Shift walks, Ctrl crouches, like CS2. While CS
 * movement is on, vanilla Sneak and Sprint are ignored (CS2 has no sprint), so the keys can overlap.
 */
public final class MovementKeys {
	private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(CounterCraft.id("movement"));

	/** CS2 +sprint: walk at 52% speed. */
	public static final KeyMapping WALK = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.walk", InputConstants.Type.KEYBOARD, InputConstants.KEY_LSHIFT, CATEGORY));
	/** CS2 +duck. */
	public static final KeyMapping CROUCH = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.crouch", InputConstants.Type.KEYBOARD, InputConstants.KEY_LCONTROL, CATEGORY));
	/** Toggles the server's sv_autobunnyhopping (operators; unbound by default; /sv_autobunnyhopping works too). */
	public static final KeyMapping AUTO_BHOP = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.auto_bhop", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));

	private MovementKeys() {
	}

	public static void init() {
	}
}
