package dev.countercraft.client.agent;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.net.RagdollPayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Player ragdolls, like CS2: when a player dies (server {@link RagdollPayload}), their agent, in the pose it had, turns
 * into CS2's ragdoll for that agent ({@link RagdollDef}, simulated by {@link RagdollSim}), pushed by the killing hit. The
 * body stays where it falls until that player respawns. Bones without a physics body keep their pose relative to their
 * parent (fingers, neck, clavicles follow).
 */
public final class Ragdolls {
	/** At most this many bodies at once (the oldest goes first). */
	private static final int MAX = 24;

	private static final class Ragdoll {
		final UUID owner;
		final int entityId;
		final String agent;
		final RagdollSim sim;
		/** Death pose: each bone's local matrix (relative to its parent) and the world matrix of the root chain. */
		final float[] local;
		final float[] deathWorld;
		final Vec3 origin;
		final AgentAnimator anim;
		final AgentRenderer renderer = new AgentRenderer();
		/** Physics body per animation bone, or -1. */
		final int[] bodyOf;
		/** The player who died (a respawn makes a new one), and whether this client has seen them dead yet. */
		final Player died;
		boolean seenDead;

		Ragdoll(UUID owner, int entityId, String agent, RagdollSim sim, float[] local, float[] deathWorld, Vec3 origin, AgentAnimator anim, int[] bodyOf, Player died) {
			this.died = died;
			this.owner = owner;
			this.entityId = entityId;
			this.agent = agent;
			this.sim = sim;
			this.local = local;
			this.deathWorld = deathWorld;
			this.origin = origin;
			this.anim = anim;
			this.bodyOf = bodyOf;
		}
	}

	private static final List<Ragdoll> RAGDOLLS = new ArrayList<>();

	private Ragdolls() {
	}

	/** This entity is drawn as a ragdoll (its own agent model is hidden). */
	public static boolean replaces(int entityId) {
		for (Ragdoll r : RAGDOLLS) {
			if (r.entityId == entityId) {
				return true;
			}
		}
		return false;
	}

	public static void clear() {
		RAGDOLLS.clear();
		RagdollDef.clear();
	}

	/** The local player's ragdoll's pelvis (world), for the death cam, or null. */
	public static Vec3 bodyOf(UUID owner, float partial) {
		for (Ragdoll r : RAGDOLLS) {
			if (r.owner.equals(owner)) {
				RagdollSim.Body pelvis = r.sim.bodies.length > 0 ? r.sim.bodies[0] : null;
				for (RagdollSim.Body b : r.sim.bodies) {
					if (r.anim != null && AgentClient.data().skeleton().names[b.bone].equals("pelvis")) {
						pelvis = b;
					}
				}
				if (pelvis == null) {
					return null;
				}
				Vector3d x = new Vector3d(pelvis.xTick).lerp(pelvis.x, partial);
				return new Vec3(x.x, x.y, x.z);
			}
		}
		return null;
	}

	public static void onPayload(RagdollPayload p) {
		Minecraft mc = Minecraft.getInstance();
		AgentData data = AgentClient.data();
		if (mc.level == null || data == null) {
			return;
		}
		Entity e = mc.level.getEntity(p.entity());
		if (!(e instanceof Player player)) {
			return;
		}
		AgentClient.Snapshot snap = AgentClient.snapshot(e.getId());
		if (snap == null) {
			return;
		}
		RagdollDef def = RagdollDef.of(snap.agent(), data.skeleton());
		if (def == null) {
			return;
		}
		AgentSkeleton skel = data.skeleton();
		int n = skel.bones();
		// the death pose in the world: entity position, turned by the body yaw (as the agent was drawn)
		Vec3 pos = e.position();
		float[] world = new float[n * 12];
		float[] place = placement(pos, snap.bodyYaw());
		for (int i = 0; i < n; i++) {
			Mat.mul(place, 0, snap.model(), i * 12, world, i * 12);
		}
		float[] local = new float[n * 12];
		float[] inv = new float[12];
		for (int i = 0; i < n; i++) {
			int par = skel.parents[i];
			if (par < 0) {
				System.arraycopy(snap.model(), i * 12, local, i * 12, 12);
			} else {
				Mat.invertRigid(snap.model(), par * 12, inv, 0);
				Mat.mul(inv, 0, snap.model(), i * 12, local, i * 12);
			}
		}
		RagdollSim sim = new RagdollSim(def, blocks(mc.level), world);
		int[] bodyOf = new int[n];
		java.util.Arrays.fill(bodyOf, -1);
		RagdollSim.Body hit = null;
		for (int b = 0; b < sim.bodies.length; b++) {
			bodyOf[sim.bodies[b].bone] = b;
			String name = skel.names[sim.bodies[b].bone];
			if (p.head() ? name.equals("head_0") : name.equals("spine_2")) {
				hit = sim.bodies[b];
			}
		}
		Vec3 v = e.getDeltaMovement().scale(20);
		sim.launch(new Vector3d(v.x, Math.max(-5, v.y), v.z), hit, new Vector3d(p.dx(), p.dy(), p.dz()).mul(p.speed()), p.blast());
		AgentAnimator anim = new AgentAnimator(data);
		anim.bodyYaw = 0f;
		RAGDOLLS.removeIf(r -> r.owner.equals(player.getUUID()));
		RAGDOLLS.add(new Ragdoll(player.getUUID(), e.getId(), snap.agent(), sim, local, world, pos, anim, bodyOf, player));
		while (RAGDOLLS.size() > MAX) {
			RAGDOLLS.remove(0);
		}
	}

