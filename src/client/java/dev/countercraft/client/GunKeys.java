package dev.countercraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.countercraft.CounterCraft;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Counter-Craft keybinds (Options > Controls > Counter-Craft 2). Fire shares the left mouse button with vanilla
 * Attack on purpose: 26.3 lets several mappings share a key, and vanilla attacking is blocked while a gun is held.
 */
public final class GunKeys {
	private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(CounterCraft.id("weapons"));

	public static final KeyMapping FIRE = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.fire", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_LEFT, CATEGORY));
	/** Scope, burst toggle, silencer, or R8 fan fire (CS2's secondary attack). */
	public static final KeyMapping ALT_FIRE = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.alt_fire", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_RIGHT, CATEGORY));
	public static final KeyMapping RELOAD = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.reload", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY));
	public static final KeyMapping INSPECT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.inspect", InputConstants.Type.KEYBOARD, InputConstants.KEY_F, CATEGORY));

	/** Switch between CS weapons (CS slots, CS2 weapon list) and the Minecraft hotbar. */
	public static final KeyMapping CS_MODE = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.cs_mode", InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT, CATEGORY));

	/** CS2 drop (G, like CS2): toss the CS weapon in hand. CS hotbar only (Minecraft's Drop, Q, drops in the hotbar). */
	public static final KeyMapping DROP = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.drop", InputConstants.Type.KEYBOARD, InputConstants.KEY_G, CATEGORY));
	/**
	 * CS2 lastinv (Q, like CS2): back to the weapon held before. CS hotbar only; it shares Q with Minecraft's Drop, which
	 * the CS hotbar ignores (G drops there) and the Minecraft hotbar keeps.
	 */
	public static final KeyMapping LAST_WEAPON = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.lastinv", InputConstants.Type.KEYBOARD, InputConstants.KEY_Q, CATEGORY));
	/** CS2 buy menu (B, like CS2). */
	public static final KeyMapping BUY = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.buy", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY));
	/** CS2's sellbackall (Delete, CS2's default bind): refund everything bought this round (Refunds). */
	public static final KeyMapping SELL_BACK_ALL = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.sellbackall", InputConstants.Type.KEYBOARD, InputConstants.KEY_DELETE, CATEGORY));
	/** CS2 team select (M, like CS2). */
	public static final KeyMapping TEAM = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.team", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, CATEGORY));

	/** CS2 crosshair editor (unbound by default; also /cs_crosshair). */
	public static final KeyMapping CROSSHAIR = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.crosshair", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));

	/** Draw the hitboxes bullets use (HitboxDebug; unbound by default). */
	public static final KeyMapping DEBUG_HITBOXES = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.debug_hitboxes", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
	/** Grenade tracer (debug): the predicted flight while the pin is pulled, and thrown grenades' trails. Unbound. */
	public static final KeyMapping GRENADE_TRACER = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.grenade_tracer", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), CATEGORY));
	/** CS2 switchhands: the viewmodel to the other hand (cl_righthand). */
	public static final KeyMapping SWITCH_HANDS = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.switch_hands", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CATEGORY));

	private GunKeys() {
	}

	/** Forces class init (and so registration) during client init. */
	static void init() {
	}
}
