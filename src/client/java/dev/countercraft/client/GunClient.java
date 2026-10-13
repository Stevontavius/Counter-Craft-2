package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import dev.countercraft.client.fx.ShotEffects;
import dev.countercraft.client.movement.CsPlayerMovement;
import dev.countercraft.client.viewmodel.ViewmodelAnimator;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import dev.countercraft.client.viewmodel.ViewmodelBob;
import dev.countercraft.client.viewmodel.ViewmodelRenderer;
import dev.countercraft.gun.Ballistics;
import dev.countercraft.gun.CsWeapon;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import dev.countercraft.gun.KnifeItem;
import dev.countercraft.gun.GunSpec;
import dev.countercraft.gun.Inaccuracy;
import dev.countercraft.client.movement.MovementKeys;
import dev.countercraft.gun.RecoilPattern;
import dev.countercraft.net.AltPayload;
import dev.countercraft.net.FirePayload;
import dev.countercraft.net.ReloadPayload;
import dev.countercraft.net.ThrowPayload;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client half of the guns: sends trigger pulls, predicts its own shots (sound, animation, recoil) so firing feels
 * instant, drives the viewmodel animation, renders it in place of the vanilla hand, and handles the scope view.
 */
public final class GunClient {
	/**
	 * Viewmodels (3-4 MB .cvm files) load on a background thread, ahead of their first draw when they can be predicted
	 * (your inventory, the buy menu: {@link #prefetch}); with their textures ({@link dev.countercraft.client.gpu.TextureStream}).
	 */
	private static final Map<String, Pending> ASSETS = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "countercraft viewmodel loader");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	/** One viewmodel's load: whoever claims it first (the loader, or the render thread when it can't wait) reads it. */
	private static final class Pending {
		final java.util.concurrent.CompletableFuture<Optional<ViewmodelAsset>> done = new java.util.concurrent.CompletableFuture<>();
		final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
	}
	private static final ViewmodelRenderer RENDERER = new ViewmodelRenderer();
	/** Molotov: the lighter reaches the rag this far into the pull-pin clip (measured on the baked clip). */
	private static final float RAG_IGNITES = 0.35f;
	private static final float LIGHTER_OPEN = 0.1f;
	private static final float LIGHTER_SHUT = 0.9f;

	private static RecoilPattern.State recoil = new RecoilPattern.State();
	private static ViewmodelAnimator animator;
	/** AUG / SG 553: a second animator holding the aim-down-sights pose; blended in by {@link #adsWeight}. */
	private static ViewmodelAnimator adsAnimator;
	private static float adsWeight;
	private static long lastFrameNanos;
	private static String heldGun = "";
	private static int heldSlot = -1;
	private static Long lastReloadEnd;
	private static boolean wasFire;
	private static boolean wasAlt;
	private static long tick;
	private static double nextShot;
	/** End of the current draw (CS2 deploy time): nothing can be used before it. */
	private static double drawEnd;
	private static int predictedAmmo;
	private static int lastSeenAmmo;
	/** R8: trigger packets sent since the gun was ready (the server counts the same packets: GunItem.trigger). */
	private static int prepPulls;
	private static int burstLeft;
	private static double burstNext;
	private static boolean leftNext;
	private static boolean primed;
	/** The pin is pulled: the throw strength right now, following the held buttons (see {@link #STRENGTH_RATE}). */
	private static float primedStrength;
	/** Both buttons were held last tick; while > 0, a one-button release is still in its grace (ticks left). */
	private static boolean primedBoth;
	private static int releaseGrace;
	/**
	 * How fast the throw strength follows a change of buttons while the pin is pulled, strength per second. Measured in
	 * CS2 (measure/nade_strength.py: 27 decoy throws, strength read off the practice trail): a 0.5 change (full to
	 * medium, medium to lob) takes about 0.2 s. Approximate: +-0.5 / s.
	 */
	static final float STRENGTH_RATE = 2.5f;
	/**
	 * Letting go of one of two held buttons: CS2 keeps the medium throw this long before it starts moving (so a two
	 * button release a little apart still throws medium). Measured: unchanged up to 150 ms apart, moving by 200 ms.
	 * In client ticks (0.15 s).
	 */
	static final int RELEASE_GRACE_TICKS = 3;
	private static boolean ragLit;
	private static boolean inspectWasDown;
	/** CS2's accumulated firing inaccuracy as the client predicts it (Legacy crosshair), and when it was set. */
	private static float firePenalty;
	private static long firePenaltyAt;
	/** The client's copy of CS2's inaccuracy (same model as the server), its total at the last two ticks (the crosshair
	 * interpolates between them), and the ground / fall state for landing penalties. */
	private static final Inaccuracy accuracy = new Inaccuracy();
	private static float inaccPrev;
	private static float inaccNow;
	private static boolean accWasOnGround = true;
	private static double accFallSpeed;
	/** Grenades: when the throw packet goes out (CS2 throws 0.1 s after the release), its strength, the end of the throw
	 * animation, and whether the next grenade is drawn after it. */
	private static long throwSendAt = -1;
	private static float throwStrength;
	private static double throwEnd;
	private static boolean redraw;
	/** The thrown grenade has left the hand: it's no longer drawn in the viewmodel until the next one comes up. */
	private static boolean released;
	/** The server's sv_infinite_ammo (CvarsPayload). */
	public static int infiniteAmmo;

	private GunClient() {
	}

	/** The gun in use: only from the CS slots in CS mode (one sitting in the Minecraft hotbar is just an item). */
	static GunSpec held(LocalPlayer player) {
		return heldWeapon(player) instanceof GunItem gun ? gun.spec() : null;
	}

	/** The CS weapon in use (gun, knife, grenade, Zeus): only from the CS slots in CS mode, and never while spectating. */
	static CsWeapon heldWeapon(LocalPlayer player) {
		return player != null && !player.isSpectator() && dev.countercraft.team.CsInventory.active(player)
				&& player.getMainHandItem().getItem() instanceof CsWeapon w ? w : null;
	}

	/** Current scope level of the held gun (0 = none). */
	public static int zoomLevel() {
		Minecraft mc = Minecraft.getInstance();
		GunSpec spec = held(mc.player);
		return spec == null || !spec.zoom().scoped() ? 0 : mc.player.getMainHandItem().getOrDefault(ModComponents.ZOOM, 0);
	}

	/** True for guns whose zoom is aiming down the scope (AUG, SG 553): no scope overlay, the viewmodel comes up. */
	static boolean ironsight(GunSpec spec) {
		return spec != null && spec.ironsight() != null;
	}

	/** How far the AUG / SG 553 is brought up to the eye, 0..1. */
	static float adsWeight() {
		return ironsight(held(Minecraft.getInstance().player)) ? adsWeight : 0f;
	}

	private static boolean drawing() {
		return tick + 1e-6 < drawEnd;
	}

	/**
	 * Start of client tick, before vanilla handles key presses. With a gun out: Inspect plays the inspect, Reload asks
	 * the server to reload, and vanilla's swap-hands press is swallowed (it shares F with Inspect by default).
	 */
	public static void startTick(Minecraft mc) {
		if (heldWeapon(mc.player) == null) {
			while (GunKeys.INSPECT.consumeClick() || GunKeys.RELOAD.consumeClick()) {
				// not holding a gun: drop presses so they don't fire later
			}
			return;
		}
		while (mc.options.keySwapOffhand.consumeClick()) {
			// swallowed: guns have no offhand
		}
		// inspect starts on the press itself: a held key's auto-repeat must not restart it over and over
		while (GunKeys.INSPECT.consumeClick()) {
			// drained; the edge below decides
		}
		boolean inspectDown = GunKeys.INSPECT.isDown() && mc.gui.screen() == null;
		if (inspectDown && !inspectWasDown && animator != null && !drawing() && !animator.current().startsWith("reload") && zoomLevel() == 0) {
			animator.play("inspect", 1f);
			dev.countercraft.client.agent.AgentClient.localInspect();
		}
		inspectWasDown = inspectDown;
		boolean reloadPressed = false;
		while (GunKeys.RELOAD.consumeClick()) {
			reloadPressed = true;
		}
		if (reloadPressed) {
			// CS2: R also ends an inspect (it eases back to idle; a reload, if one starts, takes over)
			if (animator != null && animator.current().equals("inspect")) {
				animator.play(animator.idleRole(), 1f);
			}
			if (held(mc.player) != null && !drawing()) {
				ClientPlayNetworking.send(ReloadPayload.INSTANCE);
			}
		}
	}

	public static void endTick(Minecraft mc) {
		tick++;
		LocalPlayer player = mc.player;
		if (player != null && tick % 10 == 0) {
			prefetchInventory(player);
		}
		CsWeapon weapon = heldWeapon(player);
		boolean screen = mc.gui.screen() != null;
		boolean fire = GunKeys.FIRE.isDown() && !screen;
		boolean alt = GunKeys.ALT_FIRE.isDown() && !screen;
		boolean firePressed = fire && !wasFire;
		boolean altPressed = alt && !wasAlt;
		wasFire = fire;
		wasAlt = alt;
		if (throwSendAt >= 0 && tick >= throwSendAt) {
			throwSendAt = -1;
			if (weapon instanceof GrenadeItem) {
				sendThrow(player);
			}
			released = true;
		}
		if (weapon == null) {
			if (animator != null && tick < throwEnd) {
				return; // the last grenade's throw plays out before the hand goes
			}
			heldGun = "";
			animator = null;
			adsAnimator = null;
			primed = false;
			ragLit = false;
			return;
		}
		ItemStack stack = player.getMainHandItem();
		int ammo = stack.getOrDefault(ModComponents.AMMO, 0);

		// switched weapon (or slot): draw, and nothing can be used until the draw is over (CS2 deploy time)
		int slot = player.getInventory().getSelectedSlot();
		if (!weapon.weaponId().equals(heldGun) || slot != heldSlot) {
			heldGun = weapon.weaponId();
			heldSlot = slot;
			recoil = new RecoilPattern.State();
			animator = asset(mc, weapon.weaponId()).map(ViewmodelAnimator::new).orElse(null);
			adsAnimator = animator != null && animator.has("ads_idle") ? new ViewmodelAnimator(animator.asset(), "ads_idle") : null;
			adsWeight = 0f;
			if (animator != null) {
				animator.play("draw", 1f);
			}
			drawEnd = tick + weapon.deploySeconds() * 20.0;
			nextShot = drawEnd;
			lastReloadEnd = stack.get(ModComponents.RELOAD_END);
			predictedAmmo = lastSeenAmmo = ammo;
			burstLeft = 0;
			primed = false;
			ragLit = false;
			prepPulls = 0;
			redraw = false;
			released = false;
			throwEnd = 0;
		}
		if (weapon instanceof KnifeItem) {
			knifeTick(mc, fire, alt);
			return;
		}
		if (weapon instanceof GrenadeItem g) {
			grenadeTick(g, fire, alt);
			return;
		}
		GunSpec spec = held(player);
		updateAccuracy(player, spec, stack);

		// ammo the server confirms; a reload (ammo going up) resets the prediction
		if (ammo > lastSeenAmmo || ammo < predictedAmmo) {
			predictedAmmo = ammo;
		}
		lastSeenAmmo = ammo;

		// reload started on the server (shotguns: once, then a loop of the clip per shell)
		Long reloadEnd = stack.get(ModComponents.RELOAD_END);
		boolean started = spec.singleShellReload() ? reloadEnd != null && lastReloadEnd == null
				: reloadEnd != null && !reloadEnd.equals(lastReloadEnd);
		if (started && !spec.exhaustible()) {
			dev.countercraft.client.agent.AgentClient.local("action_reload", "", spec.reloadTime());
		}
		if (started && animator != null && !spec.exhaustible()) {
			GunSpec.ShellReload sr = spec.shellReload();
			int shells = Math.max(1, Math.min(spec.magSize() - ammo, stack.getOrDefault(ModComponents.RESERVE, 0)));
			if (spec.singleShellReload() && sr != null) {
				animator.playLooped("reload", sr.intro(), sr.intro() + sr.loop(), shells);
				nextShot = Math.max(nextShot, tick + spec.reloadTime() * 20.0); // same disallow window as the server
			} else {
				String role = ammo == 0 && animator.has("reload_empty") ? "reload_empty" : "reload";
				ViewmodelAsset.ClipData clip = animator.asset().clips.get(role);
				int n = spec.singleShellReload() ? shells : 1;
				float rate = clip == null ? 1f : clip.duration() / Math.max(0.1f, spec.reloadTime() * n);
				animator.play(role, rate);
			}
		}
		lastReloadEnd = reloadEnd;
		if (animator != null) {
			animator.setIdle(predictedAmmo <= 0 ? "idle_empty" : "idle");
		}
		if (drawing()) {
			return;
		}

		// alt fire: R8 fans the hammer while held; everything else toggles on press (scope / burst / silencer)
		if (spec.revolver()) {
			if (alt) {
				ClientPlayNetworking.send(new FirePayload(true, altPressed, motion(player)));
				predict(mc, spec, stack, true, !altPressed);
			}
		} else if (altPressed) {
			ClientPlayNetworking.send(AltPayload.INSTANCE);
			if ("detachable".equals(spec.silencer()) && animator != null && reloadEnd == null && tick >= nextShot) {
				boolean off = stack.getOrDefault(ModComponents.SILENCER_OFF, false);
				animator.play(off ? "silencer_on" : "silencer_off", 1f);
				nextShot = tick + 40;
			}
		}

		// burst follow-up rounds (predicted; the server fires them on its own)
		while (burstLeft > 0 && tick + 1e-6 >= burstNext) {
			predictedRound(mc, spec, stack, true, burstNext);
			burstLeft--;
			burstNext += spec.burst().between() * 20.0;
		}

		if (firePressed && predictedAmmo <= 0 && reloadEnd == null && animator != null && animator.has("dryfire")) {
			animator.play("dryfire", 1f);
		}
		boolean send = spec.automatic() || spec.revolver() ? fire : firePressed;
		if (!send) {
			prepPulls = 0;
			return;
		}
		ClientPlayNetworking.send(new FirePayload(false, firePressed, motion(player)));
		if (spec.revolver()) {
			// the server's count (GunItem.trigger): the hammer pull starts once the gun is ready, a fresh press starts over,
			// and the shot goes with the packet after REVOLVER_PREP_TICKS ready ones
			boolean ready = tick + 1e-6 >= nextShot && reloadEnd == null;
			if (firePressed || !ready) {
				prepPulls = 0;
			}
			if (!ready) {
				return;
			}
			if (prepPulls++ == 0 && predictedAmmo > 0 && animator != null) {
				animator.play("prepare", 1f);
			}
			if (prepPulls <= GunItem.REVOLVER_PREP_TICKS) {
				return;
			}
		}
		predict(mc, spec, stack, false, !firePressed);
	}

	/** Knife: hold left to slash, right to stab; the animation picks hit/miss/backstab from what's in reach. */
	private static void knifeTick(Minecraft mc, boolean fire, boolean alt) {
		if (!fire && !alt) {
			return;
		}
		if (tick + 1e-6 < nextShot) {
			return; // between swings: nothing to send (the server only swings on the packet marked as a swing)
		}
		boolean stab = !fire;
		boolean hit = false;
		boolean back = false;
		int backId = -1;
		if (mc.hitResult instanceof EntityHitResult eh && Ballistics.hittable(eh.getEntity(), mc.player)) {
			boolean player = Ballistics.owner(eh.getEntity()) instanceof net.minecraft.world.entity.player.Player;
			// the server's reach: CS2's against players, longer against mobs
			double reach = player ? (stab ? KnifeItem.STAB_RANGE : KnifeItem.SLASH_RANGE) : (stab ? KnifeItem.STAB_RANGE_MOB : KnifeItem.SLASH_RANGE_MOB);
			if (mc.player.getEyePosition().distanceTo(eh.getLocation()) <= reach) {
				hit = true;
				// the same test as the server: the backstab rule for players, the back hitbox zone for mobs
				back = Ballistics.owner(eh.getEntity()) instanceof LivingEntity target
						&& (player ? KnifeItem.backstab(mc.player, target) : KnifeItem.backZone(mc.player, target, eh.getLocation()));
				backId = back ? Ballistics.owner(eh.getEntity()).getId() : -1;
			}
		}
		// the swing: its packet is marked (fresh) and carries what the animation shows (a backstab), so the server swings
		// with it and the damage matches what you saw (the server's own test sees the target a moment later)
		ClientPlayNetworking.send(new FirePayload(stab, true, motion(mc.player), backId));
		boolean anim = animator != null;
		String role;
		if (stab) {
			role = back && anim && animator.has("stab_backstab") ? "stab_backstab" : (hit ? "stab_hit" : "stab");
			nextShot = tick + (hit ? 1.1 : 1.0) * 20.0;
		} else {
			leftNext = !leftNext;
			role = back && anim && animator.has("backstab") ? "backstab" : (hit ? (leftNext ? "slash_hit2" : "slash_hit") : (leftNext ? "slash2" : "slash"));
			nextShot = tick + (hit ? 0.5 : 0.4) * 20.0;
		}
		if (anim) {
			animator.play(animator.has(role) ? role : (stab ? "stab" : "slash"), 1f);
		}
		dev.countercraft.client.agent.AgentClient.local("action_attack", KnifeItem.attackType(stab, hit, back), (float) ((nextShot - tick) / 20.0));
	}

	/**
	 * Grenade: pressing either button pulls the pin; letting go of everything throws. While the pin is pulled the throw
	 * follows the buttons held right now, as often as they change: left only = full throw, right only = underhand lob,
	 * both = medium (CS2's 1.0 / 0.0 / 0.5 strengths), the viewmodel's ready pose with it. The grenade goes with the
	 * strength at the moment everything is let go. Not before the draw is over.
	 */
	private static void grenadeTick(GrenadeItem grenade, boolean fire, boolean alt) {
		if (redraw && tick + 1e-6 >= throwEnd) {
			// CS2: once the throw has played, the next grenade is drawn (sv_infinite_ammo, or a second flashbang)
			redraw = false;
			released = false;
			if (animator != null) {
				animator.play("draw", 1f);
			}
		}
		if (fire || alt) {
			if (!primed) {
				if (drawing()) {
					return;
				}
				primed = true;
				primedStrength = idealStrength(fire, alt);
				primedBoth = fire && alt;
				releaseGrace = 0;
				dev.countercraft.client.agent.AgentClient.local("action_attack", "attack_grenade_charge", 60f);
				if (animator != null) {
					animator.play("pullpin", 1f);
				}
			}
			boolean both = fire && alt;
			if (primedBoth && !both) {
				releaseGrace = RELEASE_GRACE_TICKS; // one of the two let go: a moment's grace before the throw changes
			} else if (both) {
				releaseGrace = 0;
			}
			primedBoth = both;
			boolean grace = releaseGrace > 0;
			if (grace) {
				releaseGrace--;
			}
			float ideal = grace ? 0.5f : idealStrength(fire, alt);
			float step = STRENGTH_RATE * 0.05f; // per client tick
			primedStrength += Math.max(-step, Math.min(step, ideal - primedStrength));
			// CS2 holds the grenade ready for the throw the buttons ask for: back and high (left), low for a lob
			// (right), between (both); it eases from one to the other as buttons change
			if (animator != null) {
				animator.setIdle(grace || both ? "charge_mid" : fire ? "charge_high" : "charge_low");
			}
			return;
		}
		if (!primed) {
			return;
		}
		primed = false;
		float strength = primedStrength;
		throwStrength = strength;
		throwSendAt = tick + 2; // CS2's grenade leaves the hand 0.1 s after the release
		dev.countercraft.client.agent.AgentClient.local("action_attack", "attack_grenade_throw", grenade.def().throwSeconds(strength));
		if (animator != null) {
			animator.play(strength >= 1f ? "throw" : (animator.has("throw_under") ? "throw_under" : "throw"), 1f);
			animator.setIdle("idle");
		}
		// the throw plays out, then the next one is drawn: nothing can be primed until both are done (as the server)
		float anim = grenade.def().throwSeconds(strength);
		throwEnd = tick + anim * 20.0;
		drawEnd = throwEnd + grenade.def().deploy() * 20.0;
		redraw = true;
	}

	/** The pin is pulled: the strength the throw would have now (1 left, 0 right, 0.5 both), else -1. */
	public static float grenadeStrength() {
		return primed ? primedStrength : -1f;
	}

	/** CS2's strength for the buttons held: both 0.5, left 1, right 0. */
	private static float idealStrength(boolean fire, boolean alt) {
		return fire && alt ? 0.5f : fire ? 1f : 0f;
	}

	/** The player's own velocity as a throw carries it (units/s). */
	public static Vec3 throwVelocity(LocalPlayer player) {
		return CsPlayerMovement.active() ? CsPlayerMovement.velocity() : player.getDeltaMovement().scale(20 * 39.37);
	}

	/** Throw now, with the view and the player's own velocity (units/s) at this moment. */
	private static void sendThrow(LocalPlayer player) {
		Vec3 v = throwVelocity(player);
		ClientPlayNetworking.send(new ThrowPayload(throwStrength, player.getYRot(), player.getXRot(), (float) v.x, (float) v.y, (float) v.z));
	}

	/** Predict one trigger pull locally; the server makes the same decision and does the damage. */
	private static boolean predict(Minecraft mc, GunSpec spec, ItemStack stack, boolean altFire, boolean held) {
		Long reloadEnd = stack.get(ModComponents.RELOAD_END);
		boolean reloading = reloadEnd != null && !(spec.singleShellReload() && predictedAmmo > 0);
		if (reloading || tick + 1e-6 < nextShot || burstLeft > 0 || predictedAmmo <= 0) {
			return false;
		}
		double shotAt = GunItem.shotTime(nextShot, tick, held);
		boolean burst = spec.burst().enabled() && stack.getOrDefault(ModComponents.BURST, false);
		predictedRound(mc, spec, stack, altFire, shotAt);
		if (burst) {
			burstLeft = Math.min(2, predictedAmmo);
			burstNext = shotAt + spec.burst().between() * 20.0;
			nextShot = shotAt + spec.burst().cycle() * 20.0;
		} else {
			nextShot = shotAt + spec.cycleTicks(altFire);
		}
		return true;
	}

	private static void predictedRound(Minecraft mc, GunSpec spec, ItemStack stack, boolean altFire, double shotAt) {
		GunItem gun = (GunItem) stack.getItem();
		boolean alt = altFire || gun.altMode(stack);
		recoil.shoot(spec.recoil(), shotAt, alt);
		float fireAdd = dev.countercraft.gun.AccuracySpec.pick(spec.accuracy().firing(), alt);
		firePenalty = firePenalty() + fireAdd;
		firePenaltyAt = System.nanoTime();
		accuracy.fire(spec.accuracy(), alt);
		inaccPrev += fireAdd; // the crosshair blooms on the shot, not a tick later
		inaccNow += fireAdd;
		dev.countercraft.client.agent.AgentClient.local("action_attack", altFire && spec.revolver() ? "attack_gun_secondaryfire" : "attack_gun_primaryfire",
				Math.max(0.5f, (float) spec.cycleTicks(altFire) / 20f));
		if (infiniteAmmo != 1) {
			predictedAmmo--;
		}
		boolean left = "elite".equals(spec.id()) && leftNext;
		if (animator != null) {
			String role;
			if (predictedAmmo <= 0 && animator.has("shoot_empty")) {
				role = "shoot_empty";
			} else if (spec.revolver()) {
				role = altFire && animator.has("shoot_alt") ? "shoot_alt" : "shoot"; // R8: fan the hammer
			} else if (animator.has("shoot_alt")) {
				role = leftNext ? "shoot_alt" : "shoot"; // dual Berettas: left, right
				leftNext = !leftNext;
			} else {
				role = "shoot";
			}
			animator.play(role, 1f);
			if (adsAnimator != null && zoomLevel() > 0) {
				adsAnimator.play("ads_shoot", 1f);
			}
		}
		boolean unsilenced = "detachable".equals(spec.silencer()) && stack.getOrDefault(ModComponents.SILENCER_OFF, false);
		String snd = unsilenced && spec.sounds().fireUnsilenced() != null ? spec.sounds().fireUnsilenced() : spec.fireSound();
		playLocal(mc, CounterCraft.id(snd), 1f);
		ShotEffects.localShot(spec, gun.silenced(stack) || "integrated".equals(spec.silencer()), left || gun.silenced(stack));
	}

	/** Camera punch {pitchUp, yawRight} in degrees for this frame. */
	public static float[] viewPunch(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		GunSpec spec = held(mc.player);
		if (spec == null) {
			return null;
		}
		return recoil.camera(spec.recoil(), tick + partialTick);
	}

	/** The firing part of the inaccuracy now (recovers to 10% over the recovery time, as on the server). */
	public static float firePenalty() {
		Minecraft mc = Minecraft.getInstance();
		GunSpec spec = held(mc.player);
		if (spec == null || firePenalty <= 0) {
			return 0f;
		}
		boolean crouch = mc.player.isCrouching() || (CsPlayerMovement.active() && CsPlayerMovement.duck(1f) > 0.5f);
		float recovery = crouch ? spec.accuracy().recoveryCrouch() : spec.accuracy().recoveryStand();
		double dt = (System.nanoTime() - firePenaltyAt) / 1e9;
		return recovery <= 0 ? 0f : (float) (firePenalty * Math.pow(0.1, dt / recovery));
	}

	/** This tick's movement for the server's inaccuracy (CS units/s; vanilla movement maps 4.317 b/s to 250 u/s). */
	private static FirePayload.Motion motion(LocalPlayer p) {
		Vec3 v;
		boolean ground;
		float duck;
		if (CsPlayerMovement.active()) {
			v = CsPlayerMovement.velocity();
			ground = CsPlayerMovement.onGround();
			duck = CsPlayerMovement.duck(1f);
		} else {
			Vec3 d = p.getDeltaMovement();
			double h = 20 * 250 / 4.317;
			v = new Vec3(d.x * h, d.y * 20 * 39.37, d.z * h);
			ground = p.onGround();
			duck = p.isCrouching() ? 1f : 0f;
		}
		return new FirePayload.Motion((float) v.x, (float) v.y, (float) v.z, ground, MovementKeys.WALK.isDown(), duck);
	}

	/** Advance the client's inaccuracy one tick (as the server does) and take this tick's total for the crosshair. */
	private static void updateAccuracy(LocalPlayer player, GunSpec spec, ItemStack stack) {
		dev.countercraft.gun.AccuracySpec a = spec.accuracy();
		boolean alt = ((GunItem) stack.getItem()).altMode(stack);
		FirePayload.Motion m = motion(player);
		boolean ladder = player.onClimbable() && !m.onGround();
		if (!m.onGround()) {
			accFallSpeed = Math.max(0, -m.vy());
		} else if (!accWasOnGround) {
			accuracy.land(a, alt, accFallSpeed);
		}
		accWasOnGround = m.onGround();
		accuracy.update(a, alt, m.onGround(), m.duck(), ladder, recoil.shotIndex, 0.05);
		inaccPrev = inaccNow;
		inaccNow = accuracy.total(a, alt, Math.hypot(m.vx(), m.vz()), m.vy(), m.onGround(), m.walking());
	}

	/** The client's inaccuracy (radians, without spread) interpolated within the tick: smooth at any frame rate. */
	private static float inaccuracy(float partialTick) {
		return inaccPrev + (inaccNow - inaccPrev) * Math.max(0f, Math.min(1f, partialTick));
	}

	/** Weapon inaccuracy + spread right now (radians, CS2 terms), for the dynamic crosshair; 0 without a gun. */
	public static double crosshairSpread(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		GunSpec spec = held(mc.player);
		if (spec == null) {
			return 0;
		}
		boolean alt = ((GunItem) mc.player.getMainHandItem().getItem()).altMode(mc.player.getMainHandItem());
		return inaccuracy(partialTick) + dev.countercraft.gun.AccuracySpec.pick(spec.accuracy().spread(), alt);
	}

	/**
	 * Where the next bullet would go relative to the camera {up, right} in degrees (aim punch minus the camera's share
	 * of it): the recoil-following crosshair's offset.
	 */
	public static float[] crosshairRecoil(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		GunSpec spec = held(mc.player);
		if (spec == null) {
			return null;
		}
		double now = tick + partialTick;
		float[] cam = recoil.camera(spec.recoil(), now);
		float[] aim = recoil.aim(spec.recoil(), now);
		return new float[] {aim[0] - cam[0], aim[1] - cam[1]};
	}

	/** The part of {@link #scopeInaccuracy} that comes from moving / jumping (CS2's scope blur follows it). */
	static double scopeBlurInaccuracy() {
		Minecraft mc = Minecraft.getInstance();
		GunSpec spec = held(mc.player);
		if (spec == null) {
			return 0;
		}
		boolean alt = ((GunItem) mc.player.getMainHandItem().getItem()).altMode(mc.player.getMainHandItem());
		boolean crouch = mc.player.isCrouching() || (CsPlayerMovement.active() && CsPlayerMovement.duck(1f) > 0.5f);
		return Math.max(0, scopeInaccuracy() - dev.countercraft.gun.AccuracySpec.pick(crouch ? spec.accuracy().crouching() : spec.accuracy().standing(), alt));
	}

	/** The held gun's inaccuracy right now without the firing part (radians): drives the scope blur. */
	static double scopeInaccuracy() {
		Minecraft mc = Minecraft.getInstance();
		if (held(mc.player) == null) {
			return 0;
		}
		return Math.max(0, inaccuracy(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) - firePenalty());
	}

	/** Zoomed FOV (CS2 degrees, horizontal at 4:3) of the held gun right now, or 0 when not zoomed. */
	private static double csZoomFov(GunSpec spec) {
		if (ironsight(spec)) {
			return adsWeight > 0 ? spec.ironsight().fov() : 0;
		}
		int z = zoomLevel();
		return z <= 0 ? 0 : spec.zoom().fov()[Math.min(z, 2) - 1];
	}

	/** Zoom factor in effect (1 = none): the AUG / SG 553 ease in with the ADS blend. */
	public static double magnification() {
		GunSpec spec = held(Minecraft.getInstance().player);
		if (spec == null) {
			return 1.0;
		}
		double csFov = csZoomFov(spec);
		if (csFov <= 0) {
			return 1.0;
		}
		double mag = Math.tan(Math.toRadians(45)) / Math.tan(Math.toRadians(csFov / 2));
		return ironsight(spec) ? 1.0 + (mag - 1.0) * adsWeight : mag;
	}

	/**
	 * CS2 zoom as a magnification of the player's own FOV: CS2's scope FOVs (40/10, 40/15, 45) are horizontal at 4:3
	 * against a 90 degree default, so magnification = tan(45) / tan(fov / 2).
	 */
	public static float zoomFov(float fov) {
		double mag = magnification();
		if (mag == 1.0) {
			return fov;
		}
		return (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fov / 2)) / mag));
	}

	/** Mouse look scale while zoomed (CS2 zoom_sensitivity_ratio 1: the same aim speed on screen). */
	public static double zoomSensitivity() {
		return 1.0 / magnification();
	}

	/** Render the CS2 viewmodel instead of the vanilla hand. Returns false to let vanilla render. */
	public static boolean renderViewmodel(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		if (animator == null) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		animator.update(sound -> playLocal(mc, sound, 1f));
		GunSpec spec = held(mc.player);
		if (heldWeapon(mc.player) == null && !(tick < throwEnd)) {
			return false;
		}
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;
		if (spec != null && spec.zoom().hideViewmodel() && zoomLevel() > 0) {
			return true; // scoped snipers hide the gun, like CS2
		}
		float[] pose = animator.pose();
		if (adsAnimator != null && ironsight(spec)) {
			// CS2 brings the AUG / SG 553 up at m_flIronSightPullUpSpeed and lowers it at m_flIronSightPutDownSpeed
			boolean up = zoomLevel() > 0;
			adsWeight = up ? Math.min(1f, adsWeight + dt * spec.ironsight().pullUp()) : Math.max(0f, adsWeight - dt * spec.ironsight().putDown());
			if (adsWeight > 0f) {
				adsAnimator.update(sound -> { });
				float[] ads = adsAnimator.pose();
				float[] blended = new float[pose.length];
				for (int i = 0; i < pose.length; i++) {
					blended[i] = pose[i] + (ads[i] - pose[i]) * adsWeight;
				}
				pose = blended;
			}
		}
		// a removed silencer stays off the gun once the detach animation has put it away
		if (spec != null && "detachable".equals(spec.silencer()) && mc.player.getMainHandItem().getOrDefault(ModComponents.SILENCER_OFF, false)
				&& !animator.current().startsWith("silencer_")) {
			animator.hideBone(pose, "silencer");
		}
		float[] bob = ViewmodelBob.offset(mc.player);
		if (ironsight(spec) && adsWeight > 0f) {
			for (int i = 0; i < 3; i++) {
				bob[i] *= 1f - adsWeight; // aiming down sights holds the scope on the eye
			}
		}
		if (released) {
			for (String bone : THROWN_BONES) {
				animator.hideBone(pose, bone); // gone with the throw (the pin and ring stay on the other hand)
			}
		}
		float[] shown = pose;
		RENDERER.skin(heldGun, heldWeapon(mc.player) == null ? null : dev.countercraft.client.skins.Skins.of(mc.player.getMainHandItem()));
		if (ironsight(spec)) {
			RENDERER.ironsightFov(spec.ironsight().fov());
		}
		RENDERER.submit(animator.asset(), pose, poseStack, collector, light, flames(pose), ironsight(spec) ? 1f - adsWeight : 1f, bob,
				(ps, c) -> ShotEffects.submitViewmodel(ps, c, animator.asset(), shown));
		return true;
	}

	/** Grenade viewmodel bones that leave with the throw: the body, its spoon, the molotov bottle, rag and liquid. */
	private static final String[] THROWN_BONES = {"weapon", "weapon_offset", "weapon_hand_r", "handle", "molotov", "molotov_rag_jiggle",
			"liquid_surface"};

	/** The molotov's burning rag (lit by the lighter during the pin pull, until the throw) and the lighter's flame. */
	private static List<ViewmodelRenderer.Flame> flames(float[] pose) {
		if (!"molotov".equals(heldGun) || animator == null) {
			return List.of();
		}
		String clip = animator.current();
		float t = animator.clipTime();
		if (clip.equals("pullpin") && t >= RAG_IGNITES) {
			ragLit = true;
		} else if (!primed && !clip.startsWith("throw")) {
			ragLit = false;
		}
		List<ViewmodelRenderer.Flame> out = new ArrayList<>(2);
		ViewmodelAsset a = animator.asset();
		if (ragLit && !released) {
			float[] p = a.posed(pose, "molotov_rag_jiggle", a.boneCentroid("molotov_rag_jiggle"));
			if (p != null) {
				out.add(new ViewmodelRenderer.Flame(p[0], p[1], p[2], 0.09f));
			}
		}
		if (clip.equals("pullpin") && t >= LIGHTER_OPEN && t <= LIGHTER_SHUT) {
			float[] p = a.posed(pose, "lighter_hinge", a.boneCentroid("lighter_hinge"));
			if (p != null) {
				out.add(new ViewmodelRenderer.Flame(p[0], p[1] + 0.012f, p[2], 0.03f));
			}
		}
		return out;
	}

	/**
	 * Your own weapon's sounds (gunfire, and the viewmodel's reload / draw sounds): played at the listener itself, not at a
	 * point in the world, so they stay centred between both ears however you move, turn or fly. Everyone else hears your
	 * shots from where you are (the server's sound, positional).
	 */
	private static void playLocal(Minecraft mc, Identifier sound, float volume) {
		if (mc.level != null && mc.player != null) {
			mc.getSoundManager().play(new net.minecraft.client.resources.sounds.SimpleSoundInstance(sound, SoundSource.PLAYERS, volume, 1f,
					net.minecraft.util.RandomSource.create(), false, 0, net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0, 0, 0,
					true));
		}
	}

	/** A thrown grenade's world model (converted as viewmodels/&lt;id&gt;_thrown.cvm), if the CS2 pack has it. */
	static Optional<ViewmodelAsset> thrownAsset(String grenade) {
		return asset(Minecraft.getInstance(), grenade + "_thrown");
	}

	/** As {@link #thrownAsset}, without waiting: null while it loads in the background. */
	static Optional<ViewmodelAsset> thrownAssetIfReady(String grenade) {
		return viewmodelIfReady(grenade + "_thrown");
	}

	private static Pending pending(Minecraft mc, String gun) {
		return ASSETS.computeIfAbsent(gun, g -> {
			Pending p = new Pending();
			LOADER.execute(() -> load(mc, g, p));
			return p;
		});
	}

	private static void load(Minecraft mc, String gun, Pending p) {
		if (!p.claimed.compareAndSet(false, true)) {
			return;
		}
		Optional<ViewmodelAsset> a;
		try {
			a = read(mc, gun);
		} catch (Throwable t) {
			a = Optional.empty();
		}
		p.done.complete(a);
		a.ifPresent(GunClient::prefetchTextures);
	}

	/** A viewmodel now: loaded here if the loader hasn't started it (rather than waiting behind its queue). */
	private static Optional<ViewmodelAsset> asset(Minecraft mc, String gun) {
		Pending p = pending(mc, gun);
		load(mc, gun, p);
		return p.done.join();
	}

	/** Load a gun's (or "&lt;grenade&gt;_thrown") viewmodel and its textures in the background, ahead of its first draw. */
	public static void prefetch(String gun) {
		pending(Minecraft.getInstance(), gun);
	}

	/** Its textures (and roughness / normal maps), streamed ahead of the first draw. Loader thread. */
	private static void prefetchTextures(ViewmodelAsset a) {
		var rm = Minecraft.getInstance().getResourceManager();
		for (ViewmodelAsset.Part part : a.parts) {
			Identifier t = part.texture();
			dev.countercraft.client.gpu.TextureStream.request(t);
			for (String suffix : new String[] {"_s.png", "_n.png"}) {
				Identifier m = t.withPath(t.getPath().replace(".png", suffix));
				if (rm.getResource(m).isPresent()) {
					dev.countercraft.client.gpu.TextureStream.request(m);
				}
			}
		}
	}

	private static final java.util.Set<dev.countercraft.client.skins.Skins.Skin> PREFETCHED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Everything you carry (and your agent's arms): loaded in the background before you switch to it, so a first equip
	 * (or a fresh buy) doesn't stall a frame on a 3-4 MB model and its 2048 px textures.
	 */
	private static void prefetchInventory(LocalPlayer p) {
		List<ItemStack> stacks = new ArrayList<>(dev.countercraft.team.CsInventory.of(p).items);
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			stacks.add(p.getInventory().getItem(i));
		}
		for (ItemStack st : stacks) {
			if (st.getItem() instanceof CsWeapon w) {
				prefetch(w.weaponId());
				if (st.getItem() instanceof GrenadeItem) {
					prefetch(w.weaponId() + "_thrown");
				}
				prefetch(dev.countercraft.client.skins.Skins.of(st));
			}
		}
		ViewmodelAsset.Part[] arms = agentArms();
		if (arms != null) {
			for (ViewmodelAsset.Part part : arms) {
				dev.countercraft.client.gpu.TextureStream.request(part.texture());
			}
		}
	}

	/** A finish's texture and its roughness / metal map, streamed ahead of the first draw. */
	public static void prefetch(dev.countercraft.client.skins.Skins.Skin skin) {
		if (skin != null && PREFETCHED.add(skin)) {
			Identifier t = skin.texture();
			dev.countercraft.client.gpu.TextureStream.request(t);
			Identifier m = t.withPath(t.getPath().replace(".png", "_s.png"));
			LOADER.execute(() -> {
				if (Minecraft.getInstance().getResourceManager().getResource(m).isPresent()) {
					dev.countercraft.client.gpu.TextureStream.request(m);
				}
			});
		}
	}

	/** A viewmodel if it's loaded; null while it loads in the background (started here); empty if the pack lacks it. */
	public static Optional<ViewmodelAsset> viewmodelIfReady(String gun) {
		Pending p = pending(Minecraft.getInstance(), gun);
		return p.done.isDone() ? p.done.join() : null;
	}

	private static Optional<ViewmodelAsset> read(Minecraft mc, String g) {
		{
			Identifier id = CounterCraft.id("viewmodels/" + g + ".cvm");
			try (InputStream in = mc.getResourceManager().open(id)) {
				ViewmodelAsset a = ViewmodelAsset.read(in);
				mc.getResourceManager().getResource(CounterCraft.id("viewmodels/" + g + "_lens.json")).ifPresent(r -> {
					try (var reader = r.openAsReader()) {
						com.google.gson.JsonObject j = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
						var c = j.getAsJsonArray("center");
						var ax = j.getAsJsonArray("axis");
						a.lens = new float[] {j.get("bone").getAsInt(), c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat(),
								ax.get(0).getAsFloat(), ax.get(1).getAsFloat(), ax.get(2).getAsFloat(), j.get("radius").getAsFloat()};
					} catch (Exception e) {
						CounterCraft.LOG.warn("[countercraft] bad lens file for {}: {}", g, e.toString());
					}
				});
				CounterCraft.LOG.info("[countercraft] viewmodel {}: {} parts, {} bones, clips {}", g, a.parts.length, a.boneCount, a.clips.keySet());
				return Optional.of(a);
			} catch (Exception e) {
				CounterCraft.LOG.warn("[countercraft] no viewmodel for {} ({}): run converter/convert.py and enable the CS2 asset pack", g, e.toString());
				return Optional.empty();
			}
		}
	}

	public static String lensDebug() {
		return RENDERER.lensDebug();
	}

	/** A gun's converted viewmodel (also the source of its third-person mesh), if the CS2 pack has it. */
	public static Optional<ViewmodelAsset> viewmodel(String gun) {
		return asset(Minecraft.getInstance(), gun);
	}

	private static final Map<String, Optional<ViewmodelAsset.Part[]>> ARMS = new HashMap<>();
	/** Whose arms the viewmodel wears while {@link #renderFor} draws someone else's (null: yours). */
	private static net.minecraft.world.entity.player.Player armsOf;

	/**
	 * Another Chance's first-person spectating: {@code p}'s viewmodel of {@code gun}, posed by its own animator, in their
	 * agent's arms and gloves with their finish (no bob, flames or ADS: what's not sent for other players).
	 */
	public static void renderFor(net.minecraft.world.entity.player.Player p, String gun, ViewmodelAnimator anim, PoseStack poseStack,
			SubmitNodeCollector collector, int light) {
		anim.update(sound -> {
			// their own sounds come from them in the world
		});
		armsOf = p;
		try {
			RENDERER.skin(gun, dev.countercraft.client.skins.Skins.of(p.getMainHandItem()));
			RENDERER.submit(anim.asset(), anim.pose(), poseStack, collector, light);
		} finally {
			armsOf = null;
		}
	}

	/**
	 * The viewmodel's arms: the agent's first-person arms (viewmodels/arms_&lt;agent&gt;.cva) with the equipped gloves, of
	 * the local player (or the one being spectated, {@link #renderFor}); null: the .cvm's own.
	 */
	public static ViewmodelAsset.Part[] agentArms() {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.world.entity.player.Player owner = armsOf != null ? armsOf : mc.player;
		if (owner == null) {
			return null;
		}
		String agent = dev.countercraft.client.agent.AgentClient.agentFor(owner);
		ViewmodelAsset.Part[] arms = agentArmsOf(mc, agent);
		dev.countercraft.client.skins.Skins.Glove glove = dev.countercraft.client.agent.AgentClient.gloveFor(owner);
		if (glove != null) {
			String key = agent + "|" + glove.type() + "|" + glove.id();
			ViewmodelAsset.Part[] base = arms;
			ViewmodelAsset.Part[] gl = GLOVED.computeIfAbsent(key, k -> Optional.ofNullable(dev.countercraft.client.skins.Skins.armsWithGloves(base, glove)))
					.orElse(null);
			if (gl != null) {
				return gl;
			}
		}
		return arms;
	}

	private static final Map<String, Optional<ViewmodelAsset.Part[]>> GLOVED = new HashMap<>();

	private static ViewmodelAsset.Part[] agentArmsOf(Minecraft mc, String agent) {
		return ARMS.computeIfAbsent(agent, a -> {
			try (InputStream in = mc.getResourceManager().open(CounterCraft.id("viewmodels/arms_" + a + ".cva"))) {
				return Optional.of(ViewmodelAsset.readArms(in));
			} catch (Exception e) {
				CounterCraft.LOG.warn("[countercraft] no first-person arms for {} ({})", a, e.toString());
				return Optional.empty();
			}
		}).orElse(null);
	}

	/** Forget loaded viewmodels (after a resource reload); the next tick re-loads and re-draws the held gun. */
	public static void clearAssets() {
		ASSETS.clear();
		PREFETCHED.clear();
		dev.countercraft.client.viewmodel.ViewmodelRenderer.clearMaps();
		ARMS.clear();
		GLOVED.clear();
		animator = null;
		adsAnimator = null;
		heldGun = "";
	}
}
