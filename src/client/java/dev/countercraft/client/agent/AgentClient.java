package dev.countercraft.client.agent;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import dev.countercraft.client.movement.CsPlayerMovement;
import dev.countercraft.client.movement.MovementKeys;
import dev.countercraft.gun.CsWeapon;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import dev.countercraft.gun.KnifeItem;
import dev.countercraft.net.AgentAnimPayload;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Players drawn as CS2 agents (the default T agent for now): loads the converted agent data, keeps one
 * {@link AgentAnimator} per player, feeds it each frame from the player's movement, view and held weapon, and takes
 * weapon actions from this client's guns and from the server (other players).
 */
public final class AgentClient {
	/** CS2's default agents (items_game customplayer_t_map_based / customplayer_ct_map_based). */
	public static final String AGENT = "tm_phoenix";
	public static final String AGENT_CT = "ctm_sas";
	private static final double UNITS = 39.37;
	/** First-person legs without the GPU path (no near clip there): the body sits this far behind the eye, so looking down the waist is just behind straight down
	 * and the feet are ahead (matched by eye to a CS2 screenshot). */
	private static final float LEGS_BACK = 0.18f;
	/** First-person legs: the pelvis stays at least this far below the eye. */
	private static final float LEGS_EYE_CLEARANCE = 0.45f;

	private static AgentData data;
	private static boolean loadFailed;
	private static final Map<Integer, State> STATES = new HashMap<>();

	private static final class State {
		final AgentAnimator anim;
		long lastNanos;
		Vec3 lastPos;
		double vx;
		double vy;
		double vz;
		float crouch;
		boolean wasGround = true;
		/** Vanilla arm swing seen last frame (a new swing starts the third-person attack). */
		boolean wasSwinging;
		float lastSwing;
		final AgentRenderer renderer = new AgentRenderer();
		String gun;
		boolean heldBlock;
		/** Equipped gloves (null: the agent's own). */
		dev.countercraft.client.skins.Skins.Glove glove;
		String agent = AGENT;
		dev.countercraft.client.skins.Skins.Skin skin;
		boolean visible = true;
		Vec3 muzzle;
		long muzzleAt;
		String team = dev.countercraft.team.Teams.T;

		State(AgentData d) {
			anim = new AgentAnimator(d);
		}
	}

	private AgentClient() {
	}

	public static void reload(ResourceManager rm) {
		data = null;
		loadFailed = false;
		STATES.clear();
		try {
			data = AgentData.load(path -> {
				try (InputStream in = rm.open(CounterCraft.id("agents/" + path))) {
					return in.readAllBytes();
				}
			});
			if (data.model(AGENT) == null) {
				data = null;
			}
			CounterCraft.LOG.info("[countercraft] agents: {} ready", data == null ? "none" : AGENT);
			dev.countercraft.gun.Hitboxes.set(null, null);
			if (data != null) {
				try (InputStream in = rm.open(CounterCraft.id("agents/hitboxes.json"))) {
					int n = HitboxPoser.pose(data, in.readAllBytes());
					CounterCraft.LOG.info("[countercraft] CS2 hitboxes: {} capsules", n);
				} catch (Exception e) {
					CounterCraft.LOG.info("[countercraft] no CS2 hitboxes ({}): targets keep Minecraft's boxes", e.toString());
				}
			}
		} catch (Exception e) {
			loadFailed = true;
			CounterCraft.LOG.info("[countercraft] no agent data ({}): players keep the Minecraft model until converter/agent runs", e.toString());
		}
	}

	public static boolean enabled() {
		return data != null;
	}

	public static void clear() {
		STATES.clear();
	}

	/**
	 * Every client tick: drop the animation state of entities that are gone (a respawn is a new entity, a player who
	 * left or went out of range is removed), so dead bodies' animators and mesh buffers don't pile up.
	 */
	public static void prune(Minecraft mc) {
		if (mc.level == null) {
			STATES.clear();
			return;
		}
		if (mc.level.getGameTime() % 20 != 0) {
			return;
		}
		STATES.keySet().removeIf(id -> mc.level.getEntity(id) == null);
	}

