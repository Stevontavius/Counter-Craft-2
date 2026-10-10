package dev.countercraft.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import dev.countercraft.gun.KnifeItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The CS2 side of a player's inventory: 8 slots (CS2's weapon slots: 0 primary, 1 pistol, 2 knife, 3-6 grenades,
 * 7 Zeus) beside the Minecraft inventory, and whether the player is in CS mode. In CS mode the hand holds the selected
 * CS slot instead of the hotbar (see the Inventory mixin): number keys / scroll pick CS slots, CS2's weapon list
 * replaces the hotbar. The knife slot always holds the team's knife (set by the loadout, never moved by hand).
 *
 * <p>Saved on the player and synced to that player's client. Guns change their stacks in place every shot (ammo,
 * next shot time), so the server re-syncs whenever a slot's contents change ({@link #tick}).</p>
 */
public final class CsInventory {
	public static final int PRIMARY = 0;
	public static final int SECONDARY = 1;
	public static final int KNIFE = 2;
	public static final int GRENADE0 = 3;
	public static final int GRENADES = 4;
	public static final int ZEUS = 7;
	public static final int SIZE = 8;

	/** The slots and the mode. Mutable on purpose: stacks are changed in place like the vanilla inventory's. */
	public static final class Data {
		public final List<ItemStack> items;
		public boolean csMode;
		/** The Minecraft hotbar slot to return to when leaving CS mode. */
		public int mcSelected;

		public Data(List<ItemStack> items, boolean csMode, int mcSelected) {
			this.items = new ArrayList<>(items);
			while (this.items.size() < SIZE) {
				this.items.add(ItemStack.EMPTY);
			}
			this.csMode = csMode;
			this.mcSelected = mcSelected;
		}

		public Data copy() {
			List<ItemStack> c = new ArrayList<>(SIZE);
			for (ItemStack s : items) {
				c.add(s.copy());
			}
			return new Data(c, csMode, mcSelected);
		}

		public ItemStack get(int slot) {
			return slot >= 0 && slot < SIZE ? items.get(slot) : ItemStack.EMPTY;
		}
	}

