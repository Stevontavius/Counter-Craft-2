package dev.countercraft.steam;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.Guns;
import dev.countercraft.net.SteamLinkPayload;
import dev.countercraft.net.SteamStatusPayload;
import dev.countercraft.team.Cosmetics;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional Steam linking and the Genuine Skins ✓.
 *
 * <p>Linking: the player signs in through Steam in their browser ({@link SteamOpenId}); this server checks Steam's
 * signed answer itself and remembers the SteamID on the player (this world / server only; nothing secret is stored).
 * Unlinking forgets it.</p>
 *
 * <p>The ✓ is provenance, not rank: it shows when you've linked your Steam account and play with your own inventory
 * (Skinchanger Mode off, and not an inventory copied from someone else). The server still reads the linked account's
 * public CS2 inventory, to tell the player whether it's public. Nobody without it is marked in any way.</p>
 */
public final class GenuineSkins {
	/** The linked SteamID64 (only the player's own client is told). */
	public static final AttachmentType<String> STEAM_ID = AttachmentRegistry.<String>builder()
			.persistent(Codec.STRING)
			.copyOnDeath()
			.syncWith(ByteBufCodecs.STRING_UTF8, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("steam_id"));

	/** Shows the ✓ (everyone sees it). Worked out again on the server, never saved. */
	public static final AttachmentType<Boolean> GENUINE = AttachmentRegistry.<Boolean>builder()
			.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all())
			.buildAndRegister(CounterCraft.id("genuine_skins"));

	/** Players whose client says Skinchanger Mode is on. */
	private static final Set<UUID> SKINCHANGER = ConcurrentHashMap.newKeySet();


	private GenuineSkins() {
	}

	/** Loads the class at mod init (the attachments must be registered before anyone joins). */
	public static void init() {
	}

	public static boolean genuine(net.minecraft.world.entity.player.Player p) {
		return Boolean.TRUE.equals(p.getAttached(GENUINE));
	}

	/** The scoreboard's tick: a linked Steam account, with Skinchanger Mode off. */
	public static boolean linkedWithoutSkinchanger(ServerPlayer p) {
		return p.hasAttached(STEAM_ID) && !SKINCHANGER.contains(p.getUUID());
	}

	/** The client's Skinchanger Mode setting (sent with its loadout). */
	public static void setSkinchanger(ServerPlayer p, boolean on) {
		if (on) {
			SKINCHANGER.add(p.getUUID());
		} else {
			SKINCHANGER.remove(p.getUUID());
		}
		update(p);
	}

	/** A sign-in from the player's browser, via their client: check it with Steam, then link. */
	public static void link(ServerPlayer p, SteamLinkPayload payload) {
		MinecraftServer server = p.level().getServer();
		UUID who = p.getUUID();
		SteamOpenId.verify(payload.params()).thenAccept(id -> server.execute(() -> {
			ServerPlayer now = server.getPlayerList().getPlayer(who);
			if (now == null) {
				return;
			}
			if (id.isEmpty()) {
				status(now, "Steam didn't confirm that sign-in. Nothing was linked.");
				return;
			}
			now.setAttached(STEAM_ID, id.get());
			CounterCraft.LOG.info("[countercraft] {} linked Steam account {}", now.getName().getString(), id.get());
			status(now, "Linked to Steam account " + id.get() + ".");
			refresh(now, true);
		}));
	}

	/**
	 * On join, the client's own link (made from the menus, checked with Steam then). Only the player's own singleplayer
	 * world takes it as is: the world runs on their computer, from the client that checked it. Any other server needs a
	 * sign-in there, so nobody can claim someone else's account.
	 */
	public static void claim(ServerPlayer p, String steamId) {
		String current = p.getAttached(STEAM_ID);
		MinecraftServer server = p.level().getServer();
		if (!server.isSingleplayerOwner(p.nameAndId())) {
			if (steamId != null && !steamId.isEmpty() && !steamId.equals(current)) {
				status(p, "This server hasn't seen your Steam sign-in yet: sign in through Steam here to show your ✓.");
			}
			return;
		}
		if (steamId == null || steamId.isEmpty()) {
			if (current != null) {
				p.removeAttached(STEAM_ID);
				p.removeAttached(GENUINE);
				status(p, null);
			}
			return;
		}
		if (!steamId.matches("7656119[0-9]{10}")) {
			return;
		}
		if (!steamId.equals(current)) {
			p.setAttached(STEAM_ID, steamId);
			CounterCraft.LOG.info("[countercraft] {} uses their linked Steam account {} in this world", p.getName().getString(), steamId);
		}
		status(p, null);
		refresh(p, !steamId.equals(current));
	}

	/** When each player last asked for their inventory to be read again (at most every 30 seconds, to spare Steam). */
	private static final Map<UUID, Long> REFRESHED = new ConcurrentHashMap<>();

	/** "Refresh inventory" on the Steam Account screen. */
	public static void refreshRequested(ServerPlayer p) {
		String id = p.getAttached(STEAM_ID);
		if (id == null) {
			status(p, "No Steam account linked in this world: sign in through Steam here first.");
			return;
		}
		long now = System.currentTimeMillis();
		Long last = REFRESHED.get(p.getUUID());
		if (last != null && now - last < 30_000) {
			status(p, "Inventory read " + (now - last) / 1000 + " s ago: try again in " + (30 - (now - last) / 1000) + " s.");
			return;
		}
		REFRESHED.put(p.getUUID(), now);
		MinecraftServer server = p.level().getServer();
		UUID who = p.getUUID();
		SteamInventory.get(id, true).thenAccept(o -> server.execute(() -> {
			ServerPlayer again = server.getPlayerList().getPlayer(who);
			if (again != null) {
				update(again);
				status(again, switch (o.status()) {
					case OK -> "Inventory read: " + o.names().size() + " different items." + (genuine(again) ? " Genuine Skins \u2713 on." : "");
					case PRIVATE -> "Your CS2 inventory is private: make it public on Steam for the Genuine Skins tick.";
					case UNAVAILABLE -> "Steam didn't answer: try again in a minute.";
				});
			}
		}));
	}

	public static void unlink(ServerPlayer p) {
		p.removeAttached(STEAM_ID);
		p.removeAttached(GENUINE);
		status(p, "Steam account unlinked. CC2 no longer reads anything from it.");
	}

	/** Joined: their inventory is read again (it may have changed). */
	public static void join(ServerPlayer p) {
		status(p, null);
		refresh(p, false);
	}

	public static void leave(ServerPlayer p) {
		SKINCHANGER.remove(p.getUUID());
	}

	/** Every server tick: re-read inventories now and then, keep each ✓ in step with what's equipped. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 100 != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			refresh(p, false);
		}
	}

	/** Get the inventory (cached unless {@code force} or stale), then update the ✓. */
	private static void refresh(ServerPlayer p, boolean force) {
		String id = p.getAttached(STEAM_ID);
		if (id == null) {
			update(p);
			return;
		}
		MinecraftServer server = p.level().getServer();
		UUID who = p.getUUID();
		SteamInventory.get(id, force).thenAccept(o -> server.execute(() -> {
			ServerPlayer now = server.getPlayerList().getPlayer(who);
			if (now != null) {
				update(now);
				if (force) {
					status(now, null); // the link screen shows the inventory's state (public / private)
				}
			}
		}));
		update(p);
	}

	/** Work the ✓ out again from what's known now. */
	public static void update(ServerPlayer p) {
		boolean g = check(p);
		if (g != genuine(p)) {
			if (g) {
				p.setAttached(GENUINE, true);
			} else {
				p.removeAttached(GENUINE);
			}
		}
	}

	/**
	 * The ✓: a linked Steam account (checked with Steam by this server) with Skinchanger Mode off. The client also turns
	 * it off while the unlocked inventory was copied from another account (sent as Skinchanger Mode).
	 */
	private static boolean check(ServerPlayer p) {
		return p.getAttached(STEAM_ID) != null && !SKINCHANGER.contains(p.getUUID());
	}

	/** Tell the player's client where its link stands (a message for the link screen, or null). */
	private static void status(ServerPlayer p, String message) {
		if (!ServerPlayNetworking.canSend(p, SteamStatusPayload.TYPE)) {
			return;
		}
		String id = p.getAttached(STEAM_ID);
		SteamInventory.Owned o = id == null ? null : SteamInventory.cached(id);
		String inv = o == null ? "" : o.status().name();
		ServerPlayNetworking.send(p, new SteamStatusPayload(id == null ? "" : id, inv, message == null ? "" : message));
	}
}