	private static State state(int id) {
		return STATES.computeIfAbsent(id, k -> new State(data));
	}

	/** A weapon action of some player (from the server, or this client's own). */
	public static void action(int entity, String action, String attack, float seconds) {
		dev.countercraft.client.death.SpectateViewmodel.action(entity, action, attack); // their viewmodel, if watched
		if (data != null) {
			state(entity).anim.action(action, attack, seconds);
		}
	}

	public static void onPayload(AgentAnimPayload p) {
		action(p.entity(), p.action(), p.attack(), p.seconds());
	}

	/** This client's player did something (third person and first-person legs show it at once). */
	public static void local(String action, String attack, float seconds) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) {
			action(p.getId(), action, attack, seconds);
		}
	}

	/** Inspect is client-side in the mod: show it here and tell the server so others see it too. */
	public static void localInspect() {
		local("action_inspect", "", 5f);
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && ClientPlayNetworking.canSend(AgentAnimPayload.TYPE)) {
			ClientPlayNetworking.send(new AgentAnimPayload(p.getId(), "action_inspect", "", 5f));
		}
	}

	/** Advance a player's animation for this frame (once per frame, however often it is asked). */
	public static AgentAnimator animate(AbstractClientPlayer p, float partialTick) {
		if (data == null) {
			return null;
		}
		State s = state(p.getId());
		long now = System.nanoTime();
		if (s.lastNanos != 0 && now - s.lastNanos < 2_000_000L) {
			return s.anim; // already advanced this frame
		}
		float dt = s.lastNanos == 0 ? 0f : (now - s.lastNanos) / 1e9f;
		s.lastNanos = now;
		s.anim.update(input(p, s, partialTick, dt));
		return s.anim;
	}

	/** Gliding: the aim, relative to a body lying along the flight, that looks ahead (CS2's aim reaches about -89). */
	private static final float GLIDE_PITCH = -75f;

	private static AgentAnimator.Input input(AbstractClientPlayer p, State s, float partialTick, float dt) {
		AgentAnimator.Input in = new AgentAnimator.Input();
		in.dt = dt;
		boolean local = p instanceof LocalPlayer lp && CsPlayerMovement.handles(lp) && CsPlayerMovement.active();
		if (local) {
			Vec3 v = CsPlayerMovement.velocity();
			s.vx = v.x;
			s.vy = v.y;
			s.vz = v.z;
			in.onGround = CsPlayerMovement.onGround();
			in.crouch = CsPlayerMovement.duck(partialTick);
			in.walking = MovementKeys.WALK.isDown();
		} else {
			// other players: velocity from their tick-to-tick movement, lightly smoothed
			double k = Math.min(1.0, dt * 12.0);
			s.vx += ((p.getX() - p.xo) * 20 * UNITS - s.vx) * k;
			s.vy += ((p.getY() - p.yo) * 20 * UNITS - s.vy) * k;
			s.vz += ((p.getZ() - p.zo) * 20 * UNITS - s.vz) * k;
			in.onGround = p.onGround();
			float target = p.isCrouching() || p.hasPose(Pose.CROUCHING) ? 1f : 0f;
			s.crouch += Math.signum(target - s.crouch) * Math.min(Math.abs(target - s.crouch), dt / 0.2f);
			in.crouch = s.crouch;
			double sp = Math.hypot(s.vx, s.vz);
			in.walking = sp > 5 && sp < 150 && in.crouch < 0.5f;
		}
		in.vx = s.vx;
		in.vy = s.vy;
		in.vz = s.vz;
		in.jumped = s.wasGround && !in.onGround && s.vy > 50;
		s.wasGround = in.onGround;
		in.onLadder = p.onClimbable() && !in.onGround;
		in.yaw = p.getViewYRot(partialTick);
		// first person: the visible body (legs, belly) stays upright; looking up / down only moves the head and arms,
		// which the viewmodel stands in for. Third person bends the spine with the aim as CS2 does.
		Minecraft mc = Minecraft.getInstance();
		boolean firstPerson = p == mc.player && mc.options.getCameraType().isFirstPerson();
		in.pitch = firstPerson ? 0f : p.getViewXRot(partialTick);
		if (!firstPerson && p.isFallFlying()) {
			// gliding, the body lies along the flight (see submit): the head and arms look ahead along it
			float t = p.getFallFlyingTicks() + partialTick;
			in.pitch += (GLIDE_PITCH - in.pitch) * Math.min(1f, t * t / 100f);
		} else if (!firstPerson) {
			// swimming (and crawling) lies flat the same way: the same look ahead
			in.pitch += (GLIDE_PITCH - in.pitch) * p.getSwimAmount(partialTick);
		}
		in.uprightTorso = firstPerson;
		if (!in.onGround) {
			Vec3 from = p.getPosition(partialTick);
			BlockHitResult hit = p.level().clip(new ClipContext(from, from.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			in.heightAboveGround = (float) ((hit.getType() == HitResult.Type.MISS ? 6.0 : from.y - hit.getLocation().y) * UNITS);
		}
		weapon(wielded(p) ? p.getMainHandItem() : ItemStack.EMPTY, in);
		swing(p, s);
		return in;
	}

	/**
	 * Minecraft attacks (hitting or mining with the hand or a non-CS item) in third person: an item swings like CS2's
	 * knife slash (the agent holds Minecraft items in the knife pose); an empty hand throws a punch. CS2 no longer
	 * ships Danger Zone's fist animations, so the punch is procedural (the right hand jabs along the aim, see
	 * {@link AgentAnimator#punch}): an approximation.
	 */
	private static void swing(AbstractClientPlayer p, State s) {
		var current = p.getCurrentSwing();
		boolean swinging = p.isSwinging() && current != null && current.hand() == net.minecraft.world.InteractionHand.MAIN_HAND;
		float progress = p.getSwingAnimation(1f);
		// a new swing: one starts, or a running one restarts (its progress jumps back)
		boolean fresh = swinging && (!s.wasSwinging || progress < s.lastSwing - 0.05f);
		s.wasSwinging = swinging;
		s.lastSwing = progress;
		if (!fresh || p.getMainHandItem().getItem() instanceof dev.countercraft.gun.CsWeapon) {
			return;
		}
		if (p.getMainHandItem().isEmpty()) {
			s.anim.punch();
		} else if (current.animation().type() == net.minecraft.world.item.SwingAnimationType.STAB) {
			// a spear's jab: CS2's knife stab (a thrust), with Minecraft's own spear motion on the item (AgentRenderer.submitItem)
			s.anim.action("action_attack", "attack_knife_heavymiss", Math.max(0.4f, current.durationTicks() / 20f));
		} else {
			s.anim.action("action_attack", "attack_knife_lightmiss", 0.4f);
		}
	}

	/**
	 * Whether this player wields what's in their hand as a CS weapon: yours only in CS mode (a CS weapon in the
	 * Minecraft hotbar is just an item); other players' CS mode isn't known here, so theirs always.
	 */
	private static boolean wielded(AbstractClientPlayer p) {
		return p != Minecraft.getInstance().player || dev.countercraft.team.CsInventory.active(p);
	}

	/** CS2's weapon_type / weapon_category ids for a held item ("" for anything that isn't a CS weapon). */
	static void weapon(ItemStack stack, AgentAnimator.Input in) {
		if (!(stack.getItem() instanceof CsWeapon w)) {
			// anything else (Minecraft items, empty hand): CS2's default knife animations, third person only
			in.weaponType = "weapon_knife";
			in.weaponCategory = "weapon_category_knife";
			return;
		}
		String id = w.weaponId();
		in.weaponType = weaponType(id);
		in.deploySeconds = w.deploySeconds();
		if (stack.getItem() instanceof GunItem gun) {
			in.weaponCategory = switch (gun.spec().type()) {
				case "rifle" -> "weapon_category_rifle";
				case "sniper_rifle" -> "weapon_category_sniper";
				case "submachinegun" -> "weapon_category_smg";
				case "shotgun" -> "weapon_category_shotgun";
				case "machinegun" -> "weapon_category_machinegun";
				default -> "weapon_category_pistol";
			};
			in.silenced = gun.silenced(stack);
			in.ammo = stack.getOrDefault(ModComponents.AMMO, 1);
		} else if (stack.getItem() instanceof KnifeItem) {
			in.weaponCategory = "weapon_category_knife";
		} else if (stack.getItem() instanceof GrenadeItem) {
			in.weaponCategory = "weapon_category_grenade";
		}
	}

	static String weaponType(String id) {
		return switch (id) {
			case "knife_ct" -> "weapon_knife";
			case "knife_navaja" -> "weapon_knife_gypsy_jackknife";
			case "knife_talon" -> "weapon_knife_widowmaker";
			case "knife_bowie" -> "weapon_knife_survival_bowie";
			default -> "weapon_" + id;
		};
	}

	/** Render-state extraction of a player (third person, other players): advance their animation, note the held gun. */
	public static void extract(AbstractClientPlayer p, net.minecraft.client.renderer.entity.state.AvatarRenderState state, float partialTick) {
		if (data == null || p.isSpectator()) {
			return;
		}
		State s = state(p.getId());
		animate(p, partialTick);
		ItemStack held = p.getMainHandItem();
		s.gun = wielded(p) && held.getItem() instanceof CsWeapon w ? w.weaponId() : null;
		s.heldBlock = held.getItem() instanceof net.minecraft.world.item.BlockItem;
		s.skin = s.gun == null ? null : dev.countercraft.client.skins.Skins.of(held);
		s.team = dev.countercraft.team.Teams.of(p);
		s.agent = agentFor(p);
		s.glove = gloveFor(p);
		s.visible = !p.isInvisible();
	}

	/** Draw a player as their agent; false to let Minecraft draw them (no agent data, invisible, spectator). */
	/** A player's agent as last animated: model name, bone matrices (model space) and body yaw; null if none. */
	public record Snapshot(String agent, float[] model, float bodyYaw) {
	}

	public static Snapshot snapshot(int entityId) {
		State s = STATES.get(entityId);
		if (data == null || s == null || Float.isNaN(s.anim.bodyYaw)) {
			return null;
		}
		return new Snapshot(s.agent, s.anim.model.clone(), s.anim.bodyYaw);
	}

	/** Where this player's agent drew its gun's muzzle in the last 0.2 s (world position), or null. */
	public static Vec3 muzzle(int entityId) {
		State s = STATES.get(entityId);
		return s == null || s.muzzle == null || System.nanoTime() - s.muzzleAt > 200_000_000L ? null : s.muzzle;
	}

	public static boolean submit(net.minecraft.client.renderer.entity.state.AvatarRenderState state, PoseStack ps,
			net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?, ?> renderer) {
		if (data == null || state.isSpectator) {
			return false;
		}
		if (Ragdolls.replaces(state.id)) {
			return true; // drawn as their ragdoll instead
		}
		State s = STATES.get(state.id);
		if (s == null || !s.visible || Float.isNaN(s.anim.bodyYaw)) {
			return false;
		}
		int overlay = net.minecraft.client.renderer.entity.LivingEntityRenderer.getOverlayCoords(state, 0f);
		ps.pushPose();
		if (state.isFallFlying && !state.isAutoSpinAttack) {
			// gliding: lying flat along the flight, head first, as Minecraft tips the player (the turn about the feet); the
			// head and arms keep animating (see input: they look ahead)
			float g = state.fallFlyingScale();
			ps.rotate(com.mojang.math.Axis.YP.rotationDegrees(-s.anim.bodyYaw)); // the agent's frame: facing +Z
			ps.rotate(com.mojang.math.Axis.XP.rotationDegrees(g * (90f + state.xRot)));
			if (state.shouldApplyFlyingYRot) {
				ps.rotate(com.mojang.math.Axis.YP.rotation(state.flyingYRot));
			}
			ps.rotate(com.mojang.math.Axis.YP.rotationDegrees(s.anim.bodyYaw)); // the renderer turns by the body yaw itself
		} else if (state.swimAmount > 0f) {
			// swimming (sprint-swimming, crawling): lying flat like gliding, as Minecraft tips a swimming player (along the
			// aim in water, level out of it), shifted into the swimming hitbox as Minecraft does
			ps.rotate(com.mojang.math.Axis.YP.rotationDegrees(-s.anim.bodyYaw));
			ps.rotate(com.mojang.math.Axis.XP.rotationDegrees(state.swimAmount * (state.isInWater ? 90f + state.xRot : 90f)));
			if (state.isVisuallySwimming) {
				ps.translate(0f, -1f, -0.3f);
			}
			ps.rotate(com.mojang.math.Axis.YP.rotationDegrees(s.anim.bodyYaw));
		}
		s.renderer.lastMuzzle = null;
		s.renderer.finish = s.skin;
		s.renderer.glove = s.glove;
		s.renderer.outline = state.outlineColor; // Minecraft's glow, in the agent's shape
		try {
			s.renderer.submit(data, data.model(s.agent), s.anim, s.gun, ps, collector, state.lightCoords, overlay, false);
		} finally {
			s.renderer.outline = 0;
		}
		if (s.renderer.lastMuzzle != null) {
			org.joml.Vector3f m = s.renderer.lastMuzzle;
			s.muzzle = Minecraft.getInstance().gameRenderer.mainCamera().position().add(m.x(), m.y(), m.z());
			s.muzzleAt = System.nanoTime();
		}
		if (s.gun == null && !state.rightHandItemState.isEmpty()) {
			// Minecraft items are held like the team's default knife (blocks: upright in the hand)
			String knife = s.heldBlock ? null : dev.countercraft.team.Teams.CT.equals(s.team) ? "knife_ct" : "knife_t";
			s.renderer.submitItem(data, s.anim, state.rightHandItemState, state.rightHandItemStack, state, ps, collector, state.lightCoords, overlay,
					state.outlineColor, knife);
		}
		wings(state, ps, collector, s, renderer);
		ps.popPose();
		return true;
	}

	/**
	 * An elytra in the chest slot, worn on the agent's upper back: Minecraft's own wings layer (its model, texture, cape
	 * and the flapping from the render state), drawn in the vanilla body's space (Y down, facing -Z) at the agent's
	 * chest bone, behind it by about the vest's depth (an approximation).
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void wings(net.minecraft.client.renderer.entity.state.AvatarRenderState state, PoseStack ps,
			net.minecraft.client.renderer.SubmitNodeCollector collector, State s, net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?, ?> renderer) {
		if (renderer == null || state.chestEquipment.isEmpty() || !state.chestEquipment.has(net.minecraft.core.component.DataComponents.GLIDER)) {
			return;
		}
		int chest = data.skeleton().index("spine_3");
		if (chest < 0) {
			return;
		}
		for (var layer : ((dev.countercraft.client.mixin.LivingEntityRendererInvoker) renderer).countercraft$layers()) {
			if (layer instanceof net.minecraft.client.renderer.entity.layers.WingsLayer wl) {
				float[] m = s.anim.model;
				ps.pushPose();
				ps.rotate(com.mojang.math.Axis.YP.rotationDegrees(-s.anim.bodyYaw));
				ps.translate(m[chest * 12 + 3], m[chest * 12 + 7] + WINGS_LIFT, m[chest * 12 + 11] - WINGS_BACK);
				ps.rotate(com.mojang.math.Axis.XP.rotationDegrees(180f));
				wl.submit(ps, collector, state.lightCoords, state, state.yRot, state.xRot);
				ps.popPose();
				return;
			}
		}
	}

	/** Where the elytra hangs from on an agent: above the chest bone (blocks), behind it (the vest's depth). */
	private static final float WINGS_LIFT = 0.1f;
	private static final float WINGS_BACK = 0.12f;

	/** A player's equipped gloves: yours from the loadout, others' from their synced cosmetics; null for none. */
	public static dev.countercraft.client.skins.Skins.Glove gloveFor(net.minecraft.world.entity.player.Player p) {
		var side = p == Minecraft.getInstance().player ? dev.countercraft.client.loadout.LoadoutConfig.side(dev.countercraft.team.Teams.of(p))
				: dev.countercraft.team.Cosmetics.of(p);
		return side.gloves().isEmpty() ? null : dev.countercraft.client.skins.Skins.glove(side.gloves(), side.gloveSkin());
	}

	/** Your gloves for a team (the loadout's), for the menus' previews; null for none. */
	public static dev.countercraft.client.skins.Skins.Glove gloveFor(String team) {
		var side = dev.countercraft.client.loadout.LoadoutConfig.side(team);
		return side.gloves().isEmpty() ? null : dev.countercraft.client.skins.Skins.glove(side.gloves(), side.gloveSkin());
	}

	/** Your agent model for a team: the loadout's agent if the pack has its model, else the team's default agent. */
	public static String agentFor(String team) {
		String chosen = dev.countercraft.client.loadout.LoadoutConfig.side(team).agent();
		if (!chosen.isEmpty() && data != null && data.model(chosen) != null) {
			return chosen;
		}
		String a = dev.countercraft.team.Teams.CT.equals(team) ? AGENT_CT : AGENT;
		return data != null && data.model(a) == null ? AGENT : a;
	}

	/** A player's agent model: yours from the loadout, others' from their synced cosmetics; the team default otherwise. */
	public static String agentFor(net.minecraft.world.entity.player.Player p) {
		String team = dev.countercraft.team.Teams.of(p);
		if (p == Minecraft.getInstance().player) {
			return agentFor(team);
		}
		String chosen = dev.countercraft.team.Cosmetics.of(p).agent();
		if (!chosen.isEmpty() && data != null && data.model(chosen) != null) {
			return chosen;
		}
		String a = dev.countercraft.team.Teams.CT.equals(team) ? AGENT_CT : AGENT;
		return data != null && data.model(a) == null ? AGENT : a;
	}

	public static AgentData data() {
		return data;
	}

	static AgentRenderer renderer(int id) {
		return state(id).renderer;
	}

	/**
	 * First person: CS2 shows your own legs when you look down. Draw the local player's agent without the upper body
	 * (which the viewmodel replaces), a little behind the eye.
	 */
	public static void submitLegs(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (data == null || p == null || !mc.options.getCameraType().isFirstPerson() || p.isSpectator() || p.isSleeping() || p.isDeadOrDying()
				|| !dev.countercraft.client.options.CcOptions.firstPersonLegs()) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		AgentAnimator a = animate(p, partial);
		if (a == null) {
			return;
		}
		Camera cam = mc.gameRenderer.mainCamera();
		Vec3 pos = p.getPosition(partial).subtract(cam.position());
		double yr = Math.toRadians(a.bodyYaw);
		// like CS2 (measured: the front toe lands 0.52 m ahead of the eye, as the model has it), the body stands right under
		// the eye; the GPU path's near clip hides the cut-off body. Without it, push the body back as before.
		double back = dev.countercraft.client.gpu.GpuAgents.active() ? 0.0 : LEGS_BACK;
		pos = pos.add(Math.sin(yr) * back, 0, -Math.cos(yr) * back);
		// never let the body reach the camera (crouch-jumps tuck the legs up): keep the pelvis well below the eye, and
		// the neck base below it too
		double drop = 0;
		int pelvis = data.skeleton().index("pelvis");
		if (pelvis >= 0) {
			drop = Math.max(drop, pos.y + a.model[pelvis * 12 + 7] + LEGS_EYE_CLEARANCE);
		}
		if (drop > 0) {
			pos = pos.add(0, -drop, 0);
		}
		PoseStack ps = ctx.poseStack();
		ps.pushPose();
		ps.translate(pos.x, pos.y, pos.z);
		int light = net.minecraft.util.LightCoordsUtil.getLightCoords(p.level(), BlockPos.containing(p.getEyePosition(partial)));
		renderer(p.getId()).submit(data, data.model(agentFor(dev.countercraft.team.Teams.of(p))), a, null, ps, ctx.submitNodeCollector(), light,
				OverlayTexture.NO_OVERLAY, true);
		ps.popPose();
	}
}
