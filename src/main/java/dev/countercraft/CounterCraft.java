package dev.countercraft;

import dev.countercraft.command.CcCommand;
import dev.countercraft.gun.Accuracy;
import dev.countercraft.gun.Deploy;
import dev.countercraft.gun.GrenadeEntity;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.KnifeItem;
import dev.countercraft.gun.GunItem;
import dev.countercraft.gun.GunSpec;
import dev.countercraft.gun.Guns;
import dev.countercraft.movement.CsMove;
import dev.countercraft.net.AltPayload;
import dev.countercraft.net.CvarsPayload;
import dev.countercraft.net.PingPayload;
import dev.countercraft.net.RadioFxPayload;
import dev.countercraft.net.RadioPayload;
import dev.countercraft.net.ShotFxPayload;
import dev.countercraft.net.StepFxPayload;
import dev.countercraft.net.StepPayload;
import dev.countercraft.movement.StepRelay;
import dev.countercraft.net.FirePayload;
import dev.countercraft.net.FlashPayload;
import dev.countercraft.net.MovementModePayload;
import dev.countercraft.net.ThrowPayload;
import dev.countercraft.net.ReloadPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CounterCraft implements ModInitializer {
	public static final String MOD_ID = "countercraft";
	/** The release name players see ("BETA 1.0"); the jar's version (fabric.mod.json) stays a plain number for Fabric and the build. */
	public static final String RELEASE = "BETA 1.0";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	/** Damage types (data/countercraft/damage_type/*.json); all bypass vanilla armor and i-frames, like CS2. */
	public static final ResourceKey<DamageType> BULLET = ResourceKey.create(Registries.DAMAGE_TYPE, id("bullet"));
	public static final ResourceKey<DamageType> KNIFE = ResourceKey.create(Registries.DAMAGE_TYPE, id("knife"));
	public static final ResourceKey<DamageType> GRENADE = ResourceKey.create(Registries.DAMAGE_TYPE, id("grenade"));
	public static final ResourceKey<DamageType> FIRE = ResourceKey.create(Registries.DAMAGE_TYPE, id("fire"));
	/** The Zeus: not a projectile (an enderman can't dodge it, unlike bullets). */
	public static final ResourceKey<DamageType> TASER = ResourceKey.create(Registries.DAMAGE_TYPE, id("taser"));

	public static final EntityType<GrenadeEntity> GRENADE_ENTITY = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("grenade"),
			EntityType.Builder.<GrenadeEntity>of(GrenadeEntity::new, MobCategory.MISC).sized(0.1f, 0.1f).clientTrackingRange(8)
					.updateInterval(2).noSave().build(ResourceKey.create(Registries.ENTITY_TYPE, id("grenade"))));

	public static final EntityType<dev.countercraft.gun.DroppedWeaponEntity> DROPPED_WEAPON = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			id("dropped_weapon"), EntityType.Builder.<dev.countercraft.gun.DroppedWeaponEntity>of(dev.countercraft.gun.DroppedWeaponEntity::new,
					MobCategory.MISC).sized(0.4f, 0.15f).clientTrackingRange(8).updateInterval(1)
					.build(ResourceKey.create(Registries.ENTITY_TYPE, id("dropped_weapon"))));

	/** CS2 run speed with the knife out; guns scale movement by maxSpeed / this. */
	public static final float CS_BASE_SPEED = 250f;

	/** Gun items by weapon id, in buy-menu order. */
	public static final Map<String, GunItem> GUNS = new LinkedHashMap<>();
	public static final Map<String, KnifeItem> KNIVES = new LinkedHashMap<>();
	public static final Map<String, GrenadeItem> GRENADES = new LinkedHashMap<>();
	private static final Map<String, SoundEvent> SOUNDS = new LinkedHashMap<>();

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static Holder<DamageType> bulletDamage(ServerLevel level) {
		return damageType(level, BULLET);
	}

	public static Holder<DamageType> damageType(ServerLevel level, ResourceKey<DamageType> key) {
		return level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
	}

	@Override
	public void onInitialize() {
		ModComponents.init();
		for (GunSpec spec : Guns.ALL.values()) {
			GUNS.put(spec.id(), registerGun(spec));
		}
		for (Guns.MeleeDef m : Guns.MELEE.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(m.id()));
			KNIVES.put(m.id(), Registry.register(BuiltInRegistries.ITEM, key, new KnifeItem(new Item.Properties().setId(key).stacksTo(1), m.id())));
		}
		for (Guns.GrenadeDef g : Guns.GRENADES.values()) {
			g.sounds().values().forEach(CounterCraft::sound);
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(g.id()));
			Item.Properties props = new Item.Properties().setId(key).stacksTo(g.id().equals("flashbang") ? 2 : 1)
					.attributes(speedModifier(g.maxSpeed()));
			GRENADES.put(g.id(), Registry.register(BuiltInRegistries.ITEM, key, new GrenadeItem(props, g)));
		}
		sound("weapon.knife.slash");
		sound("weapon.knife.hit");
		sound("weapon.knife.hitwall");
		sound("weapon.knife.stab");
		sound("molotov.extinguish");
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("weapons"), FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.countercraft.weapons"))
				.icon(() -> new ItemStack(GUNS.getOrDefault("ak47", GUNS.values().iterator().next())))
				.displayItems((params, output) -> {
					GUNS.values().forEach(output::accept);
					KNIVES.values().forEach(output::accept);
					GRENADES.values().forEach(output::accept);
				})
				.build());

		PayloadTypeRegistry.serverboundPlay().register(FirePayload.TYPE, FirePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FirePayload.TYPE, (payload, context) -> {
			ItemStack stack = context.player().getMainHandItem();
			if (!dev.countercraft.team.CsInventory.active(context.player())) {
				return; // CS weapons only work from the CS slots (one in the Minecraft hotbar is just an item)
			}
			if (stack.getItem() instanceof GunItem gun) {
				gun.trigger(context.player(), stack, payload);
			} else if (stack.getItem() instanceof KnifeItem knife && Deploy.ready(context.player())) {
				knife.attack(context.player(), stack, payload.alt(), payload.backstab());
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(ThrowPayload.TYPE, ThrowPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ThrowPayload.TYPE, (payload, context) -> {
			ItemStack stack = context.player().getMainHandItem();
			if (stack.getItem() instanceof GrenadeItem grenade && Deploy.ready(context.player()) && dev.countercraft.team.CsInventory.active(context.player())) {
				grenade.throwGrenade(context.player(), stack, payload);
			}
		});
		PayloadTypeRegistry.clientboundPlay().register(FlashPayload.TYPE, FlashPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShotFxPayload.TYPE, ShotFxPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CvarsPayload.TYPE, CvarsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StepFxPayload.TYPE, StepFxPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(StepPayload.TYPE, StepPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(StepPayload.TYPE, (payload, context) -> StepRelay.relay(context.player(), payload));
		PayloadTypeRegistry.serverboundPlay().register(RadioPayload.TYPE, RadioPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RadioFxPayload.TYPE, RadioFxPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RadioPayload.TYPE, (payload, context) -> Radio.command(context.player(), payload));
		PayloadTypeRegistry.serverboundPlay().register(PingPayload.TYPE, PingPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PingPayload.TYPE, PingPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PingPayload.TYPE, (payload, context) -> Radio.ping(context.player(), payload));
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.AgentAnimPayload.TYPE, dev.countercraft.net.AgentAnimPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.AgentAnimPayload.TYPE, dev.countercraft.net.AgentAnimPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.AgentAnimPayload.TYPE,
				(payload, context) -> AgentAnims.fromClient(context.player(), payload));
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.KillFeedPayload.TYPE, dev.countercraft.net.KillFeedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.HitSoundPayload.TYPE, dev.countercraft.net.HitSoundPayload.CODEC);
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register(Combat::onDeath);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.DeathInfoPayload.TYPE, dev.countercraft.net.DeathInfoPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.RagdollPayload.TYPE, dev.countercraft.net.RagdollPayload.CODEC);
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof net.minecraft.server.level.ServerPlayer p) {
				dev.countercraft.team.CsInventory.onDeath(p);
				Achievements.died(p);
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(ReloadPayload.TYPE, ReloadPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ReloadPayload.TYPE, (payload, context) -> {
			ItemStack stack = context.player().getMainHandItem();
			if (stack.getItem() instanceof GunItem gun && Deploy.ready(context.player()) && dev.countercraft.team.CsInventory.active(context.player())) {
				gun.startReload(context.player().level(), context.player(), stack);
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(MovementModePayload.TYPE, MovementModePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MovementModePayload.TYPE,
				(payload, context) -> CsMove.setServer(context.player().getUUID(), payload.cs()));
		PayloadTypeRegistry.serverboundPlay().register(AltPayload.TYPE, AltPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(AltPayload.TYPE, (payload, context) -> {
			ItemStack stack = context.player().getMainHandItem();
			if (stack.getItem() instanceof GunItem gun && Deploy.ready(context.player()) && dev.countercraft.team.CsInventory.active(context.player())) {
				gun.alt(context.player(), stack);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Deploy.tick(server);
			Accuracy.tick(server);
			GunItem.tickBursts(server);
			Achievements.tick(server);
			MobVision.tick(server);
		});

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(ServerCvars::load);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerCvars.unload());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerCvars.send(handler.getPlayer());
			dev.countercraft.team.Economy.join(handler.getPlayer());
			Achievements.join(handler.getPlayer());
			dev.countercraft.team.TeamLock.join(handler.getPlayer());
			dev.countercraft.steam.GenuineSkins.join(handler.getPlayer());
		});
		dev.countercraft.team.Economy.init();
		SurvivalRecord.init();
		RunStats.init();
		AutoRadio.init();
		ServerTickEvents.END_SERVER_TICK.register(Scoreboard::tick);
		ServerTickEvents.END_SERVER_TICK.register(BossArmor::tick);
		ServerTickEvents.END_SERVER_TICK.register(GunNoise::tick);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.MvpPayload.TYPE, dev.countercraft.net.MvpPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.ScoreboardPayload.TYPE, dev.countercraft.net.ScoreboardPayload.CODEC);
		ServerTickEvents.END_SERVER_TICK.register(RunStats::tick);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.RunStatsPayload.TYPE, dev.countercraft.net.RunStatsPayload.CODEC);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			GunItem.forget(handler.getPlayer().getUUID());
			KnifeItem.forget(handler.getPlayer().getUUID());
			Deploy.forget(handler.getPlayer().getUUID());
			StepRelay.forget(handler.getPlayer().getUUID());
			Radio.forget(handler.getPlayer().getUUID());
			Combat.forget(handler.getPlayer().getUUID());
			Achievements.leave(handler.getPlayer());
			dev.countercraft.team.TeamLock.leave(handler.getPlayer());
			dev.countercraft.steam.GenuineSkins.leave(handler.getPlayer());
			GunNoise.leave(handler.getPlayer());
			Mvp.leave(handler.getPlayer());
			AutoRadio.leave(handler.getPlayer());
			DeathTracker.reset(handler.getPlayer().getUUID());
			CsMove.setServer(handler.getPlayer().getUUID(), false);
		});
		Combat.init();
		dev.countercraft.team.Cosmetics.init();
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.MobHeadsPayload.TYPE, dev.countercraft.net.MobHeadsPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.MobHeadsPayload.TYPE, (payload, context) -> {
			if (!dev.countercraft.gun.MobHeads.known()) {
				dev.countercraft.gun.MobHeads.set(payload.shapes()); // vanilla models: every client has the same
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.CosmeticsPayload.TYPE, dev.countercraft.net.CosmeticsPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.CosmeticsPayload.TYPE, (payload, context) -> {
			context.player().setAttached(dev.countercraft.team.Cosmetics.LOADOUT, payload.loadout());
			context.player().setAttached(dev.countercraft.team.Loadouts.START_PISTOL,
					java.util.Map.of(dev.countercraft.team.Teams.T, payload.tPistol(), dev.countercraft.team.Teams.CT, payload.ctPistol()));
			dev.countercraft.team.Loadouts.loadoutArrived(context.player());
			dev.countercraft.steam.GenuineSkins.setSkinchanger(context.player(), payload.skinchanger());
			Mvp.setKit(context.player(), payload.musicKit());
			dev.countercraft.team.CsInventory.refreshKnife(context.player());
		});
		dev.countercraft.team.Teams.init();
		dev.countercraft.team.Loadouts.init();
		dev.countercraft.team.ExtraAmmo.init();
		dev.countercraft.team.TeamLock.init();
		dev.countercraft.team.AnotherChance.init();
		dev.countercraft.team.Refunds.init();
		dev.countercraft.steam.GenuineSkins.init();
		ServerTickEvents.END_SERVER_TICK.register(dev.countercraft.steam.GenuineSkins::tick);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.SteamStatusPayload.TYPE, dev.countercraft.net.SteamStatusPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.SteamLinkPayload.TYPE, dev.countercraft.net.SteamLinkPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.SteamLinkPayload.TYPE, (payload, context) -> {
			if (payload.params().containsKey(dev.countercraft.net.SteamLinkPayload.REFRESH)) {
				dev.countercraft.steam.GenuineSkins.refreshRequested(context.player());
			} else if (payload.params().containsKey(dev.countercraft.net.SteamLinkPayload.CLAIM)) {
				dev.countercraft.steam.GenuineSkins.claim(context.player(), payload.params().get(dev.countercraft.net.SteamLinkPayload.CLAIM));
			} else if (payload.params().isEmpty()) {
				dev.countercraft.steam.GenuineSkins.unlink(context.player());
			} else {
				dev.countercraft.steam.GenuineSkins.link(context.player(), payload);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(dev.countercraft.team.TeamLock::tick);
		ServerTickEvents.END_SERVER_TICK.register(dev.countercraft.team.DayCycle::tick);
		ServerTickEvents.END_SERVER_TICK.register(dev.countercraft.server.StatusCollector::tick); // the dedicated server's window
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s -> dev.countercraft.team.DayCycle.reset());
		ServerTickEvents.END_SERVER_TICK.register(dev.countercraft.bot.TestBots::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(dev.countercraft.bot.TestBots::removeAll);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.TeamPromptPayload.TYPE, dev.countercraft.net.TeamPromptPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.countercraft.net.DaySurvivedPayload.TYPE, dev.countercraft.net.DaySurvivedPayload.CODEC);
		dev.countercraft.team.CsInventory.init();
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.CsModePayload.TYPE, dev.countercraft.net.CsModePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.CsModePayload.TYPE,
				(payload, context) -> dev.countercraft.team.CsInventory.toggle(context.player()));
		ServerTickEvents.END_SERVER_TICK.register(dev.countercraft.team.CsInventory::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> dev.countercraft.team.CsInventory.refreshKnife(handler.getPlayer()));
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register(
				(oldPlayer, newPlayer, alive) -> {
					dev.countercraft.team.CsInventory.refreshKnife(newPlayer);
					DeathTracker.reset(newPlayer.getUUID());
					if (!alive) {
						dev.countercraft.team.Loadouts.giveStartingPistol(newPlayer);
					}
				});
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.CsDropPayload.TYPE, dev.countercraft.net.CsDropPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.CsDropPayload.TYPE,
				(payload, context) -> dev.countercraft.team.CsDrop.dropHeld(context.player()));
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.PickupPayload.TYPE, dev.countercraft.net.PickupPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.PickupPayload.TYPE,
				(payload, context) -> dev.countercraft.team.CsDrop.usePickup(context.player(), payload.entity()));
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.CreativeCsSlotPayload.TYPE, dev.countercraft.net.CreativeCsSlotPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.CreativeCsSlotPayload.TYPE,
				(payload, context) -> dev.countercraft.team.CsInventory.creativeSet(context.player(), payload.slot(), payload.stack()));
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.TeamSelectPayload.TYPE, dev.countercraft.net.TeamSelectPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.TeamSelectPayload.TYPE,
				(payload, context) -> {
					if (!dev.countercraft.team.TeamLock.choose(context.player(), payload.team())) {
						return;
					}
					LOG.info("[countercraft] {} joined team {}", context.player().getName().getString(), payload.team());
				});
		PayloadTypeRegistry.serverboundPlay().register(dev.countercraft.net.BuyPayload.TYPE, dev.countercraft.net.BuyPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.countercraft.net.BuyPayload.TYPE,
				(payload, context) -> {
					LOG.info("[countercraft] {} buys {}", context.player().getName().getString(), payload.item());
					dev.countercraft.team.Buying.buy(context.player(), payload.item());
				});
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			CcCommand.register(dispatcher);
			ServerCvars.register(dispatcher);
			Achievements.register(dispatcher);
			dev.countercraft.bot.TestBots.register(dispatcher);
			// /team t|ct (the team select screen is M)
			for (String t : new String[] {dev.countercraft.team.Teams.T, dev.countercraft.team.Teams.CT}) {
				dispatcher.register(net.minecraft.commands.Commands.literal("team").then(net.minecraft.commands.Commands.literal(t).executes(ctx -> {
					if (!dev.countercraft.team.TeamLock.choose(ctx.getSource().getPlayerOrException(), t)) {
						return 0;
					}
					ctx.getSource().sendSuccess(() -> Component.literal("Joined the " + (t.equals("ct") ? "Counter-Terrorists" : "Terrorists")), false);
					return 1;
				})));
			}
		});
		LOG.info("[countercraft] loaded {} guns, {} knives, {} grenades", GUNS.size(), KNIVES.size(), GRENADES.size());
	}

	static SoundEvent sound(String id) {
		if (id == null) {
			return null;
		}
		return SOUNDS.computeIfAbsent(id, s -> {
			Identifier sid = id(s);
			return Registry.register(BuiltInRegistries.SOUND_EVENT, sid, SoundEvent.createVariableRangeEvent(sid));
		});
	}

	private static GunItem registerGun(GunSpec spec) {
		SoundEvent fire = sound(spec.fireSound());
		SoundEvent unsilenced = sound(spec.sounds().fireUnsilenced());
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(spec.id()));
		Item.Properties props = new Item.Properties()
				.setId(key)
				.stacksTo(1)
				.component(ModComponents.AMMO, spec.magSize())
				.component(ModComponents.RESERVE, spec.reserve())
				.attributes(speedModifier(spec.maxSpeed()[0]));
		return Registry.register(BuiltInRegistries.ITEM, key, new GunItem(props, spec, fire, unsilenced));
	}

	/** CS2 move speed with this weapon out, relative to the knife (Minecraft's walk = CS2's knife run). */
	private static ItemAttributeModifiers speedModifier(float csMaxSpeed) {
		return ItemAttributeModifiers.builder()
				.add(Attributes.MOVEMENT_SPEED, new AttributeModifier(id("weapon_speed"), csMaxSpeed / CS_BASE_SPEED - 1.0,
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL), EquipmentSlotGroup.MAINHAND)
				.build();
	}
}
