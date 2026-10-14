package dev.countercraft.client.agent;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.countercraft.client.GunClient;
import dev.countercraft.client.viewmodel.FastMesh;
import dev.countercraft.client.viewmodel.MeshLod;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import java.util.Optional;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Draws an agent: its mesh skinned on the CPU from the animator's model-space bones (model bones the animation doesn't
 * drive ride their parent at their rest offset), and the held weapon's mesh (from its converted viewmodel) with the
 * gun model's root bone on the agent's wpn bone. For first-person legs the upper body is folded away.
 */
public final class AgentRenderer {
	/** First-person body: only the legs and hips show; everything from spine_2 up (chest, arms, head) and the gun are
	 * hidden. Hidden = triangles mostly skinned to these bones (and everything under them) are dropped; nothing is
	 * squashed, so the cut has no pinched seams. */
	private static final String[] LEGS_HIDE = {"spine_2", "wpnPivot"};
	/** The world's up in the pose's output space (Agent Shaders' lighting), and "no shading maps" (the lit defaults). */
	private static final float[] UP = {0f, 1f, 0f};
	private static final Identifier[] NO_MAPS = new Identifier[2];
	/** Per mesh part, the indices left in the first-person body. */
	private final java.util.Map<AgentModel.Part, int[]> legsIndices = new java.util.IdentityHashMap<>();

	private float[] world = new float[0];
	private float[] skin = new float[0];
	/**
	 * Skinned positions and normals per mesh part, kept between frames: the deferred draw reads them later in the same
	 * frame, and the next frame overwrites them (a renderer belongs to one player, whose pose is the same however
	 * often it is drawn in a frame).
	 */
	private final java.util.Map<AgentModel.Part, float[][]> skinned = new java.util.IdentityHashMap<>();
	/** Where the gun's muzzle was drawn last (camera-relative, blocks), or null: third-person tracers start there. */
	public org.joml.Vector3f lastMuzzle;
	private static final java.util.Map<ViewmodelAsset, float[]> MUZZLES = new java.util.IdentityHashMap<>();
	private boolean[] hiddenAnim;

	public void submit(AgentData data, AgentModel model, AgentAnimator anim, String gunId, PoseStack ps, SubmitNodeCollector collector, int light,
			int overlay, boolean legsOnly) {
		if (model == null) {
			return;
		}
		AgentSkeleton skel = data.skeleton();
		int n = model.names.length;
		if (world.length < n * 12) {
			world = new float[n * 12];
			skin = new float[n * 12];
		}
		if (hiddenAnim == null) {
			hiddenAnim = new boolean[skel.bones()];
			for (String h : LEGS_HIDE) {
				int b = skel.index(h);
				if (b >= 0) {
					hiddenAnim[b] = true;
				}
			}
			for (int i = 0; i < skel.bones(); i++) {
				int p = skel.parents[i];
				if (p >= 0 && hiddenAnim[p]) {
					hiddenAnim[i] = true;
				}
			}
		}
		boolean[] hidden = legsOnly ? new boolean[n] : null;
		float[] tmp = new float[12];
		for (int k = 0; k < n; k++) {
			int a = model.anim[k];
			int p = model.parents[k];
			if (a >= 0) {
				System.arraycopy(anim.model, a * 12, world, k * 12, 12);
			} else if (p >= 0) {
				Mat.mul(world, p * 12, model.invRest, p * 12, tmp, 0);
				Mat.mul(tmp, 0, model.rest, k * 12, world, k * 12);
				twist(model, k, p);
			} else {
				System.arraycopy(model.rest, k * 12, world, k * 12, 12);
			}
			Mat.mul(world, k * 12, model.invRest, k * 12, skin, k * 12);
			if (hidden != null) {
				hidden[k] = a >= 0 ? hiddenAnim[a] : p >= 0 && hidden[p];
			}
		}
		ps.pushPose();
		ps.rotate(Axis.YP.rotationDegrees(-anim.bodyYaw));
		// first person: like CS2, only the agent's legs mesh (the pants) when it has one; otherwise the body cut at the waist
		boolean legsMesh = false;
		if (legsOnly) {
			for (AgentModel.Part part : model.parts) {
				legsMesh |= isLegs(part);
			}
		}
		// on the GPU when it can: the mesh is already there, only this frame's bones go up; lit like the viewmodel with
		// Agent Shaders on (the pose's output space is world-aligned, in the level and in the menus' perspective previews)
		boolean shaded = dev.countercraft.client.options.CcOptions.agentShaders();
		dev.countercraft.client.gpu.GpuAgents.Block block = dev.countercraft.client.gpu.GpuAgents.active()
				? dev.countercraft.client.gpu.GpuAgents.block(ps.last().pose(), skin, n, light, overlay, legsOnly, null, shaded ? UP : null) : null;
		for (AgentModel.Part part : model.parts) {
			if (legsMesh && !isLegs(part) || glove != null && isGloves(part)) {
				continue; // (wearing equipped gloves: the agent's own gloves aren't drawn, the glove model is, below)
			}
			int[] idx = hidden == null ? part.indices() : legsIndices.computeIfAbsent(part, k -> visibleIndices(k, hidden));
			if (idx.length == 0) {
				continue;
			}
			Identifier tex = retexture != null ? retexture : part.texture();
			if (block != null) {
				Identifier[] m = shaded ? dev.countercraft.client.viewmodel.ViewmodelRenderer.mapsOf(part.texture()) : NO_MAPS;
				dev.countercraft.client.gpu.GpuAgents.draw(part.pos(), part.nrm(), part.uv(), part.joints(), part.weights(), part.vertexCount(), idx,
						tex, block, m[0], m[1]);
				if (outline == 0) {
					continue;
				}
			}
			float[][] out = skinned.computeIfAbsent(part, k -> new float[][] {new float[k.vertexCount() * 3], new float[k.vertexCount() * 3]});
			skinPart(part.vertexCount(), part.pos(), part.nrm(), part.joints(), part.weights(), skin, out[0], out[1]);
			float[] pos = out[0];
			float[] nrm = out[1];
			// the cut-open first-person body shows its inside through the cut: draw both sides there
			boolean cull = hidden == null;
			if (block == null) {
				collector.submitCustomGeometry(ps, RenderTypes.entityCutout(tex),
						(p, vc) -> FastMesh.emit(idx, part.uv(), pos, nrm, part.vertexCount(), null, p, vc, light, overlay, cull));
			}
			outline(ps, collector, tex, idx, part.uv(), pos, nrm, part.vertexCount(), null, cull);
		}
		if (!legsOnly && gunId != null) {
			submitGun(data, anim, gunId, ps, collector, light, overlay);
		}
		ps.popPose();
		if (glove != null && !legsOnly) {
			// the equipped gloves: CS2's third-person glove model on the same animated skeleton, in its finish
			AgentModel gm = data.model("gloves_" + glove.type());
			if (gm != null) {
				dev.countercraft.client.skins.Skins.Glove g = glove;
				glove = null;
				retexture = g.texture();
				try {
					submit(data, gm, anim, null, ps, collector, light, overlay, false);
				} finally {
					retexture = null;
					glove = g;
				}
			}
		}
	}

