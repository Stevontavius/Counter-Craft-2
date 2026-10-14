package dev.countercraft.client.steam;

import dev.countercraft.client.loadout.LoadoutConfig;
import dev.countercraft.client.skins.SkinsConfig;
import dev.countercraft.client.skins.SteamGrabber;
import net.minecraft.client.Minecraft;

/**
 * The linked Steam account's inventory, copied into Counter-Craft (what Skins Options' Copy Inventory does, for your own
 * account): done automatically when you link, and again by "Refresh inventory", which first resets the copy so skins
 * sold or traded away since are gone and new ones are in.
 */
public final class LinkedInventory {
	private LinkedInventory() {
	}

	/** Copy the linked account's public CS2 inventory ({@code reset}: forget the old copy first). */
	public static void copy(boolean reset) {
		String id = SteamLinkClient.steamId();
		if (id == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		SteamLinkClient.setMessage("Reading your CS2 inventory from Steam...");
		SteamGrabber.fetch(id).whenComplete((p, err) -> mc.execute(() -> {
			if (err != null || p == null) {
				Throwable c = err != null && err.getCause() != null ? err.getCause() : err;
				SteamLinkClient.setMessage(c instanceof SteamGrabber.PrivateProfileException
						? "Your Steam profile is private: make it public to copy your inventory."
						: "Couldn't read your inventory from Steam" + (c != null ? " (" + c.getMessage() + ")" : "") + ". Try again in a minute.");
				return;
			}
			if (p.inventoryPrivate()) {
				SteamLinkClient.setMessage("Your CS2 inventory is private: make it public on Steam to copy it (and for the Genuine Skins tick).");
				return;
			}
			if (reset) {
				SkinsConfig.resetOwned();
			}
			var unlocks = SteamGrabber.unlocks(p.items());
			SkinsConfig.setOwned(unlocks, p.name(), p.id64());
			LoadoutConfig.unequipUnowned(); // anything sold or traded away comes off
			LoadoutConfig.sync();
			SteamLinkClient.setMessage((reset ? "Inventory refreshed: " : "Inventory copied: ") + unlocks.size() + " items from " + p.name() + " unlocked. "
					+ dev.countercraft.steam.SteamInventory.TRADE_PROTECTED);
		}));
	}
}
