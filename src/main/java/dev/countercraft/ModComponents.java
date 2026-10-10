package dev.countercraft;

import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

/** Per-stack gun state, stored on the ItemStack so it survives drops, saves and inventory moves. */
public final class ModComponents {
	/** Bought this round and unused: CS2's sell back ({@link dev.countercraft.team.Refunds}). */
	public static final DataComponentType<dev.countercraft.team.Refunds.Bought> BOUGHT = register("bought",
			new DataComponentType.Builder<dev.countercraft.team.Refunds.Bought>().persistent(dev.countercraft.team.Refunds.Bought.CODEC)
					.networkSynchronized(dev.countercraft.team.Refunds.Bought.STREAM_CODEC).build());
	/** Rounds in the magazine. */
	public static final DataComponentType<Integer> AMMO = register("ammo",
			new DataComponentType.Builder<Integer>().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
	/** Reserve rounds. */
	public static final DataComponentType<Integer> RESERVE = register("reserve",
			new DataComponentType.Builder<Integer>().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
	/** Game time the current reload (or one shotgun shell, or the Zeus recharge) finishes; absent when idle. */
	public static final DataComponentType<Long> RELOAD_END = register("reload_end",
			new DataComponentType.Builder<Long>().persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG).build());
	/**
	 * Game time (fractional ticks) of the next allowed shot. Every component here has a codec: survival inventory clicks
	 * send a hash of each changed stack, and a component without one can't be hashed (the click fails on the client).
	 */
	public static final DataComponentType<Double> NEXT_SHOT = register("next_shot",
			new DataComponentType.Builder<Double>().persistent(Codec.DOUBLE).networkSynchronized(ByteBufCodecs.DOUBLE).build());
	/** Shotgun reload: when it started and how many shells it has loaded (shell times come from the reload clip). */
	public static final DataComponentType<Long> RELOAD_START = register("reload_start",
			new DataComponentType.Builder<Long>().persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG).build());
	public static final DataComponentType<Integer> RELOAD_SHELLS = register("reload_shells",
			new DataComponentType.Builder<Integer>().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
	/** Scope zoom level (0 = unzoomed). */
	public static final DataComponentType<Integer> ZOOM = register("zoom",
			new DataComponentType.Builder<Integer>().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
	/** After a bolt-action shot unzooms: the level to return to and when. */
	public static final DataComponentType<Integer> REZOOM_LEVEL = register("rezoom_level",
			new DataComponentType.Builder<Integer>().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());
	public static final DataComponentType<Long> REZOOM_AT = register("rezoom_at",
			new DataComponentType.Builder<Long>().persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG).build());
	/** Glock-18 / FAMAS burst mode. */
	public static final DataComponentType<Boolean> BURST = register("burst",
			new DataComponentType.Builder<Boolean>().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());
	/** USP-S / M4A1-S with the silencer screwed off. */
	public static final DataComponentType<Boolean> SILENCER_OFF = register("silencer_off",
			new DataComponentType.Builder<Boolean>().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());

	/** CS2 weapon finish: the paint kit id (items_game paint_kits), 0 / absent = vanilla. Travels with the weapon. */
	public static final DataComponentType<Integer> SKIN = register("skin",
			new DataComponentType.Builder<Integer>().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

	private static <T> DataComponentType<T> register(String name, DataComponentType<T> type) {
		return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, CounterCraft.id(name), type);
	}

	static void init() {
	}

	private ModComponents() {
	}
}
