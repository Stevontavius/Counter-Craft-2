package dev.countercraft.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/**
 * The muzzle flash's light (CS2's flash systems carry a short-lived dynamic light, "fakelight"). Minecraft has no dynamic
 * lights, so this adds block light around the muzzle while a gun fires: blocks get it when their chunk sections are
 * rebuilt (marked dirty when the light comes, moves and goes; mixins on the block and entity light lookups), entities
 * and the viewmodel at once. Sustained fire keeps one light on rather than flickering per shot, since every change costs
 * a section rebuild. Level and reach are set by eye (approximated: CS2's light values live in game code).
 */
public final class MuzzleLight {
	/** Block light at the muzzle; it falls off by {@link #FALLOFF} per block. */
	private static final int LEVEL = 13;
	private static final double FALLOFF = 2.0;
	private static final double RADIUS = LEVEL / FALLOFF;
	/** How long the light stays after the last shot (seconds): long enough for the rebuilt sections to show it. */
	private static final double HOLD = 0.12;

	private record Light(double x, double y, double z) {
	}

	private static volatile Light light;
	private static long until;

	private MuzzleLight() {
	}

	/** A shot at {@code pos} (world). */
	public static void flash(Vec3 pos) {
		Light old = light;
		until = System.nanoTime() + (long) (HOLD * 1e9);
		if (old != null && Math.abs(old.x - pos.x) < 1 && Math.abs(old.y - pos.y) < 1 && Math.abs(old.z - pos.z) < 1) {
			return;
		}
		light = new Light(pos.x, pos.y, pos.z);
		if (old != null) {
			dirty(old);
		}
		dirty(light);
	}

	/** Render thread, every frame: put the light out once firing stops. */
	public static void frame() {
		Light l = light;
		if (l != null && System.nanoTime() > until) {
			light = null;
			dirty(l);
		}
	}

	/** Block light (0-15) the flash adds at {@code pos}. Called from chunk-building threads. */
	public static int at(BlockPos pos) {
		Light l = light;
		if (l == null) {
			return 0;
		}
		double dx = pos.getX() + 0.5 - l.x;
		double dy = pos.getY() + 0.5 - l.y;
		double dz = pos.getZ() + 0.5 - l.z;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		return d >= RADIUS ? 0 : (int) Math.round(LEVEL - d * FALLOFF);
	}

	/** {@code packed} light coords with the flash's block light mixed in. */
	public static int apply(int packed, BlockPos pos) {
		int add = at(pos);
		if (add <= 0 || LightCoordsUtil.block(packed) >= add) {
			return packed;
		}
		return LightCoordsUtil.withBlock(packed, add);
	}

	private static void dirty(Light l) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.levelExtractor == null) {
			return;
		}
		int r = (int) Math.ceil(RADIUS);
		int x0 = SectionPos.blockToSectionCoord((int) Math.floor(l.x - r));
		int x1 = SectionPos.blockToSectionCoord((int) Math.floor(l.x + r));
		int y0 = SectionPos.blockToSectionCoord((int) Math.floor(l.y - r));
		int y1 = SectionPos.blockToSectionCoord((int) Math.floor(l.y + r));
		int z0 = SectionPos.blockToSectionCoord((int) Math.floor(l.z - r));
		int z1 = SectionPos.blockToSectionCoord((int) Math.floor(l.z + r));
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					mc.levelExtractor.setSectionDirty(x, y, z);
				}
			}
		}
	}
}
