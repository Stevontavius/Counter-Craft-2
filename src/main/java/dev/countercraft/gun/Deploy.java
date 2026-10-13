package dev.countercraft.gun;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * CS2 deploy time: after switching to a weapon it can't fire, reload, zoom or be thrown until its draw is over
 * ({@link CsWeapon#deploySeconds}). Tracked per player from the selected hotbar slot and the item in it. Also
 * remembers the previously held slot: after the last grenade of a kind is thrown the next grenade is drawn, else the
 * weapon held before.
 */
public final class Deploy {
	private record Held(int slot, Item item, long readyAt, int previousSlot) {
	}

	/** A slot switch due at a game time (after the throw animation of a player's last grenade). */
	private record Switch(long at, int fromSlot) {
	}

	private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();
	private static final Map<UUID, Switch> SWITCHES = new ConcurrentHashMap<>();

	private Deploy() {
	}

	public static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Switch s = SWITCHES.get(p.getUUID());
			if (s != null && p.level().getGameTime() >= s.at()) {
				SWITCHES.remove(p.getUUID());
				switchBack(p, s.fromSlot());
			}
			update(p);
		}
	}

	/** True once the held weapon's draw has finished. */
	public static boolean ready(ServerPlayer player) {
		return ready(player, 0);
	}

	/** As {@link #ready(ServerPlayer)}, up to {@code leewayTicks} early (a client action timed by its own clock). */
	public static boolean ready(ServerPlayer player, int leewayTicks) {
		return player.level().getGameTime() + leewayTicks >= update(player).readyAt();
	}

	/** Not usable for {@code ticks} more (a grenade being thrown, then the next one drawn). */
	public static void lock(ServerPlayer player, long ticks) {
		Held h = update(player);
		HELD.put(player.getUUID(), new Held(h.slot(), h.item(), Math.max(h.readyAt(), player.level().getGameTime() + ticks), h.previousSlot()));
	}

	/** After {@code ticks}, if the slot is still empty, select the weapon held before (CS2 lastinv). */
	public static void switchBackLater(ServerPlayer player, long ticks) {
		SWITCHES.put(player.getUUID(), new Switch(player.level().getGameTime() + ticks, player.getInventory().getSelectedSlot()));
	}

	private static void switchBack(ServerPlayer p, int fromSlot) {
		if (p.getInventory().getSelectedSlot() != fromSlot || !p.getMainHandItem().isEmpty()) {
			return; // the player already picked something else
		}
		Held h = HELD.get(p.getUUID());
		int previous = h != null ? h.previousSlot() : -1;
		int target = dev.countercraft.team.CsInventory.active(p) ? nextCsItem(p, fromSlot, previous) : nextHotbarWeapon(p, fromSlot, previous);
		if (target >= 0) {
			p.getInventory().setSelectedSlot(target);
			p.connection.send(new ClientboundSetHeldSlotPacket(target));
		}
	}

	/** CS mode: the next grenade after the thrown one (wrapping), else the weapon held before, else the best one left. */
	private static int nextCsItem(ServerPlayer p, int fromSlot, int previous) {
		var d = dev.countercraft.team.CsInventory.of(p);
		int g0 = dev.countercraft.team.CsInventory.GRENADE0;
		int n = dev.countercraft.team.CsInventory.GRENADES;
		if (fromSlot >= g0 && fromSlot < g0 + n) {
			for (int k = 1; k < n; k++) {
				int i = g0 + (fromSlot - g0 + k) % n;
				if (!d.get(i).isEmpty()) {
					return i;
				}
			}
		}
		if (previous >= 0 && previous < dev.countercraft.team.CsInventory.SIZE && previous != fromSlot && !d.get(previous).isEmpty()) {
			return previous;
		}
		for (int i : new int[] {dev.countercraft.team.CsInventory.PRIMARY, dev.countercraft.team.CsInventory.SECONDARY, dev.countercraft.team.CsInventory.KNIFE}) {
			if (!d.get(i).isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	/** Hotbar: another grenade, else the slot held before, else the first CS weapon. */
	private static int nextHotbarWeapon(ServerPlayer p, int fromSlot, int previous) {
		for (int k = 1; k < 9; k++) {
			int i = (fromSlot + k) % 9;
			if (p.getInventory().getItem(i).getItem() instanceof GrenadeItem) {
				return i;
			}
		}
		if (previous >= 0 && previous < 9 && previous != fromSlot && !p.getInventory().getItem(previous).isEmpty()) {
			return previous;
		}
		for (int i = 0; i < 9; i++) {
			if (p.getInventory().getItem(i).getItem() instanceof CsWeapon) {
				return i;
			}
		}
		return -1;
	}

	private static Held update(ServerPlayer p) {
		int slot = p.getInventory().getSelectedSlot();
		Item item = p.getMainHandItem().getItem();
		Held h = HELD.get(p.getUUID());
		if (h == null || h.slot() != slot || h.item() != item) {
			float seconds = item instanceof CsWeapon w ? w.deploySeconds() : 0f;
			// the slot held before, for lastinv: only a real slot change (an emptied slot keeps its "previous")
			int previous = h == null ? -1 : (h.slot() != slot ? h.slot() : h.previousSlot());
			// one tick of grace: the client starts its draw a moment before the server hears about the switch
			h = new Held(slot, item, p.level().getGameTime() + Math.max(0, Math.round(seconds * 20f) - 1), previous);
			HELD.put(p.getUUID(), h);
		}
		return h;
	}

	public static void forget(UUID player) {
		HELD.remove(player);
		SWITCHES.remove(player);
	}
}
