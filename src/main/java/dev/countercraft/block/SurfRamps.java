package dev.countercraft.block;

import dev.countercraft.CounterCraft;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * One surf ramp for each vanilla stair (countercraft:&lt;stair without "_stairs"&gt;_surf_ramp), with the stair's
 * properties (hardness, tool, sound, map colour): crafted from that stair and a feather; listed after its stair in the
 * Building Blocks tab. Models, recipes, loot and tags come from scripts/gen_surf_ramps.py, which reads the same list.
 */
public final class SurfRamps {
	/** Each ramp and the stair it's made from. */
	public static final Map<Item, Item> FROM = new java.util.LinkedHashMap<>();

	private SurfRamps() {
	}

	public static void init() {
		List<Map.Entry<ResourceKey<Block>, Block>> stairs = new ArrayList<>();
		for (Map.Entry<ResourceKey<Block>, Block> e : BuiltInRegistries.BLOCK.entrySet()) {
			String path = e.getKey().identifier().getPath();
			if (e.getKey().identifier().getNamespace().equals("minecraft") && path.endsWith("_stairs") && e.getValue() instanceof StairBlock) {
				stairs.add(e);
			}
		}
		stairs.sort(Comparator.comparing(e -> e.getKey().identifier().getPath()));
		for (Map.Entry<ResourceKey<Block>, Block> e : stairs) {
			String path = e.getKey().identifier().getPath();
			String id = path.substring(0, path.length() - "_stairs".length()) + "_surf_ramp";
			ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, CounterCraft.id(id));
			Block ramp = Registry.register(BuiltInRegistries.BLOCK, key, new SurfRampBlock(BlockBehaviour.Properties.ofFullCopy(e.getValue()).setId(key)));
			ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, CounterCraft.id(id));
			Item item = Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(ramp, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
			FROM.put(item, e.getValue().asItem());
		}
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> {
			for (Map.Entry<Item, Item> r : FROM.entrySet()) {
				entries.insertAfter(r.getValue(), r.getKey());
			}
		});
		CounterCraft.LOG.info("[countercraft] {} surf ramps", FROM.size());
		// CS2 takes no fall damage on surf ramps: CS movement never stands on one (it slides), so a fall onto a ramp, and
		// the slide down it, would still add up to fall damage when you reach the ground. Touching a ramp ends the fall.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (var p : server.getPlayerList().getPlayers()) {
				if (p.fallDistance > 0 && touchesRamp(p)) {
					p.resetFallDistance();
				}
			}
		});
	}

	/** Within a tenth of a block of a surf ramp's block. */
	public static boolean touchesRamp(net.minecraft.world.entity.Entity e) {
		net.minecraft.world.phys.AABB box = e.getBoundingBox().inflate(0.1);
		for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(net.minecraft.util.Mth.floor(box.minX),
				net.minecraft.util.Mth.floor(box.minY), net.minecraft.util.Mth.floor(box.minZ), net.minecraft.util.Mth.floor(box.maxX),
				net.minecraft.util.Mth.floor(box.maxY), net.minecraft.util.Mth.floor(box.maxZ))) {
			if (e.level().getBlockState(pos).getBlock() instanceof SurfRampBlock) {
				return true;
			}
		}
		return false;
	}
}
