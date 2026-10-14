package dev.countercraft.client;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.fx.ShotEffects;
import dev.countercraft.client.movement.Footsteps;
import dev.countercraft.client.radio.Pings;
import dev.countercraft.client.radio.RadioClient;
import dev.countercraft.client.radio.RadioWheel;
import dev.countercraft.net.CvarsPayload;
import dev.countercraft.net.PingPayload;
import dev.countercraft.net.RadioFxPayload;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import dev.countercraft.net.FlashPayload;
import dev.countercraft.net.ShotFxPayload;
import dev.countercraft.net.StepFxPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import dev.countercraft.client.movement.MovementKeys;
import dev.countercraft.client.movement.MovementSettings;
import dev.countercraft.client.viewmodel.ViewmodelSettings;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

public class CounterCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		GunKeys.init();
		VersionClient.init();
		RadioWheel.init();
		MovementKeys.init();
		MovementSettings.load();
		dev.countercraft.client.options.CcOptions.gpuEnvironmentActive(); // load now: GPU Environment Rendering is fixed at startup
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> MovementSettings.sync());
		GunHud.register();
		EntityRendererRegistry.register(CounterCraft.GRENADE_ENTITY, GrenadeRenderer::new);
		EntityRendererRegistry.register(CounterCraft.DROPPED_WEAPON, DroppedWeaponRenderer::new);
		ClientTickEvents.START_CLIENT_TICK.register(DropClient::startTick);
		ClientTickEvents.END_CLIENT_TICK.register(MobHeadsClient::tick);
		ClientTickEvents.END_CLIENT_TICK.register(HitboxDebug::tick);
		ClientTickEvents.END_CLIENT_TICK.register(GrenadeTracer::tick);
		// CS2 death cam, death panel and ragdolls
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.DeathInfoPayload.TYPE,
				(payload, context) -> {
					dev.countercraft.client.death.DeathCam.onInfo(payload);
					dev.countercraft.client.steam.RunHistory.onDeath(context.client(), payload);
				});
		HudElementRegistry.addLast(CounterCraft.id("killer_picture"), (g, delta) -> dev.countercraft.client.steam.KillerPip.hud(g));
		// CS2 weapons outside the CS slots: "Weapon | Finish" in its rarity colour; the pick-up / stash prompt
		net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> WeaponNames.tooltip(stack, lines));
		HudElementRegistry.addLast(CounterCraft.id("pickup_prompt"), (g, delta) -> WeaponNames.pickupPrompt(g));
		// achievements as CS2 MVP banners (challenges: for the whole server, with the achiever's MVP anthem)
		HudElementRegistry.addLast(CounterCraft.id("mvp_banners"), (g, delta) -> dev.countercraft.client.hud.MvpBanners.draw(g));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.MvpPayload.TYPE,
				(payload, context) -> dev.countercraft.client.hud.MvpBanners.challenge(payload));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.RagdollPayload.TYPE,
				(payload, context) -> dev.countercraft.client.agent.Ragdolls.onPayload(payload));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			dev.countercraft.client.agent.Ragdolls.tick(client);
			dev.countercraft.client.agent.AgentClient.prune(client);
			dev.countercraft.client.death.DeathCam.tick(client);
			dev.countercraft.client.steam.RunHistory.tick(client);
			if (client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen ds) {
				client.gui.setScreen(new dev.countercraft.client.death.CsDeathScreen(ds));
			}
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(dev.countercraft.client.agent.Ragdolls::submit);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (GunKeys.SWITCH_HANDS.consumeClick()) {
				ViewmodelSettings.switchHands();
			}
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(HitboxDebug::submit);
		LevelRenderEvents.COLLECT_SUBMITS.register(GrenadeTracer::submit);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> MobHeadsClient.markDirty());
		ClientPlayNetworking.registerGlobalReceiver(FlashPayload.TYPE, (payload, context) -> GunHud.flash(payload.seconds()));
		LevelRenderEvents.COLLECT_SUBMITS.register(dev.countercraft.client.fx.MiniLightning::submit);
		ClientPlayNetworking.registerGlobalReceiver(ShotFxPayload.TYPE, (payload, context) -> {
			ShotEffects.onShot(payload);
			if (context.player() != null && payload.shooter() != context.player().getId()) {
				dev.countercraft.client.agent.AgentClient.action(payload.shooter(), "action_attack", "attack_gun_primaryfire", 0.5f);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.AgentAnimPayload.TYPE,
				(payload, context) -> dev.countercraft.client.agent.AgentClient.onPayload(payload));
		LevelRenderEvents.COLLECT_SUBMITS.register(dev.countercraft.client.agent.AgentClient::submitLegs);
		ClientPlayNetworking.registerGlobalReceiver(StepFxPayload.TYPE, (payload, context) -> Footsteps.remote(payload));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.TeamPromptPayload.TYPE,
				(payload, context) -> context.client().gui.setScreen(new dev.countercraft.client.loadout.TeamSelectScreen(true)));
		ClientPlayNetworking.registerGlobalReceiver(CvarsPayload.TYPE, (payload, context) -> {
			GunClient.infiniteAmmo = payload.infiniteAmmo();
			dev.countercraft.movement.CsMove.maxVelocity = payload.maxVelocity();
			dev.countercraft.movement.CsMove.airAccelerate = payload.airAccelerate();
			dev.countercraft.movement.CsMove.stamina(payload.staminaMax(), payload.staminaJumpCost(), payload.staminaLandCost());
		});
		ClientPlayNetworking.registerGlobalReceiver(RadioFxPayload.TYPE, (payload, context) -> RadioClient.onRadio(payload));
		ClientPlayNetworking.registerGlobalReceiver(PingPayload.TYPE, (payload, context) -> Pings.onPing(payload));
		ClientTickEvents.START_CLIENT_TICK.register(Pings::startTick);
		ClientTickEvents.END_CLIENT_TICK.register(RadioWheel::tick);
		ClientTickEvents.END_CLIENT_TICK.register(c -> dev.countercraft.client.fx.MuzzleLight.tickFires()); // burning molotovs light up
		HudElementRegistry.addLast(CounterCraft.id("radio_wheel"), (g, delta) -> RadioWheel.render(g));
		HudElementRegistry.addLast(CounterCraft.id("round_banner"), (g, delta) -> dev.countercraft.client.hud.RoundCycle.renderBanner(g));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.RunStatsPayload.TYPE,
				(payload, context) -> dev.countercraft.client.steam.RunHistory.onStats(context.client(), payload));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.SteamStatusPayload.TYPE,
				(payload, context) -> dev.countercraft.client.steam.SteamLinkClient.onStatus(payload));
		// Counter-Craft's Another Chance: spectating the players alive while dead, GAME OVER when the last one dies
		dev.countercraft.client.death.Spectate.init();
		ClientTickEvents.START_CLIENT_TICK.register(dev.countercraft.client.death.Spectate::startTick);
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.SpectatePayload.TYPE,
				(payload, context) -> dev.countercraft.client.death.Spectate.onInfo(payload));
		HudElementRegistry.addLast(CounterCraft.id("spectate"), (g, delta) -> dev.countercraft.client.death.Spectate.hud(g));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.DaySurvivedPayload.TYPE,
				(payload, context) -> dev.countercraft.client.hud.RoundCycle.onDaySurvived(payload));
		ClientTickEvents.END_CLIENT_TICK.register(dev.countercraft.client.hud.RoundCycle::tick);
		HudElementRegistry.addLast(CounterCraft.id("compass"), (g, delta) -> dev.countercraft.client.hud.CompassHud.render(g));
		// hardcore: every player's whereabouts (or DEAD); drawn after the panels it fits around (below)
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.TeamListPayload.TYPE,
				(payload, context) -> dev.countercraft.client.hud.TeamListHud.onList(payload));
		// boss bars go below the locator bar and the compass at the top
		HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.BOSS_BAR, boss -> (g, delta) -> {
			g.pose().pushMatrix();
			g.pose().translate(0, dev.countercraft.client.hud.CompassHud.height());
			boss.extractRenderState(g, delta);
			g.pose().popMatrix();
		});
		HudElementRegistry.addLast(CounterCraft.id("kill_feed"), (g, delta) -> dev.countercraft.client.hud.KillFeed.render(g));
		HudElementRegistry.addLast(CounterCraft.id("money"), (g, delta) -> dev.countercraft.client.hud.MoneyHud.draw(g));
		HudElementRegistry.addLast(CounterCraft.id("paperdoll"), (g, delta) -> dev.countercraft.client.hud.PaperdollHud.draw(g));
		HudElementRegistry.addLast(CounterCraft.id("team_list"), (g, delta) -> dev.countercraft.client.hud.TeamListHud.draw(g));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.KillFeedPayload.TYPE,
				(payload, context) -> dev.countercraft.client.hud.KillFeed.onKill(payload));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.HitSoundPayload.TYPE,
				(payload, context) -> dev.countercraft.client.hud.HitSounds.onHit(payload));
		LevelRenderEvents.COLLECT_SUBMITS.register(Pings::submit);
		// the server needs your equipped skins / knife / agent for buying, the knife slot and other players' view
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> dev.countercraft.client.loadout.LoadoutConfig.sync());
		// offer this computer's Steam link to the world (only your own singleplayer world takes it without a sign-in)
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (ClientPlayNetworking.canSend(dev.countercraft.net.SteamLinkPayload.TYPE)) {
				String id = dev.countercraft.client.steam.SteamLinkClient.steamId();
				ClientPlayNetworking.send(new dev.countercraft.net.SteamLinkPayload(
						java.util.Map.of(dev.countercraft.net.SteamLinkPayload.CLAIM, id == null ? "" : id)));
			}
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Pings.clear();
			dev.countercraft.client.hud.KillFeed.clear();
			dev.countercraft.client.agent.AgentClient.clear();
			dev.countercraft.client.agent.Ragdolls.clear();
			dev.countercraft.client.death.DeathCam.stop();
			ShotEffects.clear();
			GunClient.infiniteAmmo = 0;
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(ShotEffects::submitWorld);
		ViewmodelSettings.load();
		dev.countercraft.client.crosshair.CrosshairSettings.load();
		ClientTickEvents.END_CLIENT_TICK.register(dev.countercraft.client.crosshair.CrosshairScreen::tick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (GunKeys.CROSSHAIR.consumeClick()) {
				client.gui.setScreen(new dev.countercraft.client.crosshair.CrosshairScreen(null));
			}
		});
		// buy menu (B), team select (M); Loadout buttons on the title and pause screens; the agent preview renderer
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (GunKeys.BUY.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					if (!dev.countercraft.client.hud.RoundCycle.buyPhase() && !client.player.isCreative()) {
						// Counter-Craft: only during the buy phase, like CS2's buy time
						int s = dev.countercraft.client.hud.RoundCycle.secondsToBuyPhase();
						client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
								String.format("The buy time has expired. Next buy phase in %02d:%02d", s / 60, s % 60)).withColor(0xE05050));
						continue;
					}
					client.gui.setScreen(new dev.countercraft.client.loadout.BuyMenuScreen());
				}
			}
			while (GunKeys.SELL_BACK_ALL.consumeClick()) {
				// CS2's sellbackall works without the buy menu too
				if (client.player != null && client.gui.screen() == null) {
					ClientPlayNetworking.send(new dev.countercraft.net.RefundPayload(dev.countercraft.team.Refunds.ALL));
				}
			}
			while (GunKeys.TEAM.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) {
					client.gui.setScreen(new dev.countercraft.client.loadout.TeamSelectScreen());
				}
			}
		});
		ClientTickEvents.START_CLIENT_TICK.register(dev.countercraft.client.loadout.CsHotbar::startTick);
		// CS2-style health / armor / hunger (bottom left) in place of the vanilla bars
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.HEALTH_BAR,
				vanilla -> (g, delta) -> dev.countercraft.client.hud.VitalsHud.draw(g));
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.ARMOR_BAR, vanilla -> (g, delta) -> { });
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.FOOD_BAR, vanilla -> (g, delta) -> { });
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.AIR_BAR, vanilla -> (g, delta) -> { });
		// CS2's scoreboard in place of the player list, in every mode; right-click while holding it frees the cursor
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.PLAYER_LIST,
				vanilla -> (g, delta) -> dev.countercraft.client.hud.ScoreboardHud.hud(g));
		ClientPlayNetworking.registerGlobalReceiver(dev.countercraft.net.ScoreboardPayload.TYPE,
				(payload, context) -> dev.countercraft.client.hud.ScoreboardHud.onPayload(payload));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
				(handler, client) -> dev.countercraft.client.hud.ScoreboardHud.clear());
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			if (client.gui.screen() == null && client.player != null && client.options.keyPlayerList.isDown()) {
				boolean right = false;
				while (client.options.keyUse.consumeClick()) {
					right = true;
				}
				while (dev.countercraft.client.GunKeys.ALT_FIRE.consumeClick()) {
					right = true;
				}
				if (right) {
					client.gui.setScreen(new dev.countercraft.client.hud.ScoreboardScreen());
				}
			}
		});
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.HOTBAR, hotbar -> (g, delta) -> {
					if (dev.countercraft.client.loadout.CsHotbar.active()) {
						dev.countercraft.client.loadout.CsHotbar.draw(g, delta);
					} else {
						hotbar.extractRenderState(g, delta);
					}
				});
		net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(ctx -> new dev.countercraft.client.agent.AgentPip.Renderer());
		net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(ctx -> new dev.countercraft.client.steam.KillerPip.Renderer());
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof net.minecraft.client.gui.screens.TitleScreen || screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
				net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
						net.minecraft.network.chat.Component.literal("Loadout"),
						b -> client.gui.setScreen(new dev.countercraft.client.loadout.LoadoutScreen(screen))).bounds(4, 4, 132, 20).build());
				// CS2 Stats and MC Stats: bottom left, above the version line
				net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
						net.minecraft.network.chat.Component.literal("CS2 Stats"),
						b -> client.gui.setScreen(new dev.countercraft.client.steam.CsStatsScreen(screen))).bounds(4, h - 60, 100, 20).build());
				net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
						net.minecraft.network.chat.Component.literal("MC Stats"),
						b -> client.gui.setScreen(new dev.countercraft.client.steam.McStatsScreen(screen))).bounds(4, h - 36, 100, 20).build());
				if (screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
					// About CC2 and Quit Game: see below
					net.minecraft.client.gui.components.AbstractWidget opts = null;
					net.minecraft.client.gui.components.AbstractWidget quit = null;
					for (var wd : net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen)) {
						if (wd instanceof net.minecraft.client.gui.components.AbstractWidget aw
								&& aw.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
							if (tc.getKey().equals("menu.options")) {
								opts = aw;
							} else if (tc.getKey().equals("menu.quit")) {
								quit = aw;
							}
						}
					}
					if (opts != null && quit != null) {
						// About takes Quit Game's place beside Options...; Quit Game moves under the row, full width
						net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
								net.minecraft.network.chat.Component.literal("About CC2"),
								b -> dev.countercraft.client.options.CcOptionsScreen.openAbout(screen))
								.bounds(quit.getX(), quit.getY(), quit.getWidth(), quit.getHeight()).build());
						int right = quit.getRight();
						quit.setPosition(opts.getX(), quit.getY() + 24);
						quit.setWidth(right - opts.getX());
					}
					// the linked Steam account's card, top right: clicking it opens the stats (or the optional link screen)
					int cx = w - dev.countercraft.client.steam.SteamProfileCard.W - 6;
					int cy = 6;
					net.minecraft.client.gui.components.Button hit = net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.empty(),
							b -> client.gui.setScreen(dev.countercraft.client.steam.SteamLinkClient.steamId() == null
									? new dev.countercraft.client.steam.SteamLinkScreen(screen) : new dev.countercraft.client.steam.CsStatsScreen(screen)))
							.bounds(cx, cy, dev.countercraft.client.steam.SteamProfileCard.W, dev.countercraft.client.steam.SteamProfileCard.H).build();
					hit.setAlpha(0f);
					net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(hit);
					net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mx, my, d) ->
							dev.countercraft.client.steam.SteamProfileCard.draw(g, client.font, cx, cy, hit.isHovered()));
				}
				net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
						net.minecraft.network.chat.Component.literal("Skins Options"),
						b -> dev.countercraft.client.skins.SkinsOptionsScreen.open(screen)).bounds(4, 28, 132, 20).build());
				net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
						net.minecraft.network.chat.Component.literal("Counter-Craft 2 Options"),
						b -> dev.countercraft.client.options.CcOptionsScreen.open(screen)).bounds(4, 52, 132, 20).build());
				// the CS2 asset pack is required: without it, the title screen can't be passed (AssetPackScreen); in a world
				// (it was turned off from the options) a button to load it again
				if (screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
					dev.countercraft.client.options.AssetPackScreen.require(client, screen);
				} else if (!dev.countercraft.client.options.AssetPackScreen.loaded()) {
					net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(net.minecraft.client.gui.components.Button.builder(
							net.minecraft.network.chat.Component.literal("Load CS2 Assets").withColor(0xDCB45A),
							b -> client.gui.setScreen(new dev.countercraft.client.options.AssetPackScreen(screen))).bounds(4, 76, 132, 20).build());
				}
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
			ViewmodelSettings.register(dispatcher);
			MovementSettings.register(dispatcher);
			RadioWheel.register(dispatcher);
			dev.countercraft.client.crosshair.CrosshairSettings.register(dispatcher);
			GunHud.registerCommand(dispatcher);
		});
		ClientTickEvents.START_CLIENT_TICK.register(GunClient::startTick);
		ClientTickEvents.END_CLIENT_TICK.register(GunClient::endTick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (MovementKeys.AUTO_BHOP.consumeClick()) {
				MovementSettings.toggleAutoBhop();
			}
		});
		// enabling/disabling the CS2 asset pack (or F3+T) must re-load the viewmodels
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(CounterCraft.id("viewmodels"),
				(ResourceManagerReloadListener) manager -> {
					GunClient.clearAssets();
					dev.countercraft.client.gpu.GpuAgents.clear();
					MobHeadsClient.markDirty();
					dev.countercraft.client.loadout.MusicKit.clear();
					dev.countercraft.client.skins.Skins.clear();
					dev.countercraft.client.skins.Agents.clear();
				});
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(CounterCraft.id("agents"),
				(ResourceManagerReloadListener) dev.countercraft.client.agent.AgentClient::reload);
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(CounterCraft.id("hud"), (ResourceManagerReloadListener) rm -> {
			dev.countercraft.client.hud.KillFeed.reload(rm);
			dev.countercraft.client.hud.HitSounds.reload(rm);
		});
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(CounterCraft.id("radio"),
				(ResourceManagerReloadListener) RadioClient::reload);
		ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(CounterCraft.id("csgo_sounds"),
				(ResourceManagerReloadListener) CsgoSounds::reload);
	}
}
