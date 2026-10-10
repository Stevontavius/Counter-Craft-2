package dev.countercraft.movement;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * CS2 footstep surfaces (the soundevent families CT_/T_/Land_&lt;surface&gt;.StepLeft converted from the user's
 * install) and which one a Minecraft block sounds like. CS2 takes the surface from the map material; Minecraft's
 * nearest equivalent is the block's sound type (dirt is "gravel" in Minecraft, so it's checked by tag first).
 */
public final class Surfaces {
	public static final String[] NAMES = {"Concrete", "Dirt", "Grass", "Gravel", "Sand", "Snow", "Wood", "SolidMetal", "MetalGrate",
			"Glass", "Carpet", "Mud", "Rubber", "Tile", "Default"};
	private static final int CONCRETE = 0, DIRT = 1, GRASS = 2, GRAVEL = 3, SAND = 4, SNOW = 5, WOOD = 6, METAL = 7, GRATE = 8,
			GLASS = 9, CARPET = 10, MUD = 11, RUBBER = 12, TILE = 13, DEFAULT = 14;

	private Surfaces() {
	}

	public static String name(int i) {
		return NAMES[i >= 0 && i < NAMES.length ? i : CONCRETE];
	}

	public static int of(BlockState state) {
		if (state.isAir()) {
			return DEFAULT;
		}
		SoundType t = state.getSoundType();
		if (t == SoundType.GRASS || t == SoundType.WET_GRASS || t == SoundType.MOSS || t == SoundType.MOSS_CARPET || t == SoundType.AZALEA
				|| t == SoundType.FLOWERING_AZALEA || t == SoundType.AZALEA_LEAVES || t == SoundType.CROP || t == SoundType.LEAF_LITTER) {
			return GRASS;
		}
		if (state.is(BlockTags.DIRT) || t == SoundType.ROOTED_DIRT) {
			return DIRT;
		}
		if (t == SoundType.GRAVEL) {
			return GRAVEL;
		}
		if (t == SoundType.SAND || t == SoundType.SOUL_SAND || t == SoundType.SOUL_SOIL) {
			return SAND;
		}
		if (t == SoundType.SNOW || t == SoundType.POWDER_SNOW) {
			return SNOW;
		}
		if (t == SoundType.WOOD || t == SoundType.NETHER_WOOD || t == SoundType.BAMBOO_WOOD || t == SoundType.CHERRY_WOOD
				|| t == SoundType.SCAFFOLDING || t == SoundType.LADDER || t == SoundType.BAMBOO) {
			return WOOD;
		}
		if (t == SoundType.COPPER_GRATE || t == SoundType.CHAIN) {
			return GRATE;
		}
		if (t == SoundType.METAL || t == SoundType.ANVIL || t == SoundType.NETHERITE_BLOCK || t == SoundType.COPPER || t == SoundType.COPPER_BULB
				|| t == SoundType.LANTERN || t == SoundType.LODESTONE || t == SoundType.HEAVY_CORE || t == SoundType.VAULT) {
			return METAL;
		}
		if (t == SoundType.GLASS || t == SoundType.AMETHYST) {
			return GLASS;
		}
		if (t == SoundType.WOOL) {
			return CARPET;
		}
		if (t == SoundType.MUD || t == SoundType.MUDDY_MANGROVE_ROOTS) {
			return MUD;
		}
		if (t == SoundType.SLIME_BLOCK || t == SoundType.HONEY_BLOCK) {
			return RUBBER;
		}
		if (t == SoundType.CALCITE || t == SoundType.DECORATED_POT || t == SoundType.POLISHED_TUFF || t == SoundType.TUFF_BRICKS) {
			return TILE;
		}
		return CONCRETE;
	}
}
