package dev.countercraft.client.agent;

import dev.countercraft.client.agent.graph.GraphDef;
import dev.countercraft.client.agent.graph.GraphInstance;

/**
 * One player's CS2 third-person animation: CS2's compiled graph (run by {@link GraphInstance}) fed with the control
 * parameters CS2's game code would set, followed by the aim and hand IK that CS2 does in code (its AimCS node).
 *
 * <p>What is CS2's data: every clip, blend space, state machine, transition time, layer and bone mask. What is
 * reimplemented here (approximations, flagged): the ground-action sequencing (idle / start / move / plant-and-turn /
 * turn-on-spot), how far the feet lag the view before turning, how the aim is spread over the spine, and the hand IK.
 */
public final class AgentAnimator {
	/** Input for one frame (Minecraft units converted by the caller where noted). */
	public static final class Input {
		/** Seconds since the last update. */
		public float dt;
		/** Horizontal velocity, CS units/s, Minecraft world axes. */
		public double vx;
		public double vz;
		public double vy;
		public boolean onGround;
		public boolean onLadder;
		public boolean jumped;
		/** 0 standing .. 1 crouched. */
		public float crouch;
		public boolean walking;
		/** Eye yaw / pitch, Minecraft degrees (yaw clockwise from +Z, pitch positive down). */
		public float yaw;
		public float pitch;
		/** Height above the ground, CS units. */
		public float heightAboveGround;
		public String weaponType = "";
		public String weaponCategory = "";
		public boolean silenced;
		public float ammo = 1f;
		public float flashed;
		/** First person (own legs): keep the spine at its rest orientation, no aim; the torso can't lean or twist. */
		public boolean uprightTorso;
		/** CS2 m_flDeployDuration of the held weapon (how long its draw plays). */
		public float deploySeconds = 1f;
	}

	private static final float TURN_THRESHOLD = 50f;
	private static final float MOVE_THRESHOLD = 5f;
	private static final float START_SECONDS = 0.15f;
	private static final float PLANT_SECONDS = 0.35f;

	private final AgentData data;
	final GraphInstance graph;
	private final GraphInstance.Params params = new GraphInstance.Params();
	final GraphInstance.Ctx ctx;
	public final Pose pose;
	/** Model-space matrices (3x4 each) of the animation skeleton after aim and IK. */
	public final float[] model;
	/** Feet (body) yaw, Minecraft degrees: the model is drawn facing this way. */
	public float bodyYaw = Float.NaN;

	private String groundAction = "ground_action_idle";
	private float groundTime;
	private float prevSpeed;
	private float lastMoveSpeed;
	private double lastDirX;
	private double lastDirZ;
	private boolean wasGround = true;
	private float airTime;
	private String airAction = "";
	private float airActionTime = 10f;
	private float turnLeft;
	private float turnTotal;

	private String action = "action_idle";
	private float actionLeft;
	private boolean actionReset;
	private String pendingAction;
	private String pendingAttack;
	private float pendingSeconds;
	private String attackType = "";
	private String weaponType = "";
	private float aimYaw;

	/** Where the upper body aims, degrees left of the feet. */
	public float aimYaw() {
		return aimYaw;
	}
	/** Seconds into a procedural punch, or negative when none is playing. */
	private float punchT = -1f;
	private float aimPitch;

	/** Where the upper body aims, degrees up. */
	public float aimPitch() {
		return aimPitch;
	}

	public AgentAnimator(AgentData data) {
		this.data = data;
		AgentSkeleton skel = data.skeleton();
		GraphDef def = data.graph(data.rootGraph);
		graph = def == null ? null : new GraphInstance(def, params, data);
		ctx = new GraphInstance.Ctx(data);
		pose = new Pose(skel.bones());
		pose.setReference(skel);
		model = new float[skel.bones() * 12];
		params.set("action", action);
		params.set("idle_variation", (float) Math.random());
		params.set("deploy_variation", (float) Math.random());
		params.set("attack_variation", (float) Math.random());
		params.set("weapon_action_speedscale", 1f);
		params.set("move_type", "move_type_ground");
		params.set("ground_action", groundAction);
		params.set("ground_action_direction_id", "N");
		params.set("move_direction_id", "N");
	}

