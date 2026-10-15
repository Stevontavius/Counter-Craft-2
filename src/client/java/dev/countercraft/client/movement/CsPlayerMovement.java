package dev.countercraft.client.movement;

import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import dev.countercraft.movement.CsMove;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * CS2 movement for the local player, replacing {@code LivingEntity.travel} on land and on ladders (water, flying, elytra
 * and riding stay vanilla). Source's player move at CS2's 64 ticks a second: each Minecraft tick runs 3-4 sub-steps of
 * 1/64 s, each doing duck, jump, friction, acceleration (ground or air), gravity and a collision move through
 * Minecraft's own {@code Entity.move} (so step-up, collisions and the server's checks all still apply).
 *
 * <p>Velocity is kept in CS units/s here (1 block = 39.37 units) and handed back to the entity as this tick's
 * displacement. Anything else that changes the entity's motion (knockback, explosions, vanilla movement) is picked
 * up as an external impulse.</p>
 */
public final class CsPlayerMovement {
	/** Minecraft's step height stays: CS2's 18-unit steps (0.46 block) can't climb a slab or a stair. */
	private static final double SNAP_DOWN = 0.6;
	/** Source's MAX_CLIMB_SPEED (200 u/s) x CS's sv_ladder_scale_speed (0.78). */
	private static final double LADDER_SPEED = 200 * 0.78;
	/** Jumping off a ladder: pushed away from it at 270 u/s (Source's LadderMove). */
	private static final double LADDER_JUMP = 270;

	private static Vec3 vel = Vec3.ZERO;          // units/s, Minecraft axes (y up)
	private static Vec3 lastSetDelta;
	private static double carry;                  // unspent sub-step time
	private static double clock;                  // seconds of CS movement
	private static double duck;                   // m_flDuckAmount 0..1
	private static double duckOld;
	private static boolean wasOnGround = true;
	private static boolean jumpHeld;
	/** CS2's stamina as of the last jump or landing, and when that was (CsMove.stamina). */
	private static double stamina;
	private static double staminaAt = Double.NEGATIVE_INFINITY;
	private static double landSpeed;
	private static boolean landedThisTick;
	/**
	 * The last landing: when it shows on screen (System.nanoTime; the camera is drawn a tick behind the movement), the speed
	 * it landed with (CS2's stashed speed) and whether no ground step has slowed it yet.
	 */
	private static long landNanos = Long.MIN_VALUE / 2;
	private static double stash;
	private static boolean fresh;
	/** System.nanoTime of the last jump key press (JumpPressMixin) and of this movement tick. */
	private static long jumpKeyNanos;
	private static long tickNanos;
	private static double landPunch;              // degrees of roll at landPunchAt
	private static double landPunchAt = Double.NEGATIVE_INFINITY;
	private static boolean active;
	/** /cs_movement_debug: log every sub-step (for comparing against CS2's measured curves). */
	public static boolean debug;
	private static double lastJumpMul = 1;
	/** Seconds left after jumping off a ladder during which the ladder can't catch you again (you fly off it). */
	private static double ladderRelease;
	/** Long enough at 270 u/s to clear the ladder's block. */
	private static final double LADDER_RELEASE_SECONDS = 0.25;
	/** This tick's slow-down from a cobweb, berry bush or powder snow (x, y, z factors), or null. */
	private static Vec3 stuck;

	private CsPlayerMovement() {
	}

	/** CS movement handles this player now (land, alive, not flying / swimming / climbing / riding). */
	public static boolean handles(LocalPlayer p) {
		return MovementSettings.enabled && p.isAlive() && !p.isSpectator() && !p.getAbilities().flying && !p.isFallFlying()
				&& !p.isPassenger() && !p.isInWater() && !p.isInLava() && !p.isSwimming();
	}

	/** Velocity in CS units/s (Minecraft axes), for the viewmodel bob. */
	public static Vec3 velocity() {
		return vel;
	}

	public static boolean active() {
		return active;
	}

	public static boolean onGround() {
		return wasOnGround;
	}

	/** Duck amount for the camera's eye height, interpolated within the tick. */
	public static float duck(float partialTick) {
		return (float) (duckOld + (duck - duckOld) * partialTick);
	}

