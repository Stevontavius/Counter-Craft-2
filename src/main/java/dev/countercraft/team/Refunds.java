package dev.countercraft.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.countercraft.Combat;
import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import dev.countercraft.ServerCvars;
import dev.countercraft.net.RefundPayload;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * CS2's sell back (sv_sellback_enabled, on as in CS2's casual and competitive): what you bought this round comes back
 * at what you paid while it's unused, from the buy menu (right-click an item: "Refund") or all at once ("Refund All",
 * sellbackall: Delete, CS2's default bind). Counter-Craft: only in the buy phase you bought it in; the other modes buy
 * at any time, so they refund at any time. Unused: a gun not fired or dropped, a grenade still carried, armor not hit
 * (the latest armor bought goes back first), a defuse kit still carried; only the buyer gets the money.
 * Approximations: a weapon bought over another doesn't bring the dropped one back (it's on the ground), and Minecraft
 * has no buy zones to stand in.
 */
public final class Refunds {
	/** RefundPayload's item for "Refund All". */
	public static final String ALL = "*";

	/**
	 * On a stack bought this round (ModComponents.BOUGHT): who bought it, the round (Counter-Craft's day; 0 elsewhere),
	 * what was paid and the buy menu item.
	 */
	public record Bought(UUID buyer, long round, int price, String item) {
		public static final Codec<Bought> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.fieldOf("buyer").forGetter(Bought::buyer),
				Codec.LONG.fieldOf("round").forGetter(Bought::round),
				Codec.INT.fieldOf("price").forGetter(Bought::price),
				Codec.STRING.fieldOf("item").forGetter(Bought::item)).apply(i, Bought::new));
		public static final StreamCodec<ByteBuf, Bought> STREAM_CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Bought::buyer,
				ByteBufCodecs.VAR_LONG, Bought::round, ByteBufCodecs.VAR_INT, Bought::price, ByteBufCodecs.STRING_UTF8, Bought::item, Bought::new);
	}

	/** Something the buy menu can refund now, and for how much. */
	public record Entry(String item, int price) {
		static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Entry::item, ByteBufCodecs.VAR_INT,
				Entry::price, Entry::new);
	}

	/** Armor or a defuse kit bought this round, with the armor it replaced (a refund puts that back). */
	private record Gear(String item, int price, long round, float armorBefore, boolean helmetBefore) {
		boolean armor() {
			return !item.equals("item_defuser");
		}
	}

	/** What the player can refund now (their own buy menu shows it), refreshed twice a second and on every change. */
	public static final AttachmentType<List<Entry>> REFUNDABLE = AttachmentRegistry.<List<Entry>>builder()
			.syncWith(Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("refundable"));
	private static final Map<UUID, List<Gear>> GEAR = new ConcurrentHashMap<>();

	private Refunds() {
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(RefundPayload.TYPE, RefundPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RefundPayload.TYPE, (payload, context) -> {
			ServerPlayer p = context.player();
			boolean any = ALL.equals(payload.item()) ? refundAll(p) > 0 : refund(p, payload.item());
			p.level().playSound(null, p.getX(), p.getY(), p.getZ(), any ? SoundEvents.ITEM_PICKUP : SoundEvents.VILLAGER_NO, SoundSource.PLAYERS,
					0.4f, any ? 0.9f : 1.4f);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 == 0) {
				for (ServerPlayer p : server.getPlayerList().getPlayers()) {
					sync(p);
				}
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> GEAR.remove(entity.getUUID()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GEAR.remove(handler.getPlayer().getUUID()));
	}

	/** The current round: Counter-Craft's day (its buy phase is the buy time), 0 in the modes that always buy. */
	private static long round(ServerPlayer p) {
		return p.level().getLevelData().isHardcore() ? Math.floorDiv(p.level().getServer().overworld().getOverworldClockTime(), DayCycle.DAY) : 0;
	}

	/** Refunds are open: sv_sellback_enabled, alive and playing, and (Counter-Craft) in the buy phase. */
	private static boolean open(ServerPlayer p) {
		return ServerCvars.get("sv_sellback_enabled") != 0 && p.isAlive() && !p.isSpectator()
				&& (!p.level().getLevelData().isHardcore() || DayCycle.buyPhase(p.level()));
	}

	/** A weapon or grenade as bought (Buying): stamped with the buyer, the round, the price and the menu item. */
	static ItemStack stamp(ServerPlayer p, ItemStack stack, int price, String item) {
		stack.set(ModComponents.BOUGHT, new Bought(p.getUUID(), round(p), price, item));
		return stack;
	}

	/** Armor or a defuse kit was bought (Buying), over this armor. */
	static void boughtGear(ServerPlayer p, String item, int price, float armorBefore, boolean helmetBefore) {
		GEAR.computeIfAbsent(p.getUUID(), u -> new ArrayList<>()).add(new Gear(item, price, round(p), armorBefore, helmetBefore));
		sync(p);
	}

	/** Bought armor is unused while it's still the full 100 it came with (and, for the helmet, still there). */
	private static boolean unused(ServerPlayer p, Gear g, Gear latestArmor) {
		if (!g.armor()) {
			return Combat.hasDefuser(p);
		}
		return g == latestArmor && Combat.armorOf(p) >= 100f && (!g.item().equals("item_assaultsuit") || Combat.hasHelmet(p));
	}

	private static Gear latestArmor(List<Gear> gear) {
		Gear last = null;
		for (Gear g : gear) {
			if (g.armor()) {
				last = g;
			}
		}
		return last;
	}

	public static List<Entry> refundable(ServerPlayer p) {
		if (!open(p)) {
			return List.of();
		}
		long round = round(p);
		List<Entry> out = new ArrayList<>();
		for (ItemStack s : CsInventory.of(p).items) {
			Bought b = s.get(ModComponents.BOUGHT);
			if (b != null && b.buyer().equals(p.getUUID()) && b.round() == round) {
				out.add(new Entry(b.item(), b.price()));
			}
		}
		List<Gear> gear = GEAR.getOrDefault(p.getUUID(), List.of());
		Gear latest = latestArmor(gear);
		for (Gear g : gear) {
			if (g.round() == round && unused(p, g, latest)) {
				out.add(new Entry(g.item(), g.price()));
			}
		}
		return out;
	}

	/** Refund one {@code item} (the last one bought, for a second flashbang); false if there's none to refund. */
	public static boolean refund(ServerPlayer p, String item) {
		if (!open(p)) {
			return false;
		}
		long round = round(p);
		CsInventory.Data d = CsInventory.of(p);
		for (int i = d.items.size() - 1; i >= 0; i--) {
			Bought b = d.get(i).get(ModComponents.BOUGHT);
			if (b != null && b.item().equals(item) && b.buyer().equals(p.getUUID()) && b.round() == round) {
				d.items.set(i, ItemStack.EMPTY);
				CsInventory.markDirty(p);
				Economy.refund(p, b.price());
				sync(p);
				return true;
			}
		}
		List<Gear> gear = GEAR.getOrDefault(p.getUUID(), List.of());
		Gear latest = latestArmor(gear);
		for (int i = gear.size() - 1; i >= 0; i--) {
			Gear g = gear.get(i);
			if (g.item().equals(item) && g.round() == round && unused(p, g, latest)) {
				if (g.armor()) {
					Combat.setArmor(p, g.armorBefore(), g.helmetBefore());
				} else {
					Combat.takeDefuser(p);
				}
				gear.remove(i);
				Economy.refund(p, g.price());
				sync(p);
				return true;
			}
		}
		return false;
	}

	/** Refund everything refundable (CS2's sellbackall); how many items went back. */
	public static int refundAll(ServerPlayer p) {
		int n = 0;
		for (int guard = 0; guard < 32; guard++) {
			List<Entry> left = refundable(p);
			if (left.isEmpty() || !refund(p, left.getLast().item())) {
				break;
			}
			n++;
		}
		return n;
	}

	/** Fired, or out of the player's hands: no longer as bought. */
	public static void used(ItemStack stack) {
		if (stack.has(ModComponents.BOUGHT)) {
			stack.remove(ModComponents.BOUGHT);
		}
	}

	private static void sync(ServerPlayer p) {
		List<Gear> gear = GEAR.get(p.getUUID());
		if (gear != null) {
			// what can never come back: another round's, armor that's been hit, a kit that's gone
			long round = round(p);
			gear.removeIf(g -> g.round() != round || (g.armor() ? Combat.armorOf(p) < 100f : !Combat.hasDefuser(p)));
		}
		List<Entry> now = refundable(p);
		if (!Objects.equals(now, Objects.requireNonNullElse(p.getAttached(REFUNDABLE), List.of()))) {
			p.setAttached(REFUNDABLE, now.isEmpty() ? null : now);
		}
	}
}