	/**
	 * A weapon action seen on this player: "action_attack" (with an attack type such as attack_gun_primaryfire),
	 * "action_reload", "action_deploy", "action_inspect", "action_silencer_attach" / "detach". It holds for
	 * {@code seconds}, then the graph returns to idle.
	 */
	public void action(String id, String attack, float seconds) {
		boolean busy = action.equals("action_deploy") || action.equals("action_reload") || action.equals("action_inspect");
		if ("action_attack".equals(id) && busy) {
			// CS2's weapon graph leaves deploy / reload / inspect only through action_idle: one idle frame first
			pendingAction = id;
			pendingAttack = attack;
			pendingSeconds = seconds;
			action = "action_idle";
			actionLeft = 0f;
			return;
		}
		action = id;
		actionLeft = seconds;
		if ("action_attack".equals(id)) {
			actionReset = true;
			attackType = attack == null ? "" : attack;
		}
	}

	public void update(Input in) {
		float dt = Math.max(0f, Math.min(in.dt, 0.1f));
		if (Float.isNaN(bodyYaw)) {
			bodyYaw = in.yaw;
		}
		// weapon: a new weapon type deploys
		if (!in.weaponType.equals(weaponType)) {
			weaponType = in.weaponType;
			action("action_deploy", null, in.deploySeconds);
		}
		actionLeft -= dt;
		if (actionLeft <= 0f && !"action_idle".equals(action)) {
			action = "action_idle";
			attackType = "";
		}

		double speed = Math.hypot(in.vx, in.vz);
		feet(in, dt, speed);
		// body-local velocity: x forward, y left (CS axes), from the feet yaw
		double yr = Math.toRadians(bodyYaw);
		double fx = -Math.sin(yr), fz = Math.cos(yr);
		double lx = Math.cos(yr), lz = Math.sin(yr); // left of facing (Minecraft: +X is left when facing +Z)
		float sx = (float) (in.vx * fx + in.vz * fz);
		float sy = (float) (in.vx * lx + in.vz * lz);
		ground(in, dt, (float) speed, sx, sy);
		air(in, dt);

		aimYaw = wrap(bodyYaw - in.yaw); // CS yaw grows to the left; Minecraft's to the right
		aimPitch = -in.pitch;
		params.set("move_speed_horizontal", (float) speed);
		params.set("move_speed_x", sx);
		params.set("move_speed_y", sy);
		params.set("move_crouch_amount", in.crouch);
		params.set("move_is_walking", in.walking);
		params.set("aim_angle_pitch", aimPitch);
		params.set("aim_angle_yaw", aimYaw);
		params.set("weapon_type", in.weaponType);
		params.set("weapon_category", in.weaponCategory);
		params.set("weapon_is_silenced", in.silenced);
		params.set("weapon_ammo", in.ammo);
		params.set("flashed_amount", in.flashed);
		params.set("action", action);
		params.set("attack_type", attackType);
		params.set("action_reset", actionReset);
		actionReset = false;

		boolean applyPending = pendingAction != null;
		if (graph != null) {
			graph.evaluate(ctx, dt, pose);
		} else {
			pose.setReference(data.skeleton());
		}
		if (applyPending) {
			String p = pendingAction;
			pendingAction = null;
			action(p, pendingAttack, pendingSeconds);
		}
		pose.r[0] = data.skeleton().refR[0];
		pose.r[1] = data.skeleton().refR[1];
		pose.r[2] = data.skeleton().refR[2];
		pose.r[3] = data.skeleton().refR[3];
		pose.t[0] = pose.t[1] = pose.t[2] = 0f;
		fk();
		if (in.uprightTorso) {
			upright();
		} else if (!in.weaponCategory.isEmpty()) {
			AgentAim.apply(data.skeleton(), model, aimPitch, aimYaw, punchReach(dt));
		}
	}

	/** Throw a punch with the right hand (empty-handed Minecraft attacks; approximation, see AgentClient#swing). */
	public void punch() {
		punchT = 0f;
	}

	/** Punch: out to full reach in 0.08 s, held a moment, back by 0.3 s. Metres the right hand is pushed along the aim. */
	private float punchReach(float dt) {
		if (punchT < 0f) {
			return 0f;
		}
		punchT += dt;
		float t = punchT;
		float k;
		if (t < 0.08f) {
			float u = t / 0.08f;
			k = 1f - (1f - u) * (1f - u);
		} else if (t < 0.12f) {
			k = 1f;
		} else if (t < 0.3f) {
			float u = (t - 0.12f) / 0.18f;
			k = 1f - u * u * (3f - 2f * u);
		} else {
			punchT = -1f;
			k = 0f;
		}
		return 0.35f * k;
	}

