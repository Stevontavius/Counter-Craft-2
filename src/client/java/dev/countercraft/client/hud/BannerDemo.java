package dev.countercraft.client.hud;

import dev.countercraft.CounterCraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;

/**
 * Dev: -Dcountercraft.bannerDemo=true shows each banner in turn after joining (DAY SURVIVED, a challenge MVP, an
 * advancement, GAME OVER) and screenshots its intro and its hold, to look them over.
 */
public final class BannerDemo {
	public static final boolean ON = Boolean.getBoolean("countercraft.bannerDemo");
	private static final String[] NAMES = {"day", "mvp", "personal", "gameover"};
	private static final int[] STARTS = {60, 220, 420, 560};
	/** Screenshots this many ticks into each banner. */
	private static final int[] SHOTS = {3, 6, 12, 40, 90};
	private static int tick;
	private static long gameOverAt = -1;

	private BannerDemo() {
	}

	public static void register() {
		if (!ON) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.gui.screen() != null) {
				return;
			}
			tick++;
			if (tick == STARTS[0]) {
				RoundCycle.onDaySurvived(new dev.countercraft.net.DaySurvivedPayload(12, 1400));
			} else if (tick == STARTS[1]) {
				MvpBanners.challenge(new dev.countercraft.net.MvpPayload("CHALLENGE COMPLETE", mc.player.getName().getString(),
						dev.countercraft.team.Teams.of(mc.player), "Bonk!", "Kill a hostile mob with the impact of a thrown grenade", ""));
			} else if (tick == STARTS[2]) {
				MvpBanners.personal("ADVANCEMENT MADE", "Kobe!", "Kill a hostile mob with an HE grenade from long range",
						new net.minecraft.world.item.ItemStack(CounterCraft.GRENADES.get("hegrenade")), false);
			} else if (tick == STARTS[3]) {
				gameOverAt = System.nanoTime();
			}
			for (int i = 0; i < STARTS.length; i++) {
				for (int s : SHOTS) {
					if (tick == STARTS[i] + s) {
						String file = "cc2-banner-" + NAMES[i] + "-" + s + ".png";
						net.minecraft.client.Screenshot.grab(mc.gameDirectory, file, mc.gameRenderer.mainRenderTarget(), 1,
								msg -> CounterCraft.LOG.info("[countercraft] banner demo: {}", msg.getString()));
					}
				}
			}
		});
		HudElementRegistry.addLast(CounterCraft.id("banner_demo"), (g, delta) -> {
			if (gameOverAt >= 0) {
				double t = (System.nanoTime() - gameOverAt) / 1e9;
				if (t < 9) {
					WinPanel.gameOver(g, g.guiWidth(), g.guiHeight(), t, "Your team survived 12 days", (float) Math.min(1, (9 - t) / 0.5));
				}
			}
		});
	}
}