	private static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
			ItemStack.OPTIONAL_CODEC.listOf().fieldOf("items").forGetter(d -> d.items),
			Codec.BOOL.fieldOf("cs_mode").forGetter(d -> d.csMode),
			Codec.INT.optionalFieldOf("mc_selected", 0).forGetter(d -> d.mcSelected)).apply(i, Data::new));

	private static final StreamCodec<RegistryFriendlyByteBuf, Data> STREAM_CODEC = StreamCodec.composite(
			ItemStack.OPTIONAL_LIST_STREAM_CODEC, d -> d.items,
			ByteBufCodecs.BOOL, d -> d.csMode,
			ByteBufCodecs.VAR_INT, d -> d.mcSelected,
			Data::new);

	public static final AttachmentType<Data> DATA = AttachmentRegistry.<Data>builder()
			.persistent(CODEC)
			.copyOnDeath()
			.syncWith(STREAM_CODEC, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("cs_inventory"));

	/** Last synced contents per player (to notice in-place changes). */
	private static final Map<UUID, Integer> SYNCED = new ConcurrentHashMap<>();

	private CsInventory() {
	}

	public static void init() {
	}

	/** The player's CS slots (created empty if missing). */
	public static Data of(Player p) {
		Data d = p.getAttached(DATA);
		if (d == null) {
			d = new Data(List.of(), false, 0);
			if (!p.level().isClientSide()) {
				p.setAttached(DATA, d); // the client waits for the server's copy
			}
		}
		return d;
	}

	/** In CS mode (the hand holds the selected CS slot). */
	public static boolean active(Player p) {
		Data d = p.getAttached(DATA);
		return d != null && d.csMode;
	}

	/** Which CS slot an item belongs in, or -1. Grenades: the first of 3-6. */
	public static int slotFor(ItemStack stack) {
		if (stack.getItem() instanceof GunItem g) {
			return switch (g.spec().slot()) {
				case "primary" -> PRIMARY;
				case "secondary" -> SECONDARY;
				default -> ZEUS;
			};
		}
		if (stack.getItem() instanceof GrenadeItem) {
			return GRENADE0;
		}
		if (stack.getItem() instanceof KnifeItem) {
			return KNIFE;
		}
		return -1;
	}

	/** Whether a player may put this stack in CS slot {@code slot} by hand (the knife slot is locked). */
	public static boolean mayPlace(int slot, ItemStack stack) {
		int want = slotFor(stack);
		if (want < 0 || slot == KNIFE) {
			return false;
		}
		return want == GRENADE0 ? slot >= GRENADE0 && slot < GRENADE0 + GRENADES : want == slot;
	}

	/** The team's default knife (CS2: knife_t / knife_ct). */
	public static ItemStack knifeFor(String team) {
		KnifeItem k = CounterCraft.KNIVES.get(Teams.CT.equals(team) ? "knife_ct" : "knife_t");
		return k == null ? ItemStack.EMPTY : new ItemStack(k);
	}

	/** The player's knife: the loadout's knife and finish for their team, else the team's default. */
	public static ItemStack knifeFor(Player p) {
		Cosmetics.Side side = Cosmetics.of(p);
		KnifeItem k = side.knife().isEmpty() ? null : CounterCraft.KNIVES.get(side.knife());
		if (k == null) {
			return knifeFor(Teams.of(p));
		}
		ItemStack s = new ItemStack(k);
		if (side.knifeSkin() > 0) {
			s.set(dev.countercraft.ModComponents.SKIN, side.knifeSkin());
		}
		return s;
	}

	/** Put the player's knife in the knife slot. */
	public static void refreshKnife(Player p) {
		Data d = of(p);
		ItemStack want = knifeFor(p);
		ItemStack have = d.get(KNIFE);
		if (!ItemStack.isSameItem(have, want) || !java.util.Objects.equals(have.get(dev.countercraft.ModComponents.SKIN),
				want.get(dev.countercraft.ModComponents.SKIN))) {
			d.items.set(KNIFE, want);
			markDirty(p);
		}
	}

	public static void markDirty(Player p) {
		SYNCED.remove(p.getUUID());
	}

	/** Switch between CS mode and the Minecraft hotbar. */
	public static void toggle(ServerPlayer p) {
		Data d = of(p);
		refreshKnife(p);
		var inv = p.getInventory();
		if (d.csMode) {
			d.csMode = false;
			inv.setSelectedSlot(Math.max(0, Math.min(8, d.mcSelected)));
		} else {
			d.mcSelected = inv.getSelectedSlot();
			d.csMode = true;
			int sel = !d.get(PRIMARY).isEmpty() ? PRIMARY : !d.get(SECONDARY).isEmpty() ? SECONDARY : KNIFE;
			inv.setSelectedSlot(sel);
		}
		p.setAttached(DATA, d.copy());
		SYNCED.put(p.getUUID(), signature(d));
		p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(inv.getSelectedSlot()));
	}

	/** End of server tick: re-sync players whose CS slots changed (in place or not). */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			HotbarRule.sweep(p); // CS weapons never stay in the hotbar / off-hand
			Data d = p.getAttached(DATA);
			if (d == null) {
				continue;
			}
			// CS slots aren't in the vanilla inventory: tick their items here (reload completion, rezoom, Zeus recharge)
			int sel = p.getInventory().getSelectedSlot();
			for (int i = 0; i < SIZE; i++) {
				ItemStack st = d.get(i);
				if (!st.isEmpty()) {
					st.inventoryTick(p.level(), p, d.csMode && i == sel ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : null);
				}
			}
			int sig = signature(d);
			Integer last = SYNCED.get(p.getUUID());
			if (last == null || last != sig) {
				SYNCED.put(p.getUUID(), sig);
				p.setAttached(DATA, d.copy());
			}
		}
	}

	public static void forget(UUID player) {
		SYNCED.remove(player);
	}

	private static int signature(Data d) {
		int h = d.csMode ? 1 : 0;
		for (ItemStack s : d.items) {
			h = h * 31 + ItemStack.hashItemAndComponents(s) * 7 + s.getCount();
		}
		return h * 31 + d.mcSelected;
	}

	/** Creative inventory: put a stack in (or clear) a CS slot, if it belongs there. Never the knife slot. */
	public static void creativeSet(ServerPlayer p, int slot, ItemStack stack) {
		if (!p.isCreative() || slot < 0 || slot >= SIZE || slot == KNIFE) {
			return;
		}
		if (!stack.isEmpty() && !mayPlace(slot, stack)) {
			markDirty(p); // re-sync so the client drops its prediction
			return;
		}
		ItemStack s = stack.copy();
		if (s.getCount() > 1) {
			s.setCount(1);
		}
		of(p).items.set(slot, s);
		markDirty(p);
	}

	/**
	 * Death, CS2 competitive (gamemode_competitive.cfg): mp_death_drop_gun 1 drops the best gun (primary, else pistol),
	 * mp_death_drop_grenade 2 the grenade in hand, else the best (most expensive); mp_death_drop_taser drops the Zeus
	 * (engine default, not verified). Everything else is lost; the knife stays (it's the loadout's).
	 */
	public static void onDeath(ServerPlayer p) {
		Data d = p.getAttached(DATA);
		if (d == null) {
			return;
		}
		int gun = !d.get(PRIMARY).isEmpty() ? PRIMARY : !d.get(SECONDARY).isEmpty() ? SECONDARY : -1;
		int sel = p.getInventory().getSelectedSlot();
		int nade = -1;
		if (d.csMode && sel >= GRENADE0 && sel < GRENADE0 + GRENADES && !d.get(sel).isEmpty()) {
			nade = sel;
		} else {
			int best = -1;
			for (int i = GRENADE0; i < GRENADE0 + GRENADES; i++) {
				int price = d.get(i).getItem() instanceof GrenadeItem g && dev.countercraft.gun.Guns.GRENADES.containsKey(g.weaponId())
						? dev.countercraft.gun.Guns.GRENADES.get(g.weaponId()).price() : -1;
				if (!d.get(i).isEmpty() && price > best) {
					best = price;
					nade = i;
				}
			}
		}
		for (int i = 0; i < SIZE; i++) {
			if (i == KNIFE || d.get(i).isEmpty()) {
				continue;
			}
			if (i == gun || i == ZEUS) {
				CsDrop.fall(p, d.get(i));
			} else if (i == nade) {
				CsDrop.fall(p, d.get(i).copyWithCount(1));
			}
			d.items.set(i, ItemStack.EMPTY);
		}
		markDirty(p);
	}

	/** Whether {@code stack} fits a free CS slot without swapping anything out (grenades: within CS2's carry limits). */
	public static boolean fits(Player p, ItemStack stack) {
		Data d = of(p);
		int slot = slotFor(stack);
		if (slot < 0 || slot == KNIFE) {
			return false;
		}
		if (slot != GRENADE0) {
			return d.get(slot).isEmpty();
		}
		int same = 0;
		int free = 0;
		for (int i = GRENADE0; i < GRENADE0 + GRENADES; i++) {
			if (ItemStack.isSameItem(d.get(i), stack)) {
				same += d.get(i).getCount();
			} else if (d.get(i).isEmpty()) {
				free++;
			}
		}
		boolean flash = stack.getItem() instanceof GrenadeItem g && "flashbang".equals(g.weaponId());
		return same + stack.getCount() <= (flash ? 2 : 1) && (free > 0 || flash && same == 1);
	}

	/** Put a stack into its free CS slot (a second flashbang joins the first). False if it doesn't fit. */
	public static boolean put(ServerPlayer p, ItemStack stack) {
		if (!fits(p, stack)) {
			return false;
		}
		Data d = of(p);
		int slot = slotFor(stack);
		if (slot == GRENADE0) {
			for (int i = GRENADE0; i < GRENADE0 + GRENADES; i++) {
				if (ItemStack.isSameItem(d.get(i), stack)) {
					d.get(i).grow(stack.getCount());
					markDirty(p);
					return true;
				}
			}
			for (int i = GRENADE0; i < GRENADE0 + GRENADES; i++) {
				if (d.get(i).isEmpty()) {
					d.items.set(i, stack);
					markDirty(p);
					return true;
				}
			}
			return false;
		}
		d.items.set(slot, stack);
		markDirty(p);
		return true;
	}

	/** The CS slot holding an item like {@code stack}, or -1. */
	public static int slotOf(Player p, ItemStack stack) {
		Data d = of(p);
		for (int i = 0; i < SIZE; i++) {
			if (ItemStack.isSameItem(d.get(i), stack)) {
				return i;
			}
		}
		return -1;
	}

	/** After dropping the weapon in hand: switch to the best one left (primary, pistol, knife), like CS2. */
	public static void selectBest(ServerPlayer p) {
		Data d = of(p);
		int sel = !d.get(PRIMARY).isEmpty() ? PRIMARY : !d.get(SECONDARY).isEmpty() ? SECONDARY : KNIFE;
		p.getInventory().setSelectedSlot(sel);
		p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(sel));
	}

	/**
	 * Give a stack to its CS slot: a weapon replaces (drops) the one there, grenades take the first free grenade slot
	 * within CS2's limits (one of each, two flashbangs). False if it doesn't fit.
	 */
	public static boolean give(ServerPlayer p, ItemStack stack) {
		Data d = of(p);
		int slot = slotFor(stack);
		if (slot < 0 || slot == KNIFE) {
			return false;
		}
		if (slot == GRENADE0) {
			int same = 0;
			int free = -1;
			for (int i = GRENADE0; i < GRENADE0 + GRENADES; i++) {
				if (ItemStack.isSameItem(d.get(i), stack)) {
					same += d.get(i).getCount();
				} else if (d.get(i).isEmpty() && free < 0) {
					free = i;
				}
			}
			boolean flash = stack.getItem() instanceof GrenadeItem g && "flashbang".equals(g.weaponId());
			if (same >= (flash ? 2 : 1) || free < 0) {
				return false;
			}
			d.items.set(free, stack);
		} else {
			ItemStack old = d.get(slot);
			if (ItemStack.isSameItem(old, stack)) {
				return false;
			}
			if (!old.isEmpty()) {
				CsDrop.toss(p, old); // CS2: buying over a weapon drops the old one
			}
			d.items.set(slot, stack);
		}
		markDirty(p);
		return true;
	}
}