	/**
	 * Menus (buy menu, loadout): the pose straight from one clip at {@code seconds} (held on its last frame), no graph,
	 * root at the origin facing +Z. False if the clip isn't in the pack.
	 */
	public boolean showClip(String clipId, float seconds) {
		AgentClip c = data.clip(clipId);
		if (c == null) {
			return false;
		}
		pose.setReference(data.skeleton());
		c.sample(c.duration > 0 ? Math.min(1f, Math.max(0f, seconds / c.duration)) : 0f, pose);
		pose.r[0] = data.skeleton().refR[0];
		pose.r[1] = data.skeleton().refR[1];
		pose.r[2] = data.skeleton().refR[2];
		pose.r[3] = data.skeleton().refR[3];
		pose.t[0] = pose.t[1] = pose.t[2] = 0f;
		fk();
		bodyYaw = 0f;
		return true;
	}

	private static final String[] SPINE = {"spine_0", "spine_1", "spine_2", "spine_3"};
	private float[] restModel;

	/**
	 * Turn each spine bone (and everything on it) back to its rest orientation in model space, about its animated
	 * position: the torso stands straight up over wherever the pelvis is, whatever the clips do.
	 */
	private void upright() {
		AgentSkeleton skel = data.skeleton();
		int n = skel.bones();
		if (restModel == null) {
			restModel = new float[n * 12];
			float[] local = new float[12];
			for (int i = 0; i < n; i++) {
				Mat.fromTrs(skel.refR, i * 4, skel.refT, i * 3, 1f, local, 0);
				int par = skel.parents[i];
				if (par < 0) {
					System.arraycopy(local, 0, restModel, i * 12, 12);
				} else {
					Mat.mul(restModel, par * 12, local, 0, restModel, i * 12);
				}
			}
		}
		float[] inv = new float[12];
		float[] d = new float[12];
		float[] tmp = new float[12];
		for (String name : SPINE) {
			int b = skel.index(name);
			if (b < 0) {
				continue;
			}
			// d = rest rotation x current rotation^-1, about the bone's position
			Mat.invertRigid(model, b * 12, inv, 0);
			Mat.mul(restModel, b * 12, inv, 0, d, 0);
			float px = model[b * 12 + 3], py = model[b * 12 + 7], pz = model[b * 12 + 11];
			d[3] = px - (d[0] * px + d[1] * py + d[2] * pz);
			d[7] = py - (d[4] * px + d[5] * py + d[6] * pz);
			d[11] = pz - (d[8] * px + d[9] * py + d[10] * pz);
			AgentAim.subtree(skel, model, b, d, tmp);
		}
	}

	/** Feet yaw: follows the view while moving; when standing it lags until the view is far enough off, then turns. */
	private void feet(Input in, float dt, double speed) {
		float delta = wrap(in.yaw - bodyYaw);
		if (speed > MOVE_THRESHOLD || !in.onGround) {
			bodyYaw += approach(delta, 720f * dt);
			turnLeft = 0f;
			return;
		}
		if (turnLeft > 0f) {
			// turning on the spot: rotate with the turn clip
			float step = Math.signum(delta) * Math.min(Math.abs(delta), turnTotal / 0.45f * dt);
			bodyYaw += step;
			turnLeft -= dt;
			if (Math.abs(wrap(in.yaw - bodyYaw)) < 1f) {
				turnLeft = 0f;
			}
			return;
		}
		if (Math.abs(delta) > TURN_THRESHOLD) {
			turnTotal = Math.abs(delta);
			turnLeft = 0.45f;
			params.set("ground_turn_angle_or_velocity", -Math.signum(delta) * Math.min(45f, Math.abs(delta)));
		}
	}

