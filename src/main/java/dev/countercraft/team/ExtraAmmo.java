package dev.countercraft.team;

import com.mojang.serialization.Codec;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GunItem;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Ammo as a resource (docs/ammo-matrix.html). A gun comes with CS2's magazine and reserve once and never refills by
 * itself. During the buy phase, "Primary Ammo" / "Secondary Ammo" buy one magazine for the gun in that slot, priced by
 * {@link AmmoMatrix}, into a pool per caliber (a 9mm magazine feeds a Glock or an MP9 alike). A gun's reserve plus its
 * caliber's pool can go up to CS2's reserve for that gun plus {@link #EXTRA_MAGS} magazines (machine guns: CS2's
 * reserve only). When a gun's own reserve runs dry, a reload draws on the pool. The pool belongs to the player and is
 * lost on death.
 */
public final class ExtraAmmo {
	public static final String PRIMARY_ITEM = "item_ammo_primary";
	public static final String SECONDARY_ITEM = "item_ammo_secondary";

	/** Rounds per caliber key. */
	public static final AttachmentType<Map<String, Integer>> POOL = AttachmentRegistry.<Map<String, Integer>>builder()
			.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT))
			.syncWith(ByteBufCodecs.<io.netty.buffer.ByteBuf, String, Integer, Map<String, Integer>>map(HashMap::new, ByteBufCodecs.STRING_UTF8,
					ByteBufCodecs.VAR_INT, 32), AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("ammo_pool"));

	private ExtraAmmo() {
	}

	/** Loads the class at mod init ({@link #POOL} must be registered before anyone joins). */
	public static void init() {
	}

	/** The gun slot an ammo item is for ("primary" / "secondary"), or null. */
	public static String slotOf(String item) {
		return PRIMARY_ITEM.equals(item) ? "primary" : SECONDARY_ITEM.equals(item) ? "secondary" : null;
	}

	/** Pooled rounds of a caliber. */
	public static int pool(Player p, String caliber) {
		Map<String, Integer> m = p.getAttached(POOL);
		return m == null || caliber == null ? 0 : m.getOrDefault(caliber, 0);
	}

	/** Spare rounds for this gun beyond its own reserve: its caliber's pool. */
	public static int rounds(Player p, GunItem gun) {
		return gun.spec().exhaustible() ? 0 : pool(p, AmmoMatrix.caliber(gun.spec()));
	}

	/** The gun stack ammo for {@code slot} would go to: the one in hand, else the CS slots, else the inventory. */
	public static ItemStack gunFor(Player p, String slot) {
		ItemStack hand = p.getMainHandItem();
		if (hand.getItem() instanceof GunItem g && slot.equals(g.spec().slot())) {
			return hand;
		}
		CsInventory.Data d = CsInventory.of(p);
		for (int i = 0; i < CsInventory.SIZE; i++) {
			if (d.get(i).getItem() instanceof GunItem g && slot.equals(g.spec().slot())) {
				return d.get(i);
			}
		}
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.getItem() instanceof GunItem g && slot.equals(g.spec().slot())) {
				return s;
			}
		}
		return ItemStack.EMPTY;
	}

	/** What one magazine for {@code slot}'s gun costs this player (0: no gun there to buy for). */
	public static int price(Player p, String slot) {
		return gunFor(p, slot).getItem() instanceof GunItem g ? AmmoMatrix.magPrice(g.spec()) : 0;
	}

	/** Spare magazines a gun may carry beyond CS2's reserve (machine guns: none). */
	public static final int EXTRA_MAGS = 3;

	/** The most reserve this gun may carry: CS2's, plus {@link #EXTRA_MAGS} magazines except for machine guns. */
	public static int maxReserve(dev.countercraft.gun.GunSpec spec) {
		return spec.reserve() + ("machinegun".equals(spec.type()) ? 0 : EXTRA_MAGS * spec.magSize());
	}

	/** Room left for this gun: its maximum reserve minus its own reserve and its caliber's pool. */
	public static int room(Player p, ItemStack stack) {
		if (!(stack.getItem() instanceof GunItem g)) {
			return 0;
		}
		int own = stack.getOrDefault(dev.countercraft.ModComponents.RESERVE, 0);
		return Math.max(0, maxReserve(g.spec()) - own - pool(p, AmmoMatrix.caliber(g.spec())));
	}

	/** Buy one magazine for {@code slot}'s gun (refused with a message if there's no gun or no room). */
	public static boolean buy(ServerPlayer p, String slot) {
		ItemStack stack = gunFor(p, slot);
		if (!(stack.getItem() instanceof GunItem g) || AmmoMatrix.caliber(g.spec()) == null) {
			p.sendOverlayMessage(Component.literal("No " + slot + " weapon to buy ammo for."));
			return false;
		}
		int mag = g.spec().magSize();
		if (room(p, stack) < mag) {
			p.sendOverlayMessage(Component.literal(g.spec().name() + " is carrying all the ammo it can (" + maxReserve(g.spec()) + " in reserve)."));
			return false;
		}
		String cal = AmmoMatrix.caliber(g.spec());
		Map<String, Integer> m = new HashMap<>(p.getAttachedOrElse(POOL, Map.of()));
		m.merge(cal, mag, Integer::sum);
		p.setAttached(POOL, m);
		return true;
	}

	/** Reload with an empty reserve: take up to {@code want} rounds of the gun's caliber from the pool (how many taken). */
	public static int take(ServerPlayer p, GunItem gun, int want) {
		String cal = AmmoMatrix.caliber(gun.spec());
		int have = pool(p, cal);
		int n = Math.min(have, want);
		if (n > 0) {
			Map<String, Integer> m = new HashMap<>(p.getAttachedOrElse(POOL, Map.of()));
			if (have - n > 0) {
				m.put(cal, have - n);
			} else {
				m.remove(cal);
			}
			p.setAttached(POOL, m);
		}
		return n;
	}
}