	/** Equipped gloves (null: the agent's own), set before each submit like {@link #finish}. */
	public dev.countercraft.client.skins.Skins.Glove glove;
	/**
	 * Minecraft's glow colour for this player (ARGB; 0: none), set before each submit: the Glowing effect, a spectral
	 * arrow, the X-Ray. The agent's own mesh (body, gloves, gun) goes into Minecraft's outline pass in it, so the glow
	 * has the agent's shape.
	 */
	public int outline;

	/** The mesh into the outline pass in {@link #outline}, if there is one. */
	private void outline(PoseStack ps, SubmitNodeCollector collector, Identifier tex, int[] idx, float[] uv, float[] pos, float[] nrm, int vertexCount,
			float[] pre, boolean cull) {
		int color = outline;
		if (color == 0) {
			return;
		}
		collector.submitCustomGeometry(ps, RenderTypes.outline(tex), (p, vc) -> {
			FastMesh.color = color;
			try {
				FastMesh.emit(idx, uv, pos, nrm, vertexCount, pre, p, vc, 0xF000F0, 0, cull);
			} finally {
				FastMesh.color = 0xFFFFFFFF;
			}
		});
	}
	/** Every part drawn with this texture instead of its own (the glove model in a finish), or null. */
	private Identifier retexture;

	/** The agent's own gloves (CS2 agents' glove materials: glove_fullfinger, glove_hardknuckle...). */
	private static boolean isGloves(AgentModel.Part part) {
		return part.texture().getPath().contains("glove_");
	}

	/** The agent's legs mesh (CS2 agents have a separate legs material: ..._legs_...). */
	private static boolean isLegs(AgentModel.Part part) {
		return part.texture().getPath().contains("legs");
	}

