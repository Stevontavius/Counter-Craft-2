package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import dev.countercraft.ServerCvars;
import dev.countercraft.net.FirePayload;
import dev.countercraft.net.ShotFxPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A CS2 gun. Firing doesn't go through vanilla {@link #use}: the client sends {@code FirePayload}s (every tick the
 * fire key is held for automatics, once per press for semi-autos) and the server calls {@link #trigger}, which
 * enforces the fire rate. Reload and alt-fire (zoom / burst / silencer) are their own packets.
 */
public class GunItem extends Item implements CsWeapon {
	private static final Map<UUID, Shooter> SHOOTERS = new ConcurrentHashMap<>();
	/** CS2's Zeus x27 recharges 30 s after being used. */
	public static final int TASER_RECHARGE_TICKS = 600;
	/** R8 primary fire: the hammer is pulled for 0.2 s once the gun is ready (measured: held-trigger shots 0.70 s apart). */
	public static final int REVOLVER_PREP_TICKS = 4;
	/** Silencer on/off takes about two seconds in CS2. */
	private static final int SILENCER_TICKS = 40;
	private static final Identifier SCOPE_SPEED = CounterCraft.id("scope_speed");

	private final GunSpec spec;
	private final SoundEvent fireSound;
	private final SoundEvent fireUnsilenced;

	/** Server-side per-player firing state. */
	private static final class Shooter {
		RecoilPattern.State recoil = new RecoilPattern.State();
		String gun = "";
		int burstLeft;
		double burstNext;
		long lastTrigger = Long.MIN_VALUE / 2;
		long triggerStart;
		/** Bullets fired, for CS2's every-Nth-bullet tracers. */
		int tracerCount;
		/** Dual Berettas: the next shot is the left gun's (they alternate, right first). */
		boolean leftNext;
		/** The shooter's movement from their last trigger pull (burst follow-ups reuse it). */
		FirePayload.Motion motion;
	}

	public GunItem(Properties properties, GunSpec spec, SoundEvent fireSound, SoundEvent fireUnsilenced) {
		super(properties);
		this.spec = spec;
		this.fireSound = fireSound;
		this.fireUnsilenced = fireUnsilenced;
	}

	public GunSpec spec() {
		return spec;
	}

	@Override
	public String weaponId() {
		return spec.id();
	}

	@Override
	public float deploySeconds() {
		return spec.deploy();
	}

	/**
	 * When a shot that became allowed at {@code next} actually counts as fired: CS2 keeps the cadence of a held trigger
	 * (a shot due within the last tick or two fires "at" its due time), so a 0.07 s cycle really is 857 RPM on a
	 * 20 Hz tick instead of rounding up to every second tick. A fresh click fires now: backdating it would let the
	 * next held shot follow one tick later, so spam-clicking out-fired holding the trigger.
	 */
	public static double shotTime(double next, double now, boolean held) {
		return held && next > now - 2.0 ? Math.min(next, now) : now;
	}

	public static void forget(UUID player) {
		SHOOTERS.remove(player);
		Accuracy.forget(player);
	}

	private Shooter shooter(ServerPlayer p) {
		Shooter s = SHOOTERS.computeIfAbsent(p.getUUID(), u -> new Shooter());
		if (!s.gun.equals(spec.id())) {
			// a different gun has its own pattern: start fresh
			s.gun = spec.id();
			s.burstLeft = 0;
			s.recoil = new RecoilPattern.State();
		}
		return s;
	}

	/**
	 * Alternate stats: scoped, burst mode, or the silencer on (CS2's vdata pairs list a silencer weapon's silenced
	 * values second: the M4A1-S's recoil 25 / 21, spread 0.0006 / 0.0005, tracers every 3rd / none).
	 */
	public boolean altMode(ItemStack stack) {
		return (spec.zoom().scoped() && stack.getOrDefault(ModComponents.ZOOM, 0) > 0)
				|| (spec.burst().enabled() && stack.getOrDefault(ModComponents.BURST, false))
				|| silenced(stack);
	}

	/** A detachable silencer that's on (CS2's default for the M4A1-S and USP-S). */
	public boolean silenced(ItemStack stack) {
		return "detachable".equals(spec.silencer()) && !stack.getOrDefault(ModComponents.SILENCER_OFF, false);
	}

	/** Guns never break blocks (like swords in creative); left click fires instead. */
	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
		return false;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		// Swallow the vanilla use so nothing else (offhand blocks, food) triggers; alt-fire is its own key.
		return InteractionResult.CONSUME;
	}

	/** Server side: one trigger pull from the client ({@code alt} = R8 fan fire). */
	public void trigger(ServerPlayer player, ItemStack stack, FirePayload pull) {
		boolean altFire = pull.alt();
		long now = player.level().getGameTime();
		Shooter sh = shooter(player);
		sh.motion = pull.motion();
		if (altFire && !spec.revolver() || !Deploy.ready(player)) {
			return;
		}
		if (spec.revolver() && !altFire) {
			// hold to pull the hammer: the pull starts once the gun is ready, then it fires (and repeats while held)
			boolean ready = now + 1e-6 >= stack.getOrDefault(ModComponents.NEXT_SHOT, 0.0);
			if (now - sh.lastTrigger > 2) {
				sh.triggerStart = now;
			} else if (!ready) {
				sh.triggerStart = now + 1;
			}
			sh.lastTrigger = now;
			if (now - sh.triggerStart < REVOLVER_PREP_TICKS) {
				return;
			}
		}
		fire(player, stack, altFire, !pull.fresh(), now, sh);
	}

	private boolean fire(ServerPlayer player, ItemStack stack, boolean altFire, boolean held, long now, Shooter sh) {
		ServerLevel level = player.level();
		int ammo = stack.getOrDefault(ModComponents.AMMO, 0);
		double next = stack.getOrDefault(ModComponents.NEXT_SHOT, 0.0);
		if (now + 1e-6 < next || sh.burstLeft > 0) {
			return false;
		}
		if (stack.has(ModComponents.RELOAD_END)) {
			if (spec.singleShellReload() && ammo > 0) {
				endReload(stack); // a shotgun reload is interrupted by firing
			} else {
				return false;
			}
		}
		if (ammo <= 0) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.6f);
			stack.set(ModComponents.NEXT_SHOT, now + 5.0);
			startReload(level, player, stack);
			return false;
		}
		boolean alt = altFire || altMode(stack);
		double shotAt = shotTime(next, now, held);
		round(level, player, stack, alt, shotAt, now, sh);
		boolean burst = spec.burst().enabled() && stack.getOrDefault(ModComponents.BURST, false);
		if (burst) {
			sh.burstLeft = Math.min(2, stack.getOrDefault(ModComponents.AMMO, 0));
			sh.burstNext = shotAt + spec.burst().between() * 20.0;
			stack.set(ModComponents.NEXT_SHOT, shotAt + spec.burst().cycle() * 20.0);
		} else {
			stack.set(ModComponents.NEXT_SHOT, shotAt + spec.cycleTicks(altFire));
		}
		int zoom = stack.getOrDefault(ModComponents.ZOOM, 0);
		if (spec.zoom().unzoomAfterShot() && zoom > 0) {
			stack.set(ModComponents.ZOOM, 0);
			stack.set(ModComponents.REZOOM_LEVEL, zoom);
			stack.set(ModComponents.REZOOM_AT, now + (long) Math.ceil(spec.cycleTicks(false)));
		}
		if (spec.exhaustible() && stack.getOrDefault(ModComponents.AMMO, 0) <= 0 && ServerCvars.infiniteAmmo != 1) {
			stack.set(ModComponents.RELOAD_END, now + TASER_RECHARGE_TICKS);
		}
		return true;
	}

	/** One round: recoil, inaccuracy, every pellet, ammo, sound. */
	private void round(ServerLevel level, ServerPlayer player, ItemStack stack, boolean alt, double shotAt, long now, Shooter sh) {
		float[] kick = sh.recoil.shoot(spec.recoil(), shotAt, alt);
		AccuracySpec acc = spec.accuracy();
		float inaccuracy = Accuracy.shoot(player, acc, alt, sh.recoil.shotIndex - 1, sh.motion);
		float spread = AccuracySpec.pick(acc.spread(), alt);
		boolean silenced = silenced(stack) || "integrated".equals(spec.silencer());
		int every = spec.fx().tracerEvery(silenced(stack));
		List<Vec3> ends = new ArrayList<>();
		int tracers = 0;
		boolean scopedGun = spec.zoom().scoped() && spec.ironsight() == null;
		dev.countercraft.Achievements.fired(player, this);
		Ballistics.shotIcon = spec.id() + (("detachable".equals(spec.silencer()) && !silenced(stack)) ? "_off" : "");
		Ballistics.shotFlags = dev.countercraft.Combat.attackerFlags(player)
				| (scopedGun && stack.getOrDefault(ModComponents.ZOOM, 0) == 0 ? dev.countercraft.net.KillFeedPayload.F_NOSCOPE : 0);
		for (int i = 0; i < Math.max(1, spec.pellets()); i++) {
			Ballistics.ShotResult r = Ballistics.shoot(level, player, spec, kick, Accuracy.offset(inaccuracy, spread, player.getRandom()));
			if (i < 32) {
				ends.add(r.hitPos());
				if (every > 0 && sh.tracerCount % every == 0) {
					tracers |= 1 << i;
				}
			}
			sh.tracerCount++;
		}
		boolean left = false;
		if ("elite".equals(spec.id())) {
			left = sh.leftNext;
			sh.leftNext = !sh.leftNext;
		}
		dev.countercraft.RunStats.add(player, "shots", 1);
		dev.countercraft.GunNoise.shot(player, silenced); // nearby monsters hear it
		shotFx(player, new ShotFxPayload(player.getId(), spec.id(), (silenced ? ShotFxPayload.SILENCED : 0)
				| (left || silenced(stack) ? ShotFxPayload.ALT_MUZZLE : 0), ends, tracers));
		if (ServerCvars.infiniteAmmo != 1) {
			stack.set(ModComponents.AMMO, stack.getOrDefault(ModComponents.AMMO, 0) - 1);
		}
		dev.countercraft.team.Refunds.used(stack); // fired: no refund
		boolean unsilenced = "detachable".equals(spec.silencer()) && stack.getOrDefault(ModComponents.SILENCER_OFF, false);
		// the shooter plays their own shot locally (no network delay); everyone else hears it from here
		level.playSound(player, player.getX(), player.getEyeY(), player.getZ(), unsilenced && fireUnsilenced != null ? fireUnsilenced : fireSound,
				SoundSource.PLAYERS, 2.0f, 1.0f);
	}

	/** Muzzle flash and tracers: to everyone who can see the shooter, and the shooter (their tracers). */
	private static void shotFx(ServerPlayer shooter, ShotFxPayload fx) {
		for (ServerPlayer p : PlayerLookup.tracking(shooter)) {
			if (p != shooter && ServerPlayNetworking.canSend(p, ShotFxPayload.TYPE)) {
				ServerPlayNetworking.send(p, fx);
			}
		}
		if (ServerPlayNetworking.canSend(shooter, ShotFxPayload.TYPE)) {
			ServerPlayNetworking.send(shooter, fx);
		}
	}

	/** Server tick: follow-up shots of Glock/FAMAS bursts. */
	public static void tickBursts(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Shooter sh = SHOOTERS.get(p.getUUID());
			if (sh == null || sh.burstLeft <= 0) {
				continue;
			}
			ItemStack stack = p.getMainHandItem();
			long now = p.level().getGameTime();
			if (!(stack.getItem() instanceof GunItem gun) || !gun.spec.id().equals(sh.gun) || stack.getOrDefault(ModComponents.AMMO, 0) <= 0) {
				sh.burstLeft = 0;
				continue;
			}
			while (sh.burstLeft > 0 && now + 1e-6 >= sh.burstNext) {
				gun.round(p.level(), p, stack, true, sh.burstNext, now, sh);
				sh.burstLeft--;
				sh.burstNext += gun.spec.burst().between() * 20.0;
			}
		}
	}

	public void startReload(ServerLevel level, ServerPlayer player, ItemStack stack) {
		int ammo = stack.getOrDefault(ModComponents.AMMO, 0);
		int reserve = stack.getOrDefault(ModComponents.RESERVE, 0);
		if (spec.exhaustible() || stack.has(ModComponents.RELOAD_END) || ammo >= spec.magSize()) {
			return;
		}
		if (reserve <= 0) {
			// reserve dry: bought rounds of this gun's caliber, if any (ExtraAmmo)
			int got = dev.countercraft.team.ExtraAmmo.take(player, this, spec.magSize() - ammo);
			if (got <= 0) {
				return;
			}
			reserve = got;
			stack.set(ModComponents.RESERVE, reserve);
		}
		long now = level.getGameTime();
		GunSpec.ShellReload shells = spec.shellReload();
		if (spec.singleShellReload() && shells != null) {
			// CS2: intro, then one shell per loop of the reload clip; firing may only interrupt after the disallow time
			stack.set(ModComponents.RELOAD_START, now);
			stack.set(ModComponents.RELOAD_SHELLS, 0);
			stack.set(ModComponents.RELOAD_END, now + Math.round(shells.shellAt(0) * 20.0));
			stack.set(ModComponents.NEXT_SHOT, Math.max(stack.getOrDefault(ModComponents.NEXT_SHOT, 0.0), now + spec.reloadTime() * 20.0));
		} else {
			stack.set(ModComponents.RELOAD_END, now + spec.reloadTicks());
		}
		stack.set(ModComponents.ZOOM, 0); // reloading unzooms, like CS2
		stack.remove(ModComponents.REZOOM_AT);
		dev.countercraft.AgentAnims.broadcast(player, "action_reload", "", spec.reloadTime());
	}

	private static void endReload(ItemStack stack) {
		stack.remove(ModComponents.RELOAD_END);
		stack.remove(ModComponents.RELOAD_START);
		stack.remove(ModComponents.RELOAD_SHELLS);
	}

	/** Alt-fire key: cycle the scope, toggle burst, or screw the silencer on/off. */
	public void alt(ServerPlayer player, ItemStack stack) {
		ServerLevel level = player.level();
		long now = level.getGameTime();
		if (spec.zoom().scoped()) {
			if (stack.has(ModComponents.RELOAD_END)) {
				return;
			}
			int z = (stack.getOrDefault(ModComponents.ZOOM, 0) + 1) % (spec.zoom().levels() + 1);
			stack.set(ModComponents.ZOOM, z);
			stack.remove(ModComponents.REZOOM_AT);
			String snd = z > 0 ? spec.sounds().zoomIn() : spec.sounds().zoomOut();
			if (snd != null) {
				level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id(snd)),
						SoundSource.PLAYERS, 1f, 1f);
			}
		} else if (spec.burst().enabled()) {
			boolean b = !stack.getOrDefault(ModComponents.BURST, false);
			stack.set(ModComponents.BURST, b);
			player.sendOverlayMessage(Component.literal(b ? "Switched to burst-fire mode" : "Switched to " + (spec.automatic() ? "automatic" : "semi-automatic")));
		} else if ("detachable".equals(spec.silencer())) {
			if (stack.has(ModComponents.RELOAD_END) || now < stack.getOrDefault(ModComponents.NEXT_SHOT, 0.0)) {
				return;
			}
			boolean off = !stack.getOrDefault(ModComponents.SILENCER_OFF, false);
			stack.set(ModComponents.SILENCER_OFF, off);
			stack.set(ModComponents.NEXT_SHOT, (double) (now + SILENCER_TICKS));
			player.sendOverlayMessage(Component.literal(off ? "Silencer removed" : "Silencer attached"));
		}
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		long now = level.getGameTime();
		boolean mainhand = slot == EquipmentSlot.MAINHAND;
		if (entity instanceof ServerPlayer player) {
			if (mainhand) {
				scopeSpeed(player, stack);
				Long rezoom = stack.get(ModComponents.REZOOM_AT);
				if (rezoom != null && now >= rezoom) {
					stack.set(ModComponents.ZOOM, stack.getOrDefault(ModComponents.REZOOM_LEVEL, 1));
					stack.remove(ModComponents.REZOOM_AT);
				}
			} else if (stack.getOrDefault(ModComponents.ZOOM, 0) > 0 || stack.has(ModComponents.REZOOM_AT)) {
				stack.set(ModComponents.ZOOM, 0);
				stack.remove(ModComponents.REZOOM_AT);
				if (!(player.getMainHandItem().getItem() instanceof GunItem)) {
					clearScopeSpeed(player);
				}
			}
		}
		Long end = stack.get(ModComponents.RELOAD_END);
		if (end == null) {
			return;
		}
		if (spec.exhaustible()) {
			// the Zeus recharges wherever it is
			if (now >= end) {
				stack.set(ModComponents.AMMO, 1);
				stack.remove(ModComponents.RELOAD_END);
			}
			return;
		}
		if (!mainhand) {
			endReload(stack); // switching away cancels the reload, like CS2
			return;
		}
		int ammo = stack.getOrDefault(ModComponents.AMMO, 0);
		int reserve = stack.getOrDefault(ModComponents.RESERVE, 0);
		GunSpec.ShellReload shells = spec.shellReload();
		if (spec.singleShellReload() && shells != null) {
			Long start = stack.get(ModComponents.RELOAD_START);
			if (start == null) {
				endReload(stack); // a reload saved mid-way: just stop it
				return;
			}
			int loaded = stack.getOrDefault(ModComponents.RELOAD_SHELLS, 0);
			if (now < start + Math.round(shells.shellAt(loaded) * 20.0)) {
				return;
			}
			int take = Math.min(1, reserve);
			stack.set(ModComponents.AMMO, ammo + take);
			if (ServerCvars.infiniteAmmo != 2) {
				stack.set(ModComponents.RESERVE, reserve - take);
			}
			if (ammo + take < spec.magSize() && reserve - take > 0) {
				stack.set(ModComponents.RELOAD_SHELLS, loaded + 1);
				stack.set(ModComponents.RELOAD_END, start + Math.round(shells.shellAt(loaded + 1) * 20.0));
			} else {
				endReload(stack);
			}
			return;
		}
		if (now < end) {
			return;
		}
		int take = spec.singleShellReload() ? Math.min(1, reserve) : Math.min(spec.magSize() - ammo, reserve);
		stack.set(ModComponents.AMMO, ammo + take);
		if (ServerCvars.infiniteAmmo != 2) {
			stack.set(ModComponents.RESERVE, reserve - take);
		}
		if (spec.singleShellReload() && ammo + take < spec.magSize() && reserve - take > 0) {
			stack.set(ModComponents.RELOAD_END, now + spec.reloadTicks()); // next shell
		} else {
			stack.remove(ModComponents.RELOAD_END);
		}
	}

	private void scopeSpeed(ServerPlayer player, ItemStack stack) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		float[] max = spec.maxSpeed();
		if (spec.zoom().scoped() && stack.getOrDefault(ModComponents.ZOOM, 0) > 0 && max[1] < max[0]) {
			speed.addOrUpdateTransientModifier(new AttributeModifier(SCOPE_SPEED, max[1] / max[0] - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else if (speed.hasModifier(SCOPE_SPEED)) {
			speed.removeModifier(SCOPE_SPEED);
		}
	}

	private static void clearScopeSpeed(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null && speed.hasModifier(SCOPE_SPEED)) {
			speed.removeModifier(SCOPE_SPEED);
		}
	}

}