	private void ground(Input in, float dt, float speed, float sx, float sy) {
		groundTime += dt;
		String next = groundAction;
		boolean moving = speed > MOVE_THRESHOLD;
		double dirX = speed > 1e-3 ? in.vx / speed : 0;
		double dirZ = speed > 1e-3 ? in.vz / speed : 0;
		switch (groundAction) {
			case "ground_action_idle", "ground_action_turn_on_spot" -> {
				if (moving) {
					next = "ground_action_start";
					params.set("move_direction_id", direction8(sx, sy));
				} else if (turnLeft > 0f) {
					next = "ground_action_turn_on_spot";
				} else {
					next = "ground_action_idle";
				}
			}
			case "ground_action_start" -> {
				if (!moving) {
					next = "ground_action_idle";
				} else if (groundTime >= START_SECONDS) {
					next = "ground_action_move";
				}
			}
			case "ground_action_move" -> {
				if (!moving) {
					next = "ground_action_idle";
					params.set("move_speed_horizontal_previous", lastMoveSpeed);
				} else if (lastMoveSpeed >= 110f && dirX * lastDirX + dirZ * lastDirZ < -0.5) {
					// sharp reversal at speed: plant and turn toward the new direction
					next = "ground_action_plant_and_turn";
					params.set("move_speed_horizontal_previous", lastMoveSpeed);
					params.set("ground_action_direction_id", direction4(sx, sy));
				}
			}
			case "ground_action_plant_and_turn" -> {
				if (!moving) {
					next = "ground_action_idle";
				} else if (groundTime >= PLANT_SECONDS) {
					next = "ground_action_move";
				}
			}
			default -> next = moving ? "ground_action_move" : "ground_action_idle";
		}
		if (!next.equals(groundAction)) {
			groundAction = next;
			groundTime = 0f;
		}
		if (moving) {
			lastMoveSpeed = speed;
			lastDirX = dirX;
			lastDirZ = dirZ;
		}
		prevSpeed = speed;
		params.set("ground_action", groundAction);
	}

	private void air(Input in, float dt) {
		airActionTime += dt;
		String moveType;
		if (in.onLadder) {
			moveType = "move_type_ladder";
		} else if (in.onGround) {
			moveType = "move_type_ground";
			if (!wasGround) {
				airAction = "air_action_land";
				airActionTime = 0f;
			}
			airTime = 0f;
		} else {
			airTime += dt;
			if (in.jumped || wasGround && in.vy > 50) {
				airAction = "air_action_jump";
				airActionTime = 0f;
			} else if (wasGround) {
				airAction = "air_action_start_fall";
				airActionTime = 0f;
			}
			moveType = airAction.equals("air_action_jump") && airActionTime < 0.1f ? "move_type_jump" : "move_type_air";
		}
		wasGround = in.onGround;
		params.set("move_type", moveType);
		params.set("air_action", airActionTime < 0.2f ? airAction : "");
		params.set("air_height_above_ground", in.heightAboveGround);
	}

	private void fk() {
		AgentSkeleton skel = data.skeleton();
		int n = skel.bones();
		float[] local = new float[12];
		for (int i = 0; i < n; i++) {
			Mat.fromTrs(pose.r, i * 4, pose.t, i * 3, pose.s[i], local, 0);
			int p = skel.parents[i];
			if (p < 0) {
				System.arraycopy(local, 0, model, i * 12, 12);
			} else {
				Mat.mul(model, p * 12, local, 0, model, i * 12);
			}
		}
	}

	private static String direction8(float x, float y) {
		// x forward, y left; N = forward, E = right
		double a = Math.toDegrees(Math.atan2(-y, x));
		String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		return dirs[Math.floorMod((int) Math.round(a / 45.0), 8)];
	}

	private static String direction4(float x, float y) {
		double a = Math.toDegrees(Math.atan2(-y, x));
		String[] dirs = {"N", "E", "S", "W"};
		return dirs[Math.floorMod((int) Math.round(a / 90.0), 4)];
	}

	private static float approach(float delta, float max) {
		return Math.abs(delta) <= max ? delta : Math.signum(delta) * max;
	}

	static float wrap(float a) {
		a %= 360f;
		if (a > 180f) {
			a -= 360f;
		}
		if (a < -180f) {
			a += 360f;
		}
		return a;
	}

	/** Debug: the graph's active states, layers and referenced graphs. */
	public String describe() {
		return graph == null ? "(no graph)" + System.lineSeparator() : graph.describe(ctx);
	}

	/** Debug: the active state paths of the graph (top machines). */
	public String debugState() {
		return groundAction + " " + action + " body=" + Math.round(bodyYaw) + " aim=" + Math.round(aimYaw) + "/" + Math.round(aimPitch);
	}
}