	/** The part's triangles whose vertices are all mostly skinned to visible bones. */
	private static int[] visibleIndices(AgentModel.Part part, boolean[] hidden) {
		int nv = part.vertexCount();
		boolean[] gone = new boolean[nv];
		byte[] jn = part.joints();
		byte[] wt = part.weights();
		for (int v = 0; v < nv; v++) {
			int h = 0;
			int all = 0;
			for (int k = 0; k < 4; k++) {
				int w = Byte.toUnsignedInt(wt[v * 4 + k]);
				int j = Byte.toUnsignedInt(jn[v * 4 + k]);
				all += w;
				if (j < hidden.length && hidden[j]) {
					h += w;
				}
			}
			gone[v] = h * 2 > all;
		}
		int[] idx = part.indices();
		int[] out = new int[idx.length];
		int n = 0;
		for (int t = 0; t + 2 < idx.length; t += 3) {
			if (!gone[idx[t]] && !gone[idx[t + 1]] && !gone[idx[t + 2]]) {
				out[n++] = idx[t];
				out[n++] = idx[t + 1];
				out[n++] = idx[t + 2];
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/**
	 * Forearm twist helpers (arm_lower_*_TWIST, *_TWIST1, between elbow and wrist) take their share of the hand's roll
	 * about the forearm, by how far along the forearm they sit, so the wrist doesn't pinch.
	 */
	private void twist(AgentModel model, int k, int p) {
		String name = model.names[k];
		if (!name.startsWith("arm_lower_") || !name.contains("_TWIST")) {
			return;
		}
		int hand = model.index("hand_" + name.charAt(10));
		if (hand < 0 || model.parents[hand] != p) {
			return;
		}
		float[] t = new float[12];
		float[] rel = new float[12];
		float[] restRel = new float[12];
		float[] q = new float[4];
		// the hand relative to the forearm now, against the same at rest
		Mat.invertRigid(world, p * 12, t, 0);
		Mat.mul(t, 0, world, hand * 12, rel, 0);
		Mat.mul(model.invRest, p * 12, model.rest, hand * 12, restRel, 0);
		Mat.invertRigid(restRel, 0, t, 0);
		Mat.mul(rel, 0, t, 0, restRel, 0);
		Mat.toQuat(restRel, 0, q, 0);
		float roll = 2f * (float) Math.atan2(q[0], q[3]);
		if (roll > Math.PI) {
			roll -= (float) (2 * Math.PI);
		} else if (roll < -Math.PI) {
			roll += (float) (2 * Math.PI);
		}
		// share by distance along the forearm
		Mat.mul(model.invRest, p * 12, model.rest, k * 12, t, 0);
		float along = (float) Math.sqrt(t[3] * t[3] + t[7] * t[7] + t[11] * t[11]);
		Mat.mul(model.invRest, p * 12, model.rest, hand * 12, rel, 0);
		float len = (float) Math.sqrt(rel[3] * rel[3] + rel[7] * rel[7] + rel[11] * rel[11]);
		float a = len > 1e-4f ? Math.min(1f, along / len) * roll : 0f;
		float c = (float) Math.cos(a), sn = (float) Math.sin(a);
		float[] rx = {1, 0, 0, 0, 0, c, -sn, 0, 0, sn, c, 0};
		Mat.mul(rx, 0, t, 0, rel, 0); // Rx(roll share) * local rest
		Mat.mul(world, p * 12, rel, 0, world, k * 12);
	}

	/**
	 * A Minecraft item in the agent's right hand: placed in the frame vanilla puts held items in (an arm frame with Y
	 * along the arm toward the hand and -Z forward), here built from the forearm and the grip (wpn) direction, so the
	 * item's own third-person display transform applies as usual. A spear is held along the aim, gripped on its shaft.
	 * With the player's render state ({@code state}, null for the menu previews), Minecraft's own item motion plays on
	 * top, as its ItemInHandLayer does: the spear's jab and its charge.
	 */
	public void submitItem(AgentData data, AgentAnimator anim, net.minecraft.client.renderer.item.ItemStackRenderState item,
			net.minecraft.world.item.ItemStack stack, net.minecraft.client.renderer.entity.state.ArmedEntityRenderState state, PoseStack ps,
			SubmitNodeCollector collector, int light, int overlay, int outline, String knife) {
		AgentSkeleton skel = data.skeleton();
		int lower = skel.index("arm_lower_R");
		int hand = skel.index("hand_R");
		int wpn = skel.index("wpn");
		if (lower < 0 || hand < 0 || wpn < 0) {
			return;
		}
		float[] m = anim.model;
		float hx = m[hand * 12 + 3], hy = m[hand * 12 + 7], hz = m[hand * 12 + 11];
		// held upright and steady: a vanilla arm frame turned with the upper body's aim (not the forearm's twist, which
		// swung the item about), its position on the hand. Vanilla's held item display leans a hand-held item about 55
		// degrees up from an arm hanging straight down; raising the arm frame forward by the rest stands it up straight
		double yaw = Math.toRadians(anim.aimYaw());
		float fx = (float) Math.sin(yaw), fy = 0f, fz = (float) Math.cos(yaw);
		float lift = (float) Math.toRadians(UPRIGHT_LIFT);
		float c = (float) Math.cos(lift), sn = (float) Math.sin(lift);
		float ax = fx * sn, ay = -c, az = fz * sn;
		// arm frame: Y = along the arm, Z = backward (forward made perpendicular to the arm, reversed), X = Y x Z
		float d = fx * ax + fy * ay + fz * az;
		float zx = -(fx - d * ax), zy = -(fy - d * ay), zz = -(fz - d * az);
		float zl = (float) Math.sqrt(zx * zx + zy * zy + zz * zz);
		zx /= zl;
		zy /= zl;
		zz /= zl;
		float xx = ay * zz - az * zy, xy = az * zx - ax * zz, xz = ax * zy - ay * zx;
		org.joml.Matrix4f frame = new org.joml.Matrix4f(
				xx, xy, xz, 0f,
				ax, ay, az, 0f,
				zx, zy, zz, 0f,
				hx - ax * ARM_LENGTH, hy - ay * ARM_LENGTH, hz - az * ARM_LENGTH, 1f);
		// held like the default knife: solved so the item's handle sits in the fist, its blade along the knife's blade and
		// its flat side the knife's, through CS2's knife animations (the knife rides the agent's weapon bone)
		boolean spear = stack.is(net.minecraft.tags.ItemTags.SPEARS);
		float[] knifeAxes = spear ? spearAxes(anim) : knife == null ? null : knifeBlade(data, anim, knife);
		org.joml.Matrix4f held = knifeAxes == null ? null : heldLike(item, knifeAxes, spear ? SPEAR_GRIP : ITEM_GRIP, hx, hy, hz);
		if (held != null) {
			frame = held;
		}
		// smaller than vanilla's held items, to suit the agent's real-world proportions; about the hand, so the grip stays
		frame = new org.joml.Matrix4f().translation(hx, hy, hz).scale(HELD_SCALE).translate(-hx, -hy, -hz).mul(frame);
		ps.pushPose();
		ps.rotate(Axis.YP.rotationDegrees(-anim.bodyYaw));
		ps.mulPose(frame);
		// vanilla ItemInHandLayer for the right arm
		ps.rotate(Axis.XP.rotationDegrees(-90f));
		ps.rotate(Axis.YP.rotationDegrees(180f));
		ps.translate(1f / 16f, 0.125f, -0.625f);
		if (state != null) {
			var swing = state.currentSwing;
			if (swing != null && swing.hand().asArm(state.mainArm) == net.minecraft.world.entity.HumanoidArm.RIGHT
					&& swing.animation().type() == net.minecraft.world.item.SwingAnimationType.STAB) {
				net.minecraft.client.model.effects.SpearAnimations.thirdPersonAttackItem(state, ps);
			}
			float ticks = state.ticksUsingItem(net.minecraft.world.entity.HumanoidArm.RIGHT);
			if (ticks != 0f) {
				state.rightArmPose.animateUseItem(state, ps, ticks, net.minecraft.world.entity.HumanoidArm.RIGHT, state.rightHandItemStack);
			}
		}
		item.submit(ps, collector, light, overlay, outline);
		ps.popPose();
	}

	/** Where a spear is gripped in its sprite: on the shaft, a third of the way from the butt (the head runs to the top right). */
	private static final org.joml.Vector3f SPEAR_GRIP = new org.joml.Vector3f(5.5f / 16f, 5.5f / 16f, 0.5f);

	/** A spear's axes for {@link #heldLike}: its point along the aim, its flat side facing sideways (agent model space). */
	private static float[] spearAxes(AgentAnimator anim) {
		double yaw = Math.toRadians(anim.aimYaw());
		double pitch = Math.toRadians(anim.aimPitch());
		return new float[] {(float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch)),
				(float) Math.cos(yaw), 0f, (float) -Math.sin(yaw)};
	}

	/**
	 * Where a hand-held item's handle and blade are in its own model (the 0..1 cube of a flat item sprite): Minecraft's
	 * tool and weapon sprites run from the handle at the bottom left to the tip at the top right, the flat side facing +Z.
	 */
	private static final org.joml.Vector3f ITEM_GRIP = new org.joml.Vector3f(3.5f / 16f, 3.5f / 16f, 0.5f);
	private static final org.joml.Vector3f ITEM_BLADE = new org.joml.Vector3f(1f, 1f, 0f).normalize();
	private static final org.joml.Vector3f ITEM_FLAT = new org.joml.Vector3f(0f, 0f, 1f);

	/**
	 * The arm frame (as {@link #submitItem} uses it) that puts {@code item}'s grip point ({@code gripAt}, in its model) in
	 * the hand at (hx, hy, hz) with its blade along {@code knife}'s blade and its flat side the knife's: everything after
	 * the frame (vanilla's hand offset, the item model's own display transform) is fixed and known, so the frame is solved
	 * for, not guessed.
	 */
	private static org.joml.Matrix4f heldLike(net.minecraft.client.renderer.item.ItemStackRenderState item, float[] knife, org.joml.Vector3f gripAt,
			float hx, float hy, float hz) {
		var layers = (dev.countercraft.client.mixin.ItemStackRenderStateAccessor) item;
		if (layers.countercraft$activeLayerCount() <= 0) {
			return null;
		}
		var t = ((dev.countercraft.client.mixin.ItemLayerAccessor) layers.countercraft$layers()[0]).countercraft$itemTransform();
		// after the frame: vanilla ItemInHandLayer, then the item's display transform and its -0.5 centring
		org.joml.Matrix4f after = new org.joml.Matrix4f()
				.rotateX((float) Math.toRadians(-90f))
				.rotateY((float) Math.toRadians(180f))
				.translate(1f / 16f, 0.125f, -0.625f);
		if (t != null) {
			after.translate(t.translation().x(), t.translation().y(), t.translation().z())
					.rotate(new org.joml.Quaternionf().rotationXYZ((float) Math.toRadians(t.rotation().x()), (float) Math.toRadians(t.rotation().y()),
							(float) Math.toRadians(t.rotation().z())))
					.scale(t.scale().x(), t.scale().y(), t.scale().z());
		}
		after.translate(-0.5f, -0.5f, -0.5f);
		// its rotation part (the scale divided out)
		org.joml.Matrix3f rc = new org.joml.Matrix3f();
		after.get3x3(rc);
		org.joml.Vector3f c0 = rc.getColumn(0, new org.joml.Vector3f()).normalize();
		org.joml.Vector3f c1 = rc.getColumn(1, new org.joml.Vector3f()).normalize();
		org.joml.Vector3f c2 = rc.getColumn(2, new org.joml.Vector3f()).normalize();
		rc.setColumn(0, c0).setColumn(1, c1).setColumn(2, c2);
		// the turn taking the item's blade / flat side / their cross onto the knife's
		org.joml.Vector3f k = new org.joml.Vector3f(knife[0], knife[1], knife[2]);
		org.joml.Vector3f n = new org.joml.Vector3f(knife[3], knife[4], knife[5]);
		org.joml.Matrix3f to = new org.joml.Matrix3f(k, n, new org.joml.Vector3f(k).cross(n));
		org.joml.Matrix3f from = new org.joml.Matrix3f(ITEM_BLADE, ITEM_FLAT, new org.joml.Vector3f(ITEM_BLADE).cross(ITEM_FLAT));
		org.joml.Matrix3f lin = to.mul(from.transpose()).mul(rc.transpose());
		org.joml.Matrix4f frame = new org.joml.Matrix4f().set(lin);
		// and where the handle lands: on the hand
		org.joml.Vector3f grip = frame.transformPosition(after.transformPosition(new org.joml.Vector3f(gripAt)));
		frame.setTranslation(hx - grip.x, hy - grip.y, hz - grip.z);
		return frame;
	}

	/** Minecraft items in an agent's hand, against vanilla's size. */
	private static final float HELD_SCALE = 0.7f;

	private static final java.util.Map<String, float[]> KNIFE_AXIS = new java.util.HashMap<>();

	/**
	 * The default knife's blade direction (grip to tip) and its flat side's normal, agent model space, as the knife is held
	 * this frame: the knife mesh's farthest point from its root bone gives the blade, its thinnest direction across the
	 * blade the normal; placed like the drawn knife ({@link #submitGun}). Null if the knife's data isn't loaded.
	 */
	private static float[] knifeBlade(AgentData data, AgentAnimator anim, String knife) {
		AgentData.GunSkeleton gun = data.gun(knife);
		int wpn = data.skeleton().index("wpn");
		if (gun == null || wpn < 0) {
			return null;
		}
		int root = Math.max(0, gun.index("weapon"));
		float[] local = KNIFE_AXIS.computeIfAbsent(knife, k -> {
			Optional<ViewmodelAsset> va = GunClient.viewmodel(k);
			if (va.isEmpty()) {
				return new float[0];
			}
			Integer first = va.get().bones.get(gun.bones()[0]);
			float ox = gun.rest()[root * 12 + 3], oy = gun.rest()[root * 12 + 7], oz = gun.rest()[root * 12 + 11];
			float best = -1f;
			float[] tip = null;
			for (ViewmodelAsset.Part part : va.get().parts) {
				if (ViewmodelAsset.isArms(part) || first != null && part.vertexCount() > 0 && Byte.toUnsignedInt(part.joints()[0]) < first) {
					continue;
				}
				float[] p = part.pos();
				for (int v = 0; v < part.vertexCount(); v++) {
					float dx = p[v * 3] - ox, dy = p[v * 3 + 1] - oy, dz = p[v * 3 + 2] - oz;
					float d2 = dx * dx + dy * dy + dz * dz;
					if (d2 > best) {
						best = d2;
						tip = new float[] {dx, dy, dz};
					}
				}
			}
			if (tip == null) {
				return new float[0];
			}
			float l = (float) Math.sqrt(best);
			float bx = tip[0] / l, by = tip[1] / l, bz = tip[2] / l;
			// the flat side's normal: across the blade, the direction the knife is thinnest (the smaller axis of the
			// spread of its points in the plane square to the blade)
			float[] u = Math.abs(by) < 0.9f ? new float[] {0, 1, 0} : new float[] {1, 0, 0};
			float d0 = u[0] * bx + u[1] * by + u[2] * bz;
			float e1x = u[0] - d0 * bx, e1y = u[1] - d0 * by, e1z = u[2] - d0 * bz;
			float e1l = (float) Math.sqrt(e1x * e1x + e1y * e1y + e1z * e1z);
			e1x /= e1l;
			e1y /= e1l;
			e1z /= e1l;
			float e2x = by * e1z - bz * e1y, e2y = bz * e1x - bx * e1z, e2z = bx * e1y - by * e1x;
			double s11 = 0, s22 = 0, s12 = 0;
			for (ViewmodelAsset.Part part : va.get().parts) {
				if (ViewmodelAsset.isArms(part) || first != null && part.vertexCount() > 0 && Byte.toUnsignedInt(part.joints()[0]) < first) {
					continue;
				}
				float[] p = part.pos();
				for (int v = 0; v < part.vertexCount(); v++) {
					float dx = p[v * 3] - ox, dy = p[v * 3 + 1] - oy, dz = p[v * 3 + 2] - oz;
					double a1 = dx * e1x + dy * e1y + dz * e1z, a2 = dx * e2x + dy * e2y + dz * e2z;
					s11 += a1 * a1;
					s22 += a2 * a2;
					s12 += a1 * a2;
				}
			}
			// smallest eigenvector of [[s11 s12] [s12 s22]]
			double tr = s11 + s22, det = s11 * s22 - s12 * s12;
			double lmin = tr / 2 - Math.sqrt(Math.max(0, tr * tr / 4 - det));
			double vx = s12, vy = lmin - s11;
			if (Math.abs(vx) + Math.abs(vy) < 1e-12) {
				vx = s11 <= s22 ? 1 : 0;
				vy = s11 <= s22 ? 0 : 1;
			}
			double vl = Math.hypot(vx, vy);
			float nx = (float) ((vx * e1x + vy * e2x) / vl), ny = (float) ((vx * e1y + vy * e2y) / vl), nz = (float) ((vx * e1z + vy * e2z) / vl);
			return new float[] {bx, by, bz, nx, ny, nz};
		});
		if (local.length == 0) {
			return null;
		}
		float[] inv = new float[12];
		Mat.invertRigid(gun.rest(), root * 12, inv, 0);
		float[] place = new float[12];
		Mat.mul(anim.model, wpn * 12, inv, 0, place, 0);
		float bx = place[0] * local[0] + place[1] * local[1] + place[2] * local[2];
		float by = place[4] * local[0] + place[5] * local[1] + place[6] * local[2];
		float bz = place[8] * local[0] + place[9] * local[1] + place[10] * local[2];
		float bl = (float) Math.sqrt(bx * bx + by * by + bz * bz);
		float nx = place[0] * local[3] + place[1] * local[4] + place[2] * local[5];
		float ny = place[4] * local[3] + place[5] * local[4] + place[6] * local[5];
		float nz = place[8] * local[3] + place[9] * local[4] + place[10] * local[5];
		if (bl < 1e-6f) {
			return null;
		}
		bx /= bl;
		by /= bl;
		bz /= bl;
		// keep the normal square to the blade
		float dn = nx * bx + ny * by + nz * bz;
		nx -= dn * bx;
		ny -= dn * by;
		nz -= dn * bz;
		float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return nl < 1e-6f ? null : new float[] {bx, by, bz, nx / nl, ny / nl, nz / nl};
	}

	/** How far (degrees) the item's arm frame is raised forward from hanging straight down: hand-held items stand upright. */
	private static final float UPRIGHT_LIFT = 35f;

	/** Vanilla's arm frame origin is the shoulder; its item offset reaches 10/16 down the arm to the hand. */
	private static final float ARM_LENGTH = 0.625f;

	/**
	 * The muzzle (the viewmodel's att_muzzle_flash) in the gun mesh's own space, from the idle clip's first frame: the
	 * attachment's view-space position taken back through the gun root bone's skinning matrix. Then placed like the
	 * mesh. Null if the viewmodel has no muzzle attachment.
	 */
	private static float[] muzzlePoint(ViewmodelAsset asset, int rootBone, float[] place) {
		float[] local = MUZZLES.computeIfAbsent(asset, k -> {
			Integer att = asset.bones.get("att_muzzle_flash");
			ViewmodelAsset.ClipData clip = asset.clips.get("idle");
			if (clip == null && !asset.clips.isEmpty()) {
				clip = asset.clips.values().iterator().next();
			}
			if (att == null || clip == null || rootBone < 0) {
				return new float[0];
			}
			float[] inv = new float[12];
			Mat.invertRigid(clip.mats(), rootBone * 12, inv, 0);
			float x = clip.mats()[att * 12 + 3], y = clip.mats()[att * 12 + 7], z = clip.mats()[att * 12 + 11];
			return new float[] {inv[0] * x + inv[1] * y + inv[2] * z + inv[3], inv[4] * x + inv[5] * y + inv[6] * z + inv[7],
					inv[8] * x + inv[9] * y + inv[10] * z + inv[11]};
		});
		if (local.length == 0) {
			return null;
		}
		return new float[] {place[0] * local[0] + place[1] * local[1] + place[2] * local[2] + place[3],
				place[4] * local[0] + place[5] * local[1] + place[6] * local[2] + place[7],
				place[8] * local[0] + place[9] * local[1] + place[10] * local[2] + place[11]};
	}

	/** The held weapon's finish (set by AgentClient before {@link #submit}). */
	public dev.countercraft.client.skins.Skins.Skin finish;

	/**
	 * A gun with a piece held in the left hand (the Dual Berettas: bone weapon_hand_l), as per-bone placements for the
	 * viewmodel mesh: the left piece where its grip marker (ag1_hand_l) meets the agent's left hand, the rest on the right
	 * as usual. Null for one-handed guns (or when a bone is missing).
	 */
	private static float[] leftHanded(AgentData data, AgentAnimator anim, AgentData.GunSkeleton gun, ViewmodelAsset asset, float[] place) {
		int leftRoot = gun.index("weapon_hand_l");
		int marker = gun.index("ag1_hand_l");
		int hand = data.skeleton().index("hand_L");
		if (leftRoot < 0 || marker < 0 || hand < 0 || asset.boneCount > 128) {
			return null;
		}
		float[] inv = new float[12];
		Mat.invertRigid(gun.rest(), marker * 12, inv, 0);
		float[] left = new float[12];
		Mat.mul(anim.model, hand * 12, inv, 0, left, 0);
		float[] bones = new float[asset.boneCount * 12];
		for (java.util.Map.Entry<String, Integer> e : asset.bones.entrySet()) {
			int g = gun.index(e.getKey());
			if (g >= 0 && e.getValue() < asset.boneCount) {
				boolean onLeft = false;
				for (int b = g; b >= 0; b = gun.parents()[b]) {
					onLeft |= b == leftRoot;
				}
				System.arraycopy(onLeft ? left : place, 0, bones, e.getValue() * 12, 12);
			}
		}
		return bones;
	}

	private void submitGun(AgentData data, AgentAnimator anim, String gunId, PoseStack ps, SubmitNodeCollector collector, int light, int overlay) {
		Optional<ViewmodelAsset> va = GunClient.viewmodelIfReady(gunId); // null: loading in the background
		AgentData.GunSkeleton gun = data.gun(gunId);
		int wpn = data.skeleton().index("wpn");
		if (va == null || va.isEmpty() || gun == null || wpn < 0) {
			return;
		}
		ViewmodelAsset asset = va.get();
		Integer first = asset.bones.get(gun.bones()[0]);
		int offset = first == null ? -1 : first;
		if (offset < 0) {
			return;
		}
		int root = Math.max(0, gun.index("weapon"));
		float[] inv = new float[12];
		Mat.invertRigid(gun.rest(), root * 12, inv, 0);
		float[] place = new float[12];
		Mat.mul(anim.model, wpn * 12, inv, 0, place, 0);
		// every gun bone sits at the same placement (the gun is rigid here): no skinning, the mesh goes out as it is
		float[] muzzle = muzzlePoint(asset, offset, place);
		if (muzzle != null) {
			lastMuzzle = ps.last().pose().transformPosition(muzzle[0], muzzle[1], muzzle[2], new org.joml.Vector3f());
		}
		// on the GPU: the gun is rigid, its one "bone" is its placement on the hand; a gun with a piece for the left hand
		// (the Dual Berettas' left pistol) goes up skinned, that piece on the left hand
		if (dev.countercraft.client.gpu.GpuAgents.active()) {
			boolean shaded = dev.countercraft.client.options.CcOptions.agentShaders();
			float[] twoHanded = leftHanded(data, anim, gun, asset, place);
			dev.countercraft.client.gpu.GpuAgents.Block block = twoHanded == null
					? dev.countercraft.client.gpu.GpuAgents.block(ps.last().pose(), place, 1, light, overlay, false, null, shaded ? UP : null)
					: dev.countercraft.client.gpu.GpuAgents.block(ps.last().pose(), twoHanded, asset.boneCount, light, overlay, false, null, shaded ? UP : null);
			if (block != null) {
				for (ViewmodelAsset.Part part : dev.countercraft.client.skins.Skins.gunParts(asset, gunId, finish)) {
					if (part.vertexCount() > 0 && Byte.toUnsignedInt(part.joints()[0]) >= offset) {
						Identifier[] m = shaded ? dev.countercraft.client.viewmodel.ViewmodelRenderer.maps(asset, part) : NO_MAPS;
						dev.countercraft.client.gpu.GpuAgents.draw(part.pos(), part.nrm(), part.uv(), twoHanded == null ? null : part.joints(),
								twoHanded == null ? null : part.weights(), part.vertexCount(), part.indices(), part.texture(), block, m[0], m[1]);
					}
				}
				outlineGun(asset, gunId, offset, place, ps, collector);
				return;
			}
		}
		// far away (allowing for a zoomed scope), the gun's simplified mesh: the same look at that size, half the triangles
		org.joml.Matrix4f at = ps.last().pose();
		double dist = Math.sqrt(at.m30() * at.m30() + at.m31() * at.m31() + at.m32() * at.m32()) / Math.max(1.0, GunClient.magnification());
		int level = MeshLod.level(dist);
		for (ViewmodelAsset.Part part : dev.countercraft.client.skins.Skins.gunParts(asset, gunId, finish)) {
			if (part.vertexCount() == 0 || Byte.toUnsignedInt(part.joints()[0]) < offset) {
				continue; // the viewmodel's arms
			}
			Identifier tex = part.texture();
			MeshLod.Lod lod = MeshLod.of(part.indices(), part.pos(), part.nrm(), part.uv(), part.vertexCount(), level);
			collector.submitCustomGeometry(ps, RenderTypes.entityCutout(tex),
					(p, vc) -> FastMesh.emit(lod.indices(), lod.used(), part.uv(), part.pos(), part.nrm(), part.vertexCount(), place, p, vc, light, overlay, true));
		}
		outlineGun(asset, gunId, offset, place, ps, collector);
	}

	/** The held gun into the outline pass ({@link #outline}), rigid at its placement on the hand. */
	private void outlineGun(ViewmodelAsset asset, String gunId, int offset, float[] place, PoseStack ps, SubmitNodeCollector collector) {
		if (outline == 0) {
			return;
		}
		for (ViewmodelAsset.Part part : dev.countercraft.client.skins.Skins.gunParts(asset, gunId, finish)) {
			if (part.vertexCount() > 0 && Byte.toUnsignedInt(part.joints()[0]) >= offset) {
				outline(ps, collector, part.texture(), part.indices(), part.uv(), part.pos(), part.nrm(), part.vertexCount(), place, true);
			}
		}
	}

	private static void skinPart(int nv, float[] pos, float[] nrm, byte[] jn, byte[] wt, float[] m, float[] sPos, float[] sNrm) {
		for (int v = 0; v < nv; v++) {
			float px = pos[v * 3], py = pos[v * 3 + 1], pz = pos[v * 3 + 2];
			float nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
			float ox = 0, oy = 0, oz = 0, qx = 0, qy = 0, qz = 0;
			for (int k = 0; k < 4; k++) {
				int w8 = Byte.toUnsignedInt(wt[v * 4 + k]);
				if (w8 == 0) {
					continue;
				}
				float w = w8 / 255f;
				int o = Byte.toUnsignedInt(jn[v * 4 + k]) * 12;
				if (o + 11 >= m.length) {
					continue;
				}
				ox += w * (m[o] * px + m[o + 1] * py + m[o + 2] * pz + m[o + 3]);
				oy += w * (m[o + 4] * px + m[o + 5] * py + m[o + 6] * pz + m[o + 7]);
				oz += w * (m[o + 8] * px + m[o + 9] * py + m[o + 10] * pz + m[o + 11]);
				qx += w * (m[o] * nx + m[o + 1] * ny + m[o + 2] * nz);
				qy += w * (m[o + 4] * nx + m[o + 5] * ny + m[o + 6] * nz);
				qz += w * (m[o + 8] * nx + m[o + 9] * ny + m[o + 10] * nz);
			}
			sPos[v * 3] = ox;
			sPos[v * 3 + 1] = oy;
			sPos[v * 3 + 2] = oz;
			float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
			if (len > 1e-6f) {
				qx /= len;
				qy /= len;
				qz /= len;
			} else {
				qy = 1f;
			}
			sNrm[v * 3] = qx;
			sNrm[v * 3 + 1] = qy;
			sNrm[v * 3 + 2] = qz;
		}
	}
}