	/** CS2's eye height for the camera: 64 units standing, 46 crouched, following the duck amount. */
	public static float eyeHeight() {
		return (float) (CsMove.STAND_EYE + (CsMove.CROUCH_EYE - CsMove.STAND_EYE) * duck);
	}

	/** Landing camera roll (degrees) now: fast landings jolt the view, decaying at view_punch_decay. */
	public static float landPunch(double nowSeconds) {
		double age = nowSeconds - landPunchAt;
		return age < 0 || age > 1 ? 0f : (float) (landPunch * Math.exp(-CsMove.VIEW_PUNCH_DECAY * age));
	}

	/** True once, on the tick the player touched down (the viewmodel uses it). */
	public static boolean consumeLanding() {
		boolean l = landedThisTick;
		landedThisTick = false;
		return l;
	}

	/** The held weapon's CS max speed (knife / empty hands 250; scoped speed while zoomed). */
	static double maxSpeed(LocalPlayer p) {
		ItemStack s = p.getMainHandItem();
		if (s.getItem() instanceof GunItem gun) {
			float[] m = gun.spec().maxSpeed();
			boolean scoped = gun.spec().zoom().scoped() && s.getOrDefault(ModComponents.ZOOM, 0) > 0;
			return scoped ? m[1] : m[0];
		}
		if (s.getItem() instanceof GrenadeItem g) {
			return g.def().maxSpeed();
		}
		return CsMove.RUN_SPEED;
	}

	/** The jump key went down (JumpPressMixin). */
	public static void jumpKeyDown(long nanos) {
		jumpKeyNanos = nanos;
	}

	/** Called instead of vanilla travel while {@link #handles} is true. */
	public static void travel(LocalPlayer p) {
		if (!active) {
			active = true;
			wasOnGround = p.onGround();
			lastSetDelta = null;
		}
		Vec3 d = p.getDeltaMovement();
		if (lastSetDelta == null || d.distanceToSqr(lastSetDelta) > 1e-4) {
			vel = d.scale(20 * CsMove.UNITS_PER_BLOCK); // someone else moved us (knockback, vanilla movement)
		}
		// a cobweb (berry bush, powder snow) caught us last tick: Minecraft scales the next move by its factors and stops the
		// player; CS movement moves several times a tick, so it takes the slow-down for the whole tick and starts from rest
		var stuckIn = (dev.countercraft.client.mixin.EntityStuckAccessor) p;
		Vec3 s = stuckIn.countercraft$stuck();
		stuck = s.lengthSqr() > 1e-7 ? s : null;
		if (stuck != null) {
			stuckIn.countercraft$setStuck(Vec3.ZERO);
			vel = Vec3.ZERO;
		}
		Input in = p.input.keyPresses;
		boolean jumpDown = in.jump();
		boolean jumpPressed = jumpDown && !jumpHeld;
		jumpHeld = jumpDown;
		boolean wantJump = jumpPressed || (jumpDown && CsMove.autoBhop);
		tickNanos = System.nanoTime();
		double fwd = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
		double side = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
		boolean walking = MovementKeys.WALK.isDown();
		boolean crouching = in.shift() || p.isCrouching(); // stays down under a low ceiling, like Minecraft's pose
		if (dev.countercraft.client.GunClient.plantProgress() >= 0f) {
			// planting the C4: held still and crouched (the camera comes down with it). The server's speed modifier
			// can't do it here: CS movement leaves Counter-Craft's own modifiers out (its speeds are the weapons')
			fwd = 0;
			side = 0;
			jumpPressed = false;
			wantJump = false;
			crouching = true;
		}
		double maxSpeed = maxSpeed(p);
		float yaw = (float) Math.toRadians(p.getYRot());
		effects(p);

		Vec3 start = p.position();
		duckOld = duck;
		carry += 0.05;
		while (carry >= CsMove.TICK - 1e-9) {
			carry -= CsMove.TICK;
			ladderRelease = Math.max(0, ladderRelease - CsMove.TICK);
			boolean jumped = p.onClimbable() && ladderRelease <= 0 ? ladder(p, CsMove.TICK, jumpPressed, fwd, side)
					: step(p, CsMove.TICK, wantJump, jumpPressed, fwd, side, walking, crouching, maxSpeed, yaw);
			if (jumped) {
				jumpPressed = false;
				wantJump = jumpDown && CsMove.autoBhop; // one press, one jump
			}
		}
		Vec3 moved = p.position().subtract(start);
		lastSetDelta = moved;
		p.setDeltaMovement(moved);
		p.setSprinting(false);
		p.calculateEntityAnimation(false);
	}

