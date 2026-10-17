package dev.countercraft;

import dev.countercraft.gun.GrenadeEntity;
import dev.countercraft.team.Buying;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Dev: -Dcountercraft.grenadeTest=true runs the 1.3 grenade-vs-mob rules once the first player has joined, on an obsidian
 * platform 40 blocks above them, and logs "[countercraft] selftest name: PASS / FAIL ..." for each. The platform is
 * cleared afterwards. Never on unless that property is set.
 */
public final class GrenadeSelfTest {
	private static final boolean ON = Boolean.getBoolean("countercraft.grenadeTest");
	private static final int R = 12;
	private static int tick = -1;
	private static BlockPos c;
	private static final List<Entity> SPAWNED = new ArrayList<>();
	private static final Object[] KEEP = new Object[8];

	private GrenadeSelfTest() {
	}

	public static void init() {
		if (ON) {
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				List<ServerPlayer> ps = server.getPlayerList().getPlayers();
				if (ps.isEmpty() || tick > 2720) {
					return;
				}
				tick++;
				try {
					step(ps.get(0), (ServerLevel) ps.get(0).level());
				} catch (RuntimeException e) {
					CounterCraft.LOG.error("[countercraft] selftest crashed at tick {}", tick, e);
					tick = 10_000;
				}
			});
		}
	}

	private static final String[] TEST_ACH = {"electrician", "collateral_damage", "ace", "flash_photography", "tactical_retreat", "to_the_window",
			"bonk", "smoke_screen", "deforestation_department", "the_floor_is_lava", "money_talks"};

	private static void tp(ServerLevel level, ServerPlayer p, double x, double y, double z, float pitch) {
		p.teleportTo(level, x, y, z, java.util.Set.of(), 0f, pitch, true);
	}

	private static boolean done(ServerPlayer p, String name) {
		var h = p.level().getServer().getAdvancements().get(CounterCraft.id("cs2/" + name));
		return h != null && p.getAdvancements().getOrStartProgress(h).isDone();
	}

	private static void log(String name, boolean pass, String detail) {
		CounterCraft.LOG.info("[countercraft] selftest {}: {} {}", name, pass ? "PASS" : "FAIL", detail);
	}

	private static <T extends Entity> T spawn(ServerLevel level, EntityType<T> type, double dx, double dy, double dz, Consumer<T> setup) {
		T e = type.create(level, EntitySpawnReason.COMMAND);
		e.snapTo(c.getX() + 0.5 + dx, c.getY() + 1 + dy, c.getZ() + 0.5 + dz, 0f, 0f);
		if (setup != null) {
			setup.accept(e);
		}
		level.addFreshEntity(e);
		SPAWNED.add(e);
		return e;
	}

	private static GrenadeEntity grenade(ServerLevel level, Player owner, String kind, double dx, double dy, double dz, Vec3 vel) {
		GrenadeEntity g = new GrenadeEntity(level, owner, new ItemStack(CounterCraft.GRENADES.get(kind)), kind);
		g.launch(new Vec3(c.getX() + 0.5 + dx, c.getY() + 1.1 + dy, c.getZ() + 0.5 + dz), vel);
		level.addFreshEntity(g);
		SPAWNED.add(g);
		return g;
	}

	private static void clear(ServerLevel level) {
		for (Entity e : level.getEntities((Entity) null, new AABB(c).inflate(R + 4, 12, R + 4), e -> !(e instanceof Player))) {
			e.discard();
		}
		SPAWNED.clear();
	}

	private static void fill(ServerLevel level, BlockPos a, BlockPos b, net.minecraft.world.level.block.state.BlockState st) {
		for (BlockPos p : BlockPos.betweenClosed(a, b)) {
			level.setBlock(p, st, 2);
		}
	}

	@SuppressWarnings("unchecked")
	private static void step(ServerPlayer p, ServerLevel level) {
		switch (tick) {
			case 40 -> {
				c = p.blockPosition().offset(0, 40, 0);
				// the C4 test off, as a world starts (it takes any C4 left over from an earlier run)
				dev.countercraft.command.BetaCommand.setC4Test(level.getServer(), false);
				// the defuse kit: not for sale with the C4 test off, even to a CT
				p.setAttached(dev.countercraft.team.Teams.TEAM, dev.countercraft.team.Teams.CT);
				dev.countercraft.team.Buying.buy(p, "item_defuser");
				KEEP[7] = Combat.hasDefuser(p);
				fill(level, c.offset(-R, 0, -R), c.offset(R, 0, R), Blocks.OBSIDIAN.defaultBlockState());
				fill(level, c.offset(-R, 1, -R), c.offset(R, 6, R), Blocks.AIR.defaultBlockState());
				// prices: the nade kit and wartime
				p.setAttached(dev.countercraft.team.Teams.TEAM, dev.countercraft.team.Teams.T);
				int kit = Buying.price(p, Buying.NADE_KIT);
				p.setAttached(Buying.WARTIME, 25);
				int war = Buying.price(p, "weapon_hegrenade");
				int warKit = Buying.price(p, Buying.NADE_KIT);
				p.removeAttached(Buying.WARTIME);
				log("nade_kit_price", kit == 1080, "kit=" + kit + " " + Buying.kit(p));
				log("wartime_price", war == 225 && warKit == 811, "he=" + war + " kit=" + warKit);
			}
			// 1: HE vs slime (no split), silverfish, a close husk, a far husk (shoved), TNT (primed)
			case 60 -> {
				KEEP[0] = spawn(level, EntityTypes.SLIME, 1, 0, 0, s -> s.setSize(2, true));
				KEEP[1] = spawn(level, EntityTypes.SILVERFISH, 0, 0, 1, null);
				// (too slow to walk: what moves it is the blast)
				KEEP[2] = spawn(level, EntityTypes.HUSK, 4.5, 0, 0, m -> m.addEffect(new net.minecraft.world.effect.MobEffectInstance(
						net.minecraft.world.effect.MobEffects.SLOWNESS, 2000, 10)));
				level.setBlock(c.offset(-1, 1, -1), Blocks.TNT.defaultBlockState(), 3);
				grenade(level, p, "hegrenade", 0, 0, 0, Vec3.ZERO);
			}
			case 85 -> KEEP[3] = ((Entity) KEEP[2]).position();
			case 120 -> {
				int slimes = level.getEntitiesOfClass(net.minecraft.world.entity.monster.cubemob.Slime.class, new AABB(c).inflate(R, 8, R)).size();
				log("he_slime_no_split", slimes == 0, "slimes=" + slimes);
				log("he_silverfish", !((Entity) KEEP[1]).isAlive(), "");
				Entity husk = (Entity) KEEP[2];
				log("he_stagger", husk.isAlive() && ((Vec3) KEEP[3]).distanceTo(husk.position()) > 0.5, "alive=" + husk.isAlive()
						+ " health=" + ((Mob) husk).getHealth() + " moved=" + String.format("%.2f", ((Vec3) KEEP[3]).distanceTo(husk.position())));
				boolean primed = !level.getEntitiesOfClass(net.minecraft.world.entity.item.PrimedTnt.class, new AABB(c).inflate(R, 8, R)).isEmpty();
				log("he_tnt", primed && level.getBlockState(c.offset(-1, 1, -1)).isAir(), "primed=" + primed);
				clear(level);
			}
			// 2: a creeper in the blast goes off
			case 140 -> {
				KEEP[0] = spawn(level, EntityTypes.CREEPER, 1.5, 0, 0, m -> m.setNoAi(true));
				KEEP[1] = grenade(level, p, "hegrenade", 0, 0, 0, Vec3.ZERO);
			}
			case 180 -> log("he_creeper_chain", ((Entity) KEEP[1]).isRemoved() && ((Entity) KEEP[0]).isRemoved(), "creeperRemoved=" + ((Entity) KEEP[0]).isRemoved());
			case 190 -> clear(level);
			// 3: molotov: pathing, spawning, cooking
			case 200 -> {
				grenade(level, p, "molotov", 0, 1, 0, new Vec3(0, -250, 0));
				ItemEntity iron = new ItemEntity(level, c.getX() + 0.5, c.getY() + 1.2, c.getZ() + 0.5, new ItemStack(Items.RAW_IRON, 3), 0, 0, 0);
				iron.setNeverPickUp();
				level.addFreshEntity(iron);
				KEEP[0] = iron;
				KEEP[1] = spawn(level, EntityTypes.HUSK, -7, 0, 0, m -> m.addEffect(new net.minecraft.world.effect.MobEffectInstance(
						net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 2000)));
			}
			case 240 -> {
				boolean pool = GrenadeEntity.inFirePool(level, c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5);
				boolean lit = GrenadeEntity.litByFire(level, c.above());
				log("molotov_pool", pool && lit, "pool=" + pool + " lit=" + lit);
				boolean near = net.minecraft.world.entity.SpawnPlacements.checkSpawnRules(EntityTypes.ZOMBIE, level, EntitySpawnReason.SPAWNER, c.above(2),
						level.getRandom());
				log("molotov_no_spawns", !near, "nearAllowed=" + near);
				var path = ((Mob) KEEP[1]).getNavigation().createPath(c.offset(7, 1, 0), 0);
				double closest = 99;
				for (int i = 0; path != null && i < path.getNodeCount(); i++) {
					var n = path.getNode(i);
					closest = Math.min(closest, Math.hypot(n.x + 0.5 - (c.getX() + 0.5), n.z + 0.5 - (c.getZ() + 0.5)));
				}
				log("molotov_pathing", path != null && closest > 3.0, "nodes=" + (path == null ? -1 : path.getNodeCount()) + " closest=" + String.format("%.2f", closest));
			}
			case 290 -> {
				ItemStack st = ((ItemEntity) KEEP[0]).getItem();
				log("molotov_cooks", st.is(Items.IRON_INGOT) && st.getCount() == 3, st.toString());
				clear(level);
			}
			// 4: flash: creeper fuse, enderman block / teleport, blind damage
			case 330 -> {
				KEEP[0] = spawn(level, EntityTypes.CREEPER, 3, 0, 0, m -> m.setNoAi(true));
				KEEP[1] = spawn(level, EntityTypes.ENDERMAN, -3, 0, 0, m -> {
					m.setNoAi(true);
					m.setCarriedBlock(Blocks.STONE.defaultBlockState());
				});
				grenade(level, p, "flashbang", 0, 0, 0, Vec3.ZERO);
			}
			case 352 -> ((net.minecraft.world.entity.monster.Creeper) KEEP[0]).setSwellDir(1);
			case 366 -> {
				var cr = (net.minecraft.world.entity.monster.Creeper) KEEP[0];
				var en = (net.minecraft.world.entity.monster.Enderman) KEEP[1];
				log("flash_creeper_fuse", cr.isAlive() && cr.getSwellDir() == -1, "swellDir=" + cr.getSwellDir());
				log("flash_enderman_block", en.getCarriedBlock() == null, "");
				boolean tp = en.teleport(en.getX() + 5, en.getY(), en.getZ());
				log("flash_enderman_teleport", !tp && MobVision.blinded(en), "teleported=" + tp + " blind=" + MobVision.blinded(en));
				float h0 = en.getHealth();
				en.hurtServer(level, level.damageSources().generic(), 4f);
				log("flash_blind_damage", Math.abs(h0 - en.getHealth() - 5f) < 0.01f, "took=" + (h0 - en.getHealth()));
			}
			case 380 -> clear(level);
			// 5: smoke calms bees, and counts as a campfire under a hive
			case 390 -> {
				KEEP[0] = spawn(level, EntityTypes.BEE, 1, 1, 0, b -> {
					b.setNoAi(true);
					b.startPersistentAngerTimer();
				});
				grenade(level, p, "smokegrenade", 0, 0, 0, Vec3.ZERO);
			}
			case 391 -> log("smoke_bee_setup", ((net.minecraft.world.entity.animal.bee.Bee) KEEP[0]).isAngry(), "");
			case 470 -> {
				var bee = (net.minecraft.world.entity.animal.bee.Bee) KEEP[0];
				log("smoke_calms_bees", !bee.isAngry(), "");
				boolean smokey = net.minecraft.world.level.block.CampfireBlock.isSmokeyPos(level, c.above(2));
				boolean far = net.minecraft.world.level.block.CampfireBlock.isSmokeyPos(level, c.offset(R - 1, 2, R - 1));
				log("smoke_hive", smokey && !far, "in=" + smokey + " out=" + far);
				clear(level);
			}
			// 6: underwater HE: dynamite fishing
			case 500 -> {
				fill(level, c.offset(-3, 1, -3), c.offset(3, 4, 3), Blocks.OBSIDIAN.defaultBlockState());
				fill(level, c.offset(-2, 1, -2), c.offset(2, 4, 2), Blocks.WATER.defaultBlockState());
			}
			case 505 -> {
				KEEP[0] = spawn(level, EntityTypes.COD, 1, 1, 0, null);
				KEEP[1] = spawn(level, EntityTypes.SQUID, -1, 1.5, 1, null);
				grenade(level, p, "hegrenade", 0, 1, 0, Vec3.ZERO);
			}
			case 545 -> {
				log("he_dynamite_fishing", !((Entity) KEEP[0]).isAlive() && !((Entity) KEEP[1]).isAlive(),
						"cod=" + ((Entity) KEEP[0]).isAlive() + " squid=" + ((Entity) KEEP[1]).isAlive());
				clear(level);
			}
			// 7: a grenade's impact: half a heart to a mob
			case 560 -> {
				fill(level, c.offset(-R, 1, -R), c.offset(R, 6, R), Blocks.AIR.defaultBlockState());
				KEEP[0] = spawn(level, EntityTypes.COW, 0, 0, 0, m -> m.addEffect(new net.minecraft.world.effect.MobEffectInstance(
						net.minecraft.world.effect.MobEffects.SLOWNESS, 2000, 10)));
			}
			case 570 -> grenade(level, p, "decoy", -2.5, 0.3, 0, new Vec3(500, 0, 0));
			case 590 -> {
				float h = ((Mob) KEEP[0]).getHealth();
				log("impact_half_heart", Math.abs(h - 9f) < 0.01f, "health=" + h);
				clear(level);
			}
			// 8: the decoy's pop hurts mobs next to it
			case 600 -> {
				KEEP[0] = spawn(level, EntityTypes.COW, 1.5, 0, 0, m -> m.addEffect(new net.minecraft.world.effect.MobEffectInstance(
						net.minecraft.world.effect.MobEffects.SLOWNESS, 2000, 10)));
				grenade(level, p, "decoy", 0, 0, 0, Vec3.ZERO);
			}
			case 960 -> {
				float took = 10f - ((Mob) KEEP[0]).getHealth();
				log("decoy_pop", took > 3f && took < 6f, "took=" + took);
				clear(level);
			}
			case 970 -> clear(level);
			// 9: the new achievements, earned for real (cleared first)
			case 980 -> {
				for (String n : TEST_ACH) {
					var h = level.getServer().getAdvancements().get(CounterCraft.id("cs2/" + n));
					if (h != null) {
						for (String cr : p.getAdvancements().getOrStartProgress(h).getCompletedCriteria()) {
							p.getAdvancements().revoke(h, cr);
						}
					}
				}
			}
			case 985 -> {
				KEEP[0] = spawn(level, EntityTypes.CREEPER, 3, 0, 0, m -> m.setNoAi(true));
				Vec3 from = new Vec3(c.getX() + 0.5, c.getY() + 1.8, c.getZ() + 0.5);
				dev.countercraft.gun.Ballistics.shoot(level, p, dev.countercraft.gun.Guns.ALL.get("taser"), from, new Vec3(1, 0, 0));
				log("ach_electrician", done(p, "electrician"), "");
				clear(level);
			}
			case 990 -> {
				for (int i = 0; i < 3; i++) {
					spawn(level, EntityTypes.COW, 2 + i * 1.2, 0, 0, m -> m.setNoAi(true));
				}
			}
			case 992 -> {
				Vec3 from = new Vec3(c.getX() + 0.5, c.getY() + 1.8, c.getZ() + 0.5);
				dev.countercraft.gun.Ballistics.shoot(level, p, dev.countercraft.gun.Guns.ALL.get("awp"), from, new Vec3(1, 0, 0));
				log("ach_collateral_damage", done(p, "collateral_damage"), "");
				clear(level);
			}
			case 1000 -> {
				for (int i = 0; i < 5; i++) {
					KEEP[i] = spawn(level, EntityTypes.HUSK, 3, 0, -2 + i, m -> {
						m.setNoAi(true);
						m.setHealth(1f);
					});
				}
			}
			case 1002 -> {
				Vec3 from = new Vec3(c.getX() + 0.5, c.getY() + 2.0, c.getZ() + 0.5);
				for (int i = 0; i < 5; i++) {
					Vec3 to = ((Entity) KEEP[i]).getBoundingBox().getCenter();
					dev.countercraft.gun.Ballistics.shoot(level, p, dev.countercraft.gun.Guns.ALL.get("ak47"), from, to.subtract(from).normalize());
				}
				log("ach_ace", done(p, "ace"), "");
				clear(level);
			}
			case 1010 -> {
				for (int i = 0; i < 5; i++) {
					double a = i * Math.PI * 2 / 5;
					spawn(level, EntityTypes.HUSK, Math.cos(a) * 3, 0, Math.sin(a) * 3, m -> m.setNoAi(true));
				}
				KEEP[0] = spawn(level, EntityTypes.CREEPER, 0, 0, 2, m -> m.setNoAi(true));
				grenade(level, p, "flashbang", 0, 0, 0, Vec3.ZERO);
			}
			case 1032 -> ((net.minecraft.world.entity.monster.Creeper) KEEP[0]).setSwellDir(1);
			case 1046 -> {
				log("ach_flash_photography", done(p, "flash_photography"), "");
				log("ach_tactical_retreat", done(p, "tactical_retreat"), "");
				clear(level);
			}
			// a decoy for its pop (15 s), a husk chasing you into a smoke, and a decoy through a window
			case 1060 -> {
				KEEP[0] = spawn(level, EntityTypes.HUSK, 1.5, 0, 0, m -> {
					m.setNoAi(true);
					m.setHealth(1f);
				});
				grenade(level, p, "decoy", 0, 0, 0, Vec3.ZERO);
				// (mobs don't keep a creative player as their target: survival for this one)
				p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
				KEEP[1] = spawn(level, EntityTypes.HUSK, -7, 0, -7, m -> m.setNoAi(true));
				grenade(level, p, "smokegrenade", -7, 0, -7, Vec3.ZERO);
				fill(level, c.offset(2, 1, 6), c.offset(2, 2, 6), Blocks.GLASS.defaultBlockState());
				KEEP[2] = spawn(level, EntityTypes.HUSK, 4.5, 0, 6, m -> {
					m.setNoAi(true);
					m.setHealth(0.5f);
				});
			}
			case 1062 -> {
				((Mob) KEEP[1]).setTarget(p);
				grenade(level, p, "decoy", 0, 0.5, 6, new Vec3(500, 0, 0));
			}
			case 1090 -> {
				log("ach_to_the_window", done(p, "to_the_window") && level.getBlockState(c.offset(2, 1, 6)).isAir(), "");
				log("ach_bonk", done(p, "bonk"), "");
			}
			case 1200 -> {
				log("ach_smoke_screen", done(p, "smoke_screen"), "");
				p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
			}
			case 1430 -> {
				log("ach_deforestation_department", done(p, "deforestation_department"), "dead=" + !((Entity) KEEP[0]).isAlive());
				clear(level);
				fill(level, c.offset(-R, 1, -R), c.offset(R, 6, R), Blocks.AIR.defaultBlockState());
			}
			case 1440 -> {
				for (int i = 0; i < 10; i++) {
					double a = i * Math.PI * 2 / 10;
					double r = 1.0 + (i % 2) * 1.2;
					spawn(level, EntityTypes.HUSK, Math.cos(a) * r, 0, Math.sin(a) * r, m -> {
						m.setNoAi(true);
						m.setHealth(1f);
					});
				}
				grenade(level, p, "molotov", 0, 1, 0, new Vec3(0, -250, 0));
			}
			case 1500 -> {
				log("ach_the_floor_is_lava", done(p, "the_floor_is_lava"), "");
				// at night (the time put back after): half the money doesn't do it, the rest does
				long t = Math.floorMod(level.getOverworldClockTime(), 24000L);
				var src = level.getServer().createCommandSourceStack().withSuppressedOutput();
				level.getServer().getCommands().performPrefixedCommand(src, "time set 14000");
				Achievements.bounty(p, Achievements.NIGHT_MONEY / 2);
				boolean half = done(p, "money_talks");
				Achievements.bounty(p, Achievements.NIGHT_MONEY / 2);
				log("ach_money_talks", !half && done(p, "money_talks"), "");
				level.getServer().getCommands().performPrefixedCommand(src, "time set " + t);
				clear(level);
			}
			// 10: the C4 for real: the slot opened, planted with mouse 1 (the client presses it once the draw is over),
			// defused with E (the client holds it once it aims at the bomb); then one left to blow up next to a husk
			case 1510 -> {
				KEEP[6] = p.position();
				tp(level, p, c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5, 0f);
				// /cc give beta c4 is refused with the test off, gives one with it on
				var console = level.getServer().createCommandSourceStack().withEntity(p);
				level.getServer().getCommands().performPrefixedCommand(console, "cc give beta c4");
				boolean refused = dev.countercraft.team.CsInventory.of(p).get(dev.countercraft.team.CsInventory.C4).isEmpty();
				dev.countercraft.command.BetaCommand.setC4Test(level.getServer(), true);
				level.getServer().getCommands().performPrefixedCommand(console, "cc give beta c4");
				log("c4_give_gate", refused && dev.countercraft.team.CsInventory.of(p).get(dev.countercraft.team.CsInventory.C4).getItem()
						instanceof dev.countercraft.gun.C4Item, "refused off=" + refused);
				if (!dev.countercraft.team.CsInventory.active(p)) {
					dev.countercraft.team.CsInventory.toggle(p);
				}
				p.getInventory().setSelectedSlot(dev.countercraft.team.CsInventory.C4);
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(dev.countercraft.team.CsInventory.C4));
				// with it on, the kit is for sale to CTs (and halves the defuse below), not to Ts
				String team = dev.countercraft.team.Teams.of(p);
				p.setAttached(dev.countercraft.team.Teams.TEAM, dev.countercraft.team.Teams.T);
				dev.countercraft.team.Buying.buy(p, "item_defuser");
				boolean t = Combat.hasDefuser(p);
				p.setAttached(dev.countercraft.team.Teams.TEAM, dev.countercraft.team.Teams.CT);
				dev.countercraft.team.Buying.buy(p, "item_defuser");
				p.setAttached(dev.countercraft.team.Teams.TEAM, team);
				log("c4_kit_buy_gate", Boolean.FALSE.equals(KEEP[7]) && !t && Combat.hasDefuser(p),
						"off=" + KEEP[7] + " T=" + t + " CT=" + Combat.hasDefuser(p));
			}
			case 1620 -> {
				var bombs = level.getEntitiesOfClass(dev.countercraft.gun.PlantedC4.class, new AABB(c).inflate(R));
				KEEP[0] = bombs.isEmpty() ? null : bombs.getFirst();
				log("c4_plant_mouse1", KEEP[0] != null && dev.countercraft.team.CsInventory.of(p).get(dev.countercraft.team.CsInventory.C4).isEmpty(),
						"bombs=" + bombs.size());
				// step back (2.5 blocks, in reach) and look down at it
				tp(level, p, c.getX() + 0.5, c.getY() + 1, c.getZ() - 2.0, 30.5f);
			}
			case 1650 -> {
				// a cow walks in front mid-defuse, between the player and the bomb: the locked-in defuse goes on
				KEEP[5] = spawn(level, EntityTypes.COW, 0, 0, -1.25, m -> m.setNoAi(true));
			}
			case 1740 -> {
				var bomb = (dev.countercraft.gun.PlantedC4) KEEP[0];
				log("c4_defused_e_kit", bomb != null && bomb.isRemoved(), "within 5 s of holding E, a cow in front from 1.5 s");
				((Entity) KEEP[5]).discard();
			}
			case 1850 -> {
				tp(level, p, c.getX() + 0.5, c.getY() + 1, c.getZ() + 6.5, 0f);
				dev.countercraft.gun.PlantedC4.plant(level, p);
				Vec3 back = (Vec3) KEEP[6];
				tp(level, p, back.x, back.y, back.z, 0f);
				KEEP[1] = spawn(level, EntityTypes.HUSK, 1.5, 0, 6, m -> m.setNoAi(true));
				// one 11 blocks away behind a wall: the blast goes through walls
				fill(level, c.offset(-3, 1, 1), c.offset(3, 3, 1), Blocks.OBSIDIAN.defaultBlockState());
				KEEP[2] = spawn(level, EntityTypes.HUSK, 0, 0, -4.5, m -> m.setNoAi(true));
				// one sealed in obsidian 9 blocks off, well inside the instant kill in a straight line: walls stop the wave
				fill(level, c.offset(8, 1, 5), c.offset(10, 3, 7), Blocks.OBSIDIAN.defaultBlockState());
				fill(level, c.offset(9, 1, 6), c.offset(9, 2, 6), Blocks.AIR.defaultBlockState());
				KEEP[4] = spawn(level, EntityTypes.HUSK, 9, 0, 6, m -> m.setNoAi(true));
				// the bands: a husk 50 blocks out (16 damage), and the player in survival, 30 blocks out (lethal) for a
				// moment, then 70 (10 damage) when it goes off; pads to stand on
				fill(level, c.offset(0, 0, 34), c.offset(0, 0, 34), Blocks.OBSIDIAN.defaultBlockState());
				KEEP[3] = spawn(level, EntityTypes.HUSK, 0, 0, 34, m -> m.setNoAi(true));
				fill(level, c.offset(-1, 0, 20), c.offset(1, 0, 22), Blocks.OBSIDIAN.defaultBlockState());
				fill(level, c.offset(-1, 0, 44), c.offset(1, 0, 46), Blocks.OBSIDIAN.defaultBlockState());
				p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
				p.setHealth(p.getMaxHealth());
				tp(level, p, c.getX() + 0.5, c.getY() + 1, c.getZ() + 21.5, 0f);
				// the damage curve: lethal close in, then easing off with every block out to nothing at the edge
				boolean falls = true;
				for (double d = dev.countercraft.gun.PlantedC4.CERTAIN_DEATH + 0.25; d <= dev.countercraft.gun.PlantedC4.BLAST_RADIUS; d += 0.25) {
					falls &= dev.countercraft.gun.PlantedC4.damageAt(d) < dev.countercraft.gun.PlantedC4.damageAt(d - 0.25);
				}
				log("c4_curve", falls && dev.countercraft.gun.PlantedC4.damageAt(5) == dev.countercraft.gun.PlantedC4.LETHAL
						&& dev.countercraft.gun.PlantedC4.damageAt(22) == 19f && dev.countercraft.gun.PlantedC4.damageAt(27.5) == 16f
						&& Math.abs(dev.countercraft.gun.PlantedC4.damageAt(30) - 14.636f) < 0.01f
						&& dev.countercraft.gun.PlantedC4.damageAt(68.75) == 0f && dev.countercraft.gun.PlantedC4.damageAt(70) == 0f,
						"30 m: " + dev.countercraft.gun.PlantedC4.damageAt(30));
			}
			case 1990 -> {
				// facing the bomb (the blast debug view is on with the test)
				p.teleportTo(level, c.getX() + 0.5, c.getY() + 1, c.getZ() + 45.5, java.util.Set.of(), 180f, 8f, true);
			}
			case 2652 -> {
				// the shockwave travels: 27.5 blocks out takes it half a second
				Mob far = (Mob) KEEP[3];
				log("c4_wave_travels", far.isAlive() && far.getHealth() == far.getMaxHealth(), "health=" + far.getHealth());
			}
			case 2675 -> {
				float hp = p.getHealth();
				log("c4_player_hurt", hp >= 9.5f && hp <= 11f, "health=" + hp);
				// in the open the wave's distance is the straight one: the curve's damage at exactly that distance
				Mob mid = (Mob) KEEP[3];
				double d = mid.getBoundingBox().getCenter().distanceTo(new Vec3(c.getX() + 0.5, c.getY() + 1.25, c.getZ() + 6.5));
				float want = mid.getMaxHealth() - dev.countercraft.gun.PlantedC4.damageAt(d);
				log("c4_curve_hit", mid.isAlive() && Math.abs(mid.getHealth() - want) < 0.05f,
						String.format("%.1f m: health=%.2f want=%.2f", d, mid.getHealth(), want));
				Vec3 back = (Vec3) KEEP[6];
				tp(level, p, back.x, back.y, back.z, 0f);
				p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
				p.setHealth(p.getMaxHealth());
				fill(level, c.offset(-1, 0, 20), c.offset(1, 0, 46), Blocks.AIR.defaultBlockState());
			}
			case 2680 -> {
				Mob sealed = (Mob) KEEP[4];
				log("c4_walls_block", sealed.isAlive() && sealed.getHealth() == sealed.getMaxHealth(), "health=" + sealed.getHealth());
				log("c4_explodes", !((Entity) KEEP[1]).isAlive() && level.getBlockState(c.offset(0, 0, 6)).is(Blocks.OBSIDIAN)
						&& level.getEntitiesOfClass(dev.countercraft.gun.PlantedC4.class, new AABB(c).inflate(R)).isEmpty(), "");
				log("c4_around_walls", !((Entity) KEEP[2]).isAlive() && level.getBlockState(c.offset(0, 2, 1)).is(Blocks.OBSIDIAN), "");
				clear(level);
				// the test switched off: a planted bomb, a carried C4, a dropped one and one in the inventory all go
				dev.countercraft.gun.PlantedC4.plant(level, p);
				KEEP[0] = level.getEntitiesOfClass(dev.countercraft.gun.PlantedC4.class, p.getBoundingBox().inflate(3)).getFirst();
				dev.countercraft.team.CsInventory.give(p, new ItemStack(CounterCraft.C4));
				KEEP[1] = new ItemEntity(level, p.getX(), p.getY() + 1, p.getZ(), new ItemStack(CounterCraft.C4));
				level.addFreshEntity((Entity) KEEP[1]);
				p.getInventory().add(new ItemStack(CounterCraft.C4));
				dev.countercraft.command.BetaCommand.setC4Test(level.getServer(), false);
			}
			case 2690 -> {
				boolean gone = ((Entity) KEEP[0]).isRemoved() && ((Entity) KEEP[1]).isRemoved()
						&& dev.countercraft.team.CsInventory.of(p).get(dev.countercraft.team.CsInventory.C4).isEmpty()
						&& !p.getInventory().hasAnyMatching(s -> s.getItem() instanceof dev.countercraft.gun.C4Item);
				log("c4_test_off", gone && !Buying.allowed(p, dev.countercraft.team.Teams.CT, "item_defuser"), "all gone=" + gone);
			}
			case 2700 -> {
				fill(level, c.offset(-R, 0, -R), c.offset(R, 6, R), Blocks.AIR.defaultBlockState());
				clear(level);
				CounterCraft.LOG.info("[countercraft] selftest done");
			}
			default -> {
			}
		}
	}
}