	/** Minecraft's block collision shapes. */
	private static RagdollSim.World blocks(net.minecraft.world.level.Level level) {
		net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
		return (x0, y0, z0, x1, y1, z1, out) -> {
			for (int bx = (int) Math.floor(x0); bx <= (int) Math.floor(x1); bx++) {
				for (int by = (int) Math.floor(y0); by <= (int) Math.floor(y1); by++) {
					for (int bz = (int) Math.floor(z0); bz <= (int) Math.floor(z1); bz++) {
						cursor.set(bx, by, bz);
						var st = level.getBlockState(cursor);
						if (st.isAir()) {
							continue;
						}
						var shape = st.getCollisionShape(level, cursor);
						if (shape.isEmpty()) {
							continue;
						}
						for (var box : shape.toAabbs()) {
							out.accept(new double[] {box.minX + bx, box.minY + by, box.minZ + bz, box.maxX + bx, box.maxY + by, box.maxZ + bz});
						}
					}
				}
			}
		};
	}

	/** Translation to {@code pos} after a turn of -bodyYaw about Y (how AgentRenderer draws the agent). */
	private static float[] placement(Vec3 pos, float bodyYaw) {
		double a = Math.toRadians(-bodyYaw);
		float c = (float) Math.cos(a), s = (float) Math.sin(a);
		return new float[] {c, 0, s, (float) pos.x, 0, 1, 0, (float) pos.y, -s, 0, c, (float) pos.z};
	}

	/** Every client tick: simulate; remove the bodies of players who are back alive. */

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			RAGDOLLS.clear();
			return;
		}
		for (Iterator<Ragdoll> it = RAGDOLLS.iterator(); it.hasNext(); ) {
			Ragdoll r = it.next();
			Player owner = mc.level.getPlayerByUUID(r.owner);
			// respawned: alive, as a new player or seen dead first (the ragdoll can come a tick before this client hears
			// their health is gone; a dead player seen again from afar is a new one, still dead)
			r.seenDead |= owner == r.died && owner.isDeadOrDying();
			if (owner != null && owner.isAlive() && !owner.isDeadOrDying() && (owner != r.died || r.seenDead)) {
				it.remove();
				continue;
			}
			if (owner == null && mc.getConnection() != null && mc.getConnection().getPlayerInfo(r.owner) == null) {
				it.remove(); // left the server (or a removed test bot)
				continue;
			}
			r.sim.tick();
		}
	}

	public static void submit(LevelRenderContext ctx) {
		if (RAGDOLLS.isEmpty() || AgentClient.data() == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		AgentData data = AgentClient.data();
		AgentSkeleton skel = data.skeleton();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		for (Ragdoll r : RAGDOLLS) {
			AgentModel model = data.model(r.agent);
			if (model == null) {
				continue;
			}
			int n = skel.bones();
			float[] world = new float[n * 12];
			for (int i = 0; i < n; i++) {
				int b = r.bodyOf[i];
				if (b >= 0) {
					RagdollSim.Body body = r.sim.bodies[b];
					Quaterniond q = new Quaterniond(body.qTick).slerp(body.q, partial);
					Vector3d x = new Vector3d(body.xTick).lerp(body.x, partial);
					Vector3d o = body.boneOrigin(x, q);
					org.joml.Matrix3d m = new org.joml.Matrix3d().set(q);
					world[i * 12] = (float) m.m00;
					world[i * 12 + 1] = (float) m.m10;
					world[i * 12 + 2] = (float) m.m20;
					world[i * 12 + 3] = (float) o.x;
					world[i * 12 + 4] = (float) m.m01;
					world[i * 12 + 5] = (float) m.m11;
					world[i * 12 + 6] = (float) m.m21;
					world[i * 12 + 7] = (float) o.y;
					world[i * 12 + 8] = (float) m.m02;
					world[i * 12 + 9] = (float) m.m12;
					world[i * 12 + 10] = (float) m.m22;
					world[i * 12 + 11] = (float) o.z;
				} else if (skel.parents[i] >= 0) {
					Mat.mul(world, skel.parents[i] * 12, r.local, i * 12, world, i * 12);
				} else {
					System.arraycopy(r.deathWorld, i * 12, world, i * 12, 12);
				}
			}
			// to the ragdoll's own origin (drawn there, unturned)
			for (int i = 0; i < n; i++) {
				r.anim.model[i * 12 + 3] = world[i * 12 + 3] - (float) r.origin.x;
				r.anim.model[i * 12 + 7] = world[i * 12 + 7] - (float) r.origin.y;
				r.anim.model[i * 12 + 11] = world[i * 12 + 11] - (float) r.origin.z;
				for (int k : new int[] {0, 1, 2, 4, 5, 6, 8, 9, 10}) {
					r.anim.model[i * 12 + k] = world[i * 12 + k];
				}
			}
			int pelvis = skel.index("pelvis");
			BlockPos light = BlockPos.containing(pelvis >= 0 ? world[pelvis * 12 + 3] : r.origin.x, (pelvis >= 0 ? world[pelvis * 12 + 7] : r.origin.y) + 0.3,
					pelvis >= 0 ? world[pelvis * 12 + 11] : r.origin.z);
			PoseStack ps = ctx.poseStack();
			ps.pushPose();
			ps.translate(r.origin.x - cam.x, r.origin.y - cam.y, r.origin.z - cam.z);
			r.renderer.submit(data, model, r.anim, null, ps, ctx.submitNodeCollector(), net.minecraft.util.LightCoordsUtil.getLightCoords(mc.level, light),
					OverlayTexture.NO_OVERLAY, false);
			ps.popPose();
		}
	}
}