	/** Minecraft's player defaults the effects are measured against: jump strength, gravity, sneaking speed. */
	private static final double VANILLA_JUMP = 0.42;
	private static final double VANILLA_GRAVITY = 0.08;
	private static final double VANILLA_SNEAK = 0.3;
	/**
	 * This tick's Minecraft effects on the CS movement, each against Minecraft's normal (1 = no effect): speed (Speed,
	 * Slowness and any other speed modifier, the block underfoot: soul sand, honey; Soul Speed), jump (Jump Boost, a honey
	 * block, the jump strength attribute), gravity (the gravity attribute; Slow Falling while falling), crouched speed
	 * (Swift Sneak); and Levitation's rise in units/s (negative: none).
	 */
	private static double speedScale = 1;
	private static double jumpScale = 1;
	private static double gravityScale = 1;
	private static double sneakScale = 1;
	private static double levitation = -1;

	private static void effects(LocalPlayer p) {
		var inv = (dev.countercraft.client.mixin.LivingEntityMovementInvoker) p;
		speedScale = speedAttributeScale(p) * inv.countercraft$blockSpeedFactor();
		jumpScale = Math.max(0, inv.countercraft$jumpPower() / VANILLA_JUMP);
		sneakScale = p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SNEAKING_SPEED) / VANILLA_SNEAK;
		var lev = p.getEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
		levitation = lev == null ? -1 : (lev.getAmplifier() + 1) * 0.05 * 20 * CsMove.UNITS_PER_BLOCK;
		double g = p.getGravity();
		if (vel.y <= 0 && p.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) {
			g = Math.min(g, 0.01); // Minecraft's slow fall, and no fall damage
			p.resetFallDistance();
		}
		gravityScale = g / VANILLA_GRAVITY;
	}

	/**
	 * Movement speed against the player's base (Speed / Slowness, Soul Speed, other mods' modifiers), worked out as
	 * Minecraft does but without Counter-Craft's own weapon and scope modifiers: CS movement's max speed already is the
	 * weapon's.
	 */
	static double speedAttributeScale(LocalPlayer p) {
		var a = p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
		if (a == null || a.getBaseValue() <= 0) {
			return 1;
		}
		double base = a.getBaseValue();
		double v = base;
		var mods = a.getModifiers().stream().filter(m -> !m.id().getNamespace().equals(CounterCraft.MOD_ID)).toList();
		for (var m : mods) {
			if (m.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) {
				v += m.amount();
			}
		}
		double total = v;
		for (var m : mods) {
			if (m.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE) {
				total += v * m.amount();
			}
		}
		for (var m : mods) {
			if (m.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
				total *= 1 + m.amount();
			}
		}
		return Math.max(0, total / base);
	}

	/** Leaving CS movement (water, ladder, flying...): vanilla takes over with the current motion. */
	public static void release() {
		active = false;
		duck = duckOld = 0;
	}

	/**
	 * One 1/64 s on a ladder: Source's LadderMove. Forward / back move along the view (pitch included) and sideways along
	 * the ladder; the part of that motion pushing into the ladder becomes climbing, so looking up and holding forward
	 * climbs, holding back descends; no gravity; jump pushes off. Ladders face away from their wall; vines, scaffolding
	 * and other climbables count as facing you. Returns true if the player jumped off.
	 */
	private static boolean ladder(LocalPlayer p, double dt, boolean jump, double fwd, double side) {
		Vec3 n = ladderNormal(p);
		Vec3 v;
		boolean jumped = false;
		if (jump) {
			// push off and fly: the ladder lets go for a moment (else the next 1/64 s step would catch you again)
			v = n.scale(LADDER_JUMP);
			jumped = true;
			ladderRelease = LADDER_RELEASE_SECONDS;
			wasOnGround = false;
		} else if (fwd != 0 || side != 0) {
			double yaw = Math.toRadians(p.getYRot());
			Vec3 look = p.getViewVector(1f);
			Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
			v = look.scale(fwd * LADDER_SPEED).add(right.scale(side * LADDER_SPEED));
			double into = v.dot(n);
			v = v.subtract(n.scale(into)).add(0, -into, 0);
			if (p.onGround() && into > 0) {
				v = v.add(n.scale(LADDER_SPEED)); // backing off the bottom of the ladder
			}
			// sideways along the ladder only while there's ladder there: a single column holds you (CS2's fast climb,
			// looking 45 degrees off with forward + strafe, drifts a little sideways and would slide you off it)
			Vec3 along = new Vec3(n.z, 0, -n.x);
			double lateral = v.dot(along);
			if (lateral != 0 && !climbableAt(p, p.position().add(along.scale(Math.signum(lateral) * (p.getBbWidth() / 2 + 0.05))))) {
				v = v.subtract(along.scale(lateral));
			}
		} else {
			v = Vec3.ZERO; // hold still on the ladder
		}
		vel = v;
		Vec3 move = v.scale(dt / CsMove.UNITS_PER_BLOCK);
		p.move(MoverType.SELF, stuck != null ? move.multiply(stuck) : move);
		p.resetFallDistance();
		wasOnGround = p.onGround();
		return jumped;
	}

	/** Minecraft's sneak edge check (Player.maybeBackOffFromEdge): shorten the move until it wouldn't drop off. */
	private static Vec3 backOffEdge(LocalPlayer p, Vec3 move) {
		double drop = p.maxUpStep();
		double x = move.x;
		double z = move.z;
		double step = 0.05;
		double sx = Math.signum(x) * step;
		double sz = Math.signum(z) * step;
		while (x != 0 && canFall(p, x, 0, drop)) {
			if (Math.abs(x) <= step) {
				x = 0;
				break;
			}
			x -= sx;
		}
		while (z != 0 && canFall(p, 0, z, drop)) {
			if (Math.abs(z) <= step) {
				z = 0;
				break;
			}
			z -= sz;
		}
		while (x != 0 && z != 0 && canFall(p, x, z, drop)) {
			x = Math.abs(x) <= step ? 0 : x - sx;
			z = Math.abs(z) <= step ? 0 : z - sz;
		}
		return new Vec3(x, move.y, z);
	}

	private static boolean canFall(LocalPlayer p, double x, double z, double drop) {
		AABB b = p.getBoundingBox();
		return p.level().noCollision(p, new AABB(b.minX + 1e-7 + x, b.minY - drop - 1e-7, b.minZ + 1e-7 + z, b.maxX - 1e-7 + x, b.minY, b.maxZ - 1e-7 + z));
	}

	/** A climbable block at this spot (feet or eye height). */
	private static boolean climbableAt(LocalPlayer p, Vec3 at) {
		var level = p.level();
		return level.getBlockState(net.minecraft.core.BlockPos.containing(at)).is(net.minecraft.tags.BlockTags.CLIMBABLE)
				|| level.getBlockState(net.minecraft.core.BlockPos.containing(at.x, at.y + p.getEyeHeight(), at.z)).is(net.minecraft.tags.BlockTags.CLIMBABLE);
	}

	/** The way the climbable at the player faces (horizontal unit vector, away from its wall). */
	private static Vec3 ladderNormal(LocalPlayer p) {
		for (net.minecraft.core.BlockPos pos : new net.minecraft.core.BlockPos[] {p.blockPosition(), net.minecraft.core.BlockPos.containing(p.getEyePosition())}) {
			var state = p.level().getBlockState(pos);
			if (state.hasProperty(net.minecraft.world.level.block.LadderBlock.FACING)) {
				var d = state.getValue(net.minecraft.world.level.block.LadderBlock.FACING);
				return new Vec3(d.getStepX(), 0, d.getStepZ());
			}
		}
		Vec3 look = p.getViewVector(1f);
		Vec3 flat = new Vec3(-look.x, 0, -look.z);
		return flat.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : flat.normalize();
	}

	/**
	 * Stamina now: as of the last jump or landing, recovered since (from the end of that step: CS2's sub-tick jump lands on
	 * the landing instant).
	 */
	private static double staminaNow() {
		return Math.max(0, stamina - CsMove.STAMINA_RECOVERY * Math.max(0, clock - staminaAt - CsMove.TICK));
	}

	/** One 1/64 s Source move. Returns true if the player jumped. */
	private static boolean step(LocalPlayer p, double dt, boolean wantJump, boolean pressed, double fwd, double side,
			boolean walking, boolean crouching, double maxSpeed, float yaw) {
		boolean onGround = p.onGround();
		// crouch: ducking in the air pulls the feet up (and back down when standing up)
		double before = duck;
		duck = crouching ? Math.min(1, duck + dt / CsMove.DUCK_DOWN_SECONDS) : Math.max(0, duck - dt / CsMove.DUCK_UP_SECONDS);
		if (!onGround && duck != before) {
			p.move(MoverType.SELF, new Vec3(0, (duck - before) * CsMove.AIR_DUCK_LIFT / CsMove.UNITS_PER_BLOCK, 0));
		}
		double speedMul = walking ? CsMove.WALK_MULTIPLIER : 1.0;
		speedMul += (Math.min(1.0, CsMove.DUCK_MULTIPLIER * sneakScale) - speedMul) * duck;
		double cap = maxSpeed * speedMul * speedScale;
		double surface = surfaceFriction(p);

		boolean jumped = false;
		if (onGround && wantJump) {
			// CS2's stamina: a jump right after landing (or jumping) is weaker, recovering over ~0.25 s
			double s = staminaNow();
			double mul = Math.max(0.0, 1.0 - s / 100.0);
			// a press right after the landing showed (auto bunny hopping: on the landing itself) leaves with the speed it
			// landed with, whatever the ground did while the press waited for its tick; any other jump takes the landing
			// slow-down with it
			double since = fresh && CsMove.autoBhop ? 0 : pressed ? (jumpKeyNanos - landNanos) / 1e9 : -1;
			if (since >= 0 && since <= CsMove.BHOP_TIME_WINDOW) {
				double h = Math.hypot(vel.x, vel.z);
				if (h > 1e-6 && stash > h) {
					vel = new Vec3(vel.x * stash / h, vel.y, vel.z * stash / h);
				}
			} else {
				staminaCap(maxSpeed * speedScale, s);
			}
			if (debug) {
				CounterCraft.LOG.info("[countercraft] mv jump pressed {} ms after landing, stamina {}", String.format("%.1f", since * 1000),
						String.format("%.1f", s));
			}
			stamina = Math.min(CsMove.staminaMax, s + CsMove.jumpStamina());
			staminaAt = clock;
			double h = Math.hypot(vel.x, vel.z);
			double limit = CsMove.BHOP_SPEED_CAP * maxSpeed * speedScale;
			Vec3 hv = !CsMove.enableBhop && h > limit ? new Vec3(vel.x * limit / h, 0, vel.z * limit / h) : new Vec3(vel.x, 0, vel.z);
			vel = new Vec3(hv.x, CsMove.JUMP_TAKEOFF * mul * jumpScale, hv.z);
			lastJumpMul = mul;
			onGround = false;
			jumped = true;
		} else if (onGround) {
			friction(dt, surface); // a jump on the landing step skips friction (CS2's stashed speed)
			fresh = false;
		}

		// wish direction from the keys (cl_forwardspeed / cl_sidespeed 450, always clamped to the max speed)
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		double wx = -sin * fwd * CsMove.MOVE_INPUT - cos * side * CsMove.MOVE_INPUT;
		double wz = cos * fwd * CsMove.MOVE_INPUT - sin * side * CsMove.MOVE_INPUT;
		double wishSpeed = Math.hypot(wx, wz);
		double dx = 0;
		double dz = 0;
		if (wishSpeed > 1e-6) {
			dx = wx / wishSpeed;
			dz = wz / wishSpeed;
			wishSpeed = Math.min(wishSpeed, cap);
		}
		if (onGround) {
			// sv_accelerate_use_weapon_speed: walking / crouching accelerate like the knife does, running at the
			// weapon's speed (measured: AK walk and knife walk gain the same 4.65 u/s per tick)
			double scale = walking || duck > 0.5 ? CsMove.RUN_SPEED * speedMul * speedScale : cap;
			accelerate(dx, dz, wishSpeed, CsMove.ACCELERATE * scale * dt * surface);
			vel = new Vec3(vel.x, 0, vel.z);
			staminaCap(maxSpeed * speedScale, staminaNow()); // CS2's landing slow-down: stamina caps your speed too, until it recovers
		} else {
			double add = Math.min(wishSpeed, CsMove.AIR_MAX_WISHSPEED) - (vel.x * dx + vel.z * dz);
			if (add > 0 && wishSpeed > 0) {
				double a = Math.min(add, CsMove.airAccelerate * wishSpeed * dt * surface);
				vel = vel.add(a * dx, 0, a * dz);
			}
		}
		vel = new Vec3(clamp(vel.x), clamp(vel.y), clamp(vel.z));

		// move: exact free fall within the step (Source's half-step gravity), collisions by Minecraft. Levitation instead
		// eases the vertical speed towards its rise, as Minecraft's does (a fifth of the way each 1/20 s), with no gravity
		double vy0 = vel.y;
		double g = levitation >= 0 ? 0 : CsMove.GRAVITY * gravityScale;
		if (levitation >= 0) {
			vel = new Vec3(vel.x, vel.y + (levitation - vel.y) * (1 - Math.pow(0.8, dt * 20)), vel.z);
		}
		Vec3 want = new Vec3(vel.x * dt, vel.y * dt - 0.5 * g * dt * dt, vel.z * dt).scale(1 / CsMove.UNITS_PER_BLOCK);
		vel = new Vec3(vel.x, vel.y - g * dt, vel.z);
		Vec3 from = p.position();
		if (onGround && MovementSettings.edgeGuard.holds(crouching, walking)) {
			// crouching or walking holds you at an edge, as sneaking does in Minecraft
			Vec3 held = backOffEdge(p, want);
			if (held.x != want.x) {
				vel = new Vec3(0, vel.y, vel.z);
			}
			if (held.z != want.z) {
				vel = new Vec3(vel.x, vel.y, 0);
			}
			want = held;
		}
		if (stuck != null) {
			want = want.multiply(stuck);
		}
		p.move(MoverType.SELF, want);
		Vec3 got = p.position().subtract(from);
		if (Math.abs(got.x) + 1e-7 < Math.abs(want.x)) {
			vel = new Vec3(0, vel.y, vel.z);
		}
		if (Math.abs(got.z) + 1e-7 < Math.abs(want.z)) {
			vel = new Vec3(vel.x, vel.y, 0);
		}
		if (p.verticalCollision) {
			vel = new Vec3(vel.x, 0, vel.z);
		}
		surf(p);
		// stay on the ground going down stairs and slabs (Source's StayOnGround) instead of becoming airborne
		if (wasOnGround && !jumped && !p.onGround() && vel.y <= 0) {
			AABB below = p.getBoundingBox().move(0, -SNAP_DOWN, 0);
			if (!p.level().noCollision(p, below)) {
				p.move(MoverType.SELF, new Vec3(0, -SNAP_DOWN, 0));
				if (p.onGround()) {
					vel = new Vec3(vel.x, 0, vel.z);
				}
			}
		}
		boolean now = p.onGround();
		boolean landed = now && !wasOnGround;
		if (landed) {
			landSpeed = Math.max(0, -vy0);
			landNanos = tickNanos + 50_000_000L;
			stash = Math.hypot(vel.x, vel.z);
			fresh = true;
			stamina = Math.min(CsMove.staminaMax, staminaNow() + CsMove.landStamina(landSpeed));
			staminaAt = clock;
			landedThisTick = true;
			if (landSpeed > CsMove.LAND_PUNCH_MIN_SPEED) {
				landPunch = -CsMove.LAND_PUNCH_PER_SPEED * landSpeed;
				landPunchAt = seconds();
			}
		}
		wasOnGround = now;
		Footsteps.update(p, dt, vel, now, duck, jumped, landed, landSpeed);
		clock += dt;
		if (debug) {
			CounterCraft.LOG.info("[countercraft] mv t={} speed={} vy={} y={} ground={} duck={} jump={} mul={}", String.format("%.4f", clock),
					String.format("%.2f", Math.hypot(vel.x, vel.z)), String.format("%.2f", vel.y), String.format("%.4f", p.getY()), now,
					String.format("%.3f", duck), jumped, String.format("%.4f", lastJumpMul));
		}
		return jumped;
	}

	/**
	 * Surf ramps (SurfRampBlock): Minecraft's collision lets CS movement into them, so here the player is pushed out of
	 * any ramp they're in, the shortest way, and the part of the velocity going into it is taken away (Source's
	 * ClipVelocity, overbounce 1): along a ramp you slide; a ramp is never ground, so gravity pulls you down it and
	 * strafing into it holds you up. A few passes settle a box touching several ramps.
	 */
	private static void surf(LocalPlayer p) {
		for (int pass = 0; pass < 3; pass++) {
			AABB box = p.getBoundingBox();
			boolean pushed = false;
			for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
					net.minecraft.util.Mth.floor(box.minX), net.minecraft.util.Mth.floor(box.minY), net.minecraft.util.Mth.floor(box.minZ),
					net.minecraft.util.Mth.floor(box.maxX), net.minecraft.util.Mth.floor(box.maxY), net.minecraft.util.Mth.floor(box.maxZ))) {
				var state = p.level().getBlockState(pos);
				if (!(state.getBlock() instanceof dev.countercraft.block.SurfRampBlock)) {
					continue;
				}
				Vec3[] normal = new Vec3[1];
				Vec3 out = dev.countercraft.block.SurfRampBlock.push(p.level(), pos, state, box, normal);
				if (out == null) {
					continue;
				}
				p.move(MoverType.SELF, out);
				double into = vel.dot(normal[0]);
				if (into < 0) {
					vel = vel.subtract(normal[0].scale(into));
				}
				pushed = true;
				box = p.getBoundingBox();
			}
			if (!pushed) {
				return;
			}
		}
	}

	/** Source friction: speed drops by max(speed, sv_stopspeed) x sv_friction x dt. */
	/**
	 * CS2's landing slow-down: with stamina left, the horizontal speed is held to (1 - 2 x stamina / 100) of the running
	 * speed (measured running; walking and crouching aren't, so their own caps aren't cut).
	 */
	private static void staminaCap(double cap, double s) {
		if (s > 0) {
			double limit = cap * Math.max(0, 1 - CsMove.STAMINA_SPEED * s / 100);
			double h = Math.hypot(vel.x, vel.z);
			if (h > limit) {
				vel = new Vec3(vel.x * limit / h, vel.y, vel.z * limit / h);
			}
		}
	}

	private static void friction(double dt, double surface) {
		double speed = Math.hypot(vel.x, vel.z);
		if (speed < 0.1) {
			vel = new Vec3(0, vel.y, 0);
			return;
		}
		double drop = Math.max(speed, CsMove.STOP_SPEED) * CsMove.FRICTION * dt * surface;
		double k = Math.max(0, speed - drop) / speed;
		vel = new Vec3(vel.x * k, vel.y, vel.z * k);
	}

	private static void accelerate(double dx, double dz, double wishSpeed, double accelSpeed) {
		double add = wishSpeed - (vel.x * dx + vel.z * dz);
		if (add <= 0) {
			return;
		}
		double a = Math.min(add, accelSpeed);
		vel = vel.add(a * dx, 0, a * dz);
	}

	/**
	 * Source's surface friction from the block underfoot: Minecraft's 0.6 (stone, grass...) is CS2's normal ground,
	 * slipperier blocks (ice 0.98) scale friction and acceleration down the way slick surfaces do in Source.
	 */
	private static double surfaceFriction(LocalPlayer p) {
		float f = p.level().getBlockState(p.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction();
		return Math.max(0.02, Math.min(1.0, (1.0 - f) / 0.4));
	}

	private static double clamp(double v) {
		return Math.max(-CsMove.maxVelocity, Math.min(CsMove.maxVelocity, v));
	}

	private static double seconds() {
		return System.nanoTime() / 1e9;
	}

	public static double now() {
		return seconds();
	}
}
