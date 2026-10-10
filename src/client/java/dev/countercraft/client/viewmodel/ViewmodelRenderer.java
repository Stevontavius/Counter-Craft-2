package dev.countercraft.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** CPU-skins a viewmodel pose and submits it as custom geometry in the first-person hand pass. */
public final class ViewmodelRenderer {
	/** Vanilla's animated fire (32 frames of 16x16 stacked vertically): the molotov rag and the lighter flame. */
	private static final Identifier FIRE = Identifier.withDefaultNamespace("textures/block/fire_0.png");
	private static final int FIRE_FRAMES = 32;

	/** A flame billboard at a view-space point: centre x, y, z (metres) and height. */
	public record Flame(float x, float y, float z, float height) {
	}

	private static final float INCH = 0.0254f;
	/** cc_ads_fov: overrides the gun's ironsight FOV while tuning (-1: use the gun's). */
	public static float ADS_VIEWMODEL_FOV = -1f;
	/** The held gun's m_flIronSightFOV (CS2 draws the aimed viewmodel at it; 45 for both rifles). */
	private float ironsightFov = 45f;

	public void ironsightFov(float fov) {
		ironsightFov = fov;
	}
	/** How much nearer the eye the viewmodel comes at full ADS, metres, per rifle (fitted in game to CS2's frame). */
	private static final java.util.Map<String, Float> ADS_FORWARD_BY_GUN = java.util.Map.of("sg556", 0.27f, "aug", 0.22f);
	/** cc_ads_forward: overrides the fitted distance while tuning (-1: use the fit). */
	public static float ADS_FORWARD = -1f;
	/** The whole viewmodel scaled about the eye at full ADS, to keep its near parts past the near plane. */
	public static float ADS_DEPTH_SCALE = 4f;
	/** Minecraft draws hands with a 70 degree vertical FOV. */
	/** Skinned positions / normals per viewmodel part, reused frame to frame. */
	private final java.util.Map<ViewmodelAsset.Part, float[][]> skinned = new java.util.IdentityHashMap<>();
	private static final double MC_HAND_VFOV = 70.0;

	private float[] sPos = new float[0];
	private float[] sNrm = new float[0];

	public void submit(ViewmodelAsset asset, float[] pose, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		submit(asset, pose, poseStack, collector, light, List.of(), 1f, null, null);
	}

	/**
	 * The viewmodel (with CS2's viewmodel FOV and offsets) plus flame billboards in the same space. {@code offsetWeight}
	 * fades the viewmodel offsets out while aiming down sights (the ADS pose puts the scope on the view axis itself).
	 */
	public void submit(ViewmodelAsset asset, float[] pose, PoseStack poseStack, SubmitNodeCollector collector, int light, List<Flame> flames,
			float offsetWeight, float[] bob, BiConsumer<PoseStack, SubmitNodeCollector> extra) {
		poseStack.pushPose();
		mirrored = !ViewmodelSettings.rightHand;
		if (mirrored) {
			// cl_righthand 0: the whole viewmodel (offsets too) mirrored, like CS:GO, lit by the same lights as the right
			// hand (its normals mirror with it; the GPU shaders swap front / back faces for the reversed winding)
			poseStack.scale(-1f, 1f, 1f);
		}
		// aiming down sights (AUG / SG 553): CS2 draws the viewmodel at the gun's ironsight FOV (m_flIronSightFOV 45),
		// without your offsets, and nearer the eye, so the scope fills the screen (measured: CS2's zoomed frame is the
		// same at any viewmodel_fov / offset / fov; fitted in game so each eyepiece ring matches CS2's frame)
		float ads = 1f - offsetWeight;
		// ...which brings the scope's back plate within a few cm of the eye, inside Minecraft's 5 cm hand near plane:
		// scaled up evenly about the eye, the picture is the same but nothing gets clipped
		adsNow = ads;
		float depth = 1f + (ADS_DEPTH_SCALE - 1f) * ads;
		poseStack.scale(depth, depth, depth);
		float adsFov = ADS_VIEWMODEL_FOV > 0 ? ADS_VIEWMODEL_FOV : ironsightFov;
		float k = fovScale(ViewmodelSettings.fov + (adsFov - ViewmodelSettings.fov) * ads);
		poseStack.scale(k, k, 1f);
		// CS2 viewmodel_offset_x/y/z: right, forward, up (inches); Minecraft view space is X right, Y up, -Z forward
		float o = INCH * offsetWeight;
		float forward = ADS_FORWARD >= 0 ? ADS_FORWARD : ADS_FORWARD_BY_GUN.getOrDefault(gun, 0.3f);
		poseStack.translate(ViewmodelSettings.offsetX * o, ViewmodelSettings.offsetZ * o, -ViewmodelSettings.offsetY * o + forward * ads);
		if (bob != null) {
			poseStack.translate(bob[0], bob[1], bob[2]); // CS2 movement bob (view-space metres)
		}
		submitParts(asset, pose, poseStack, collector, light);
		if (!flames.isEmpty()) {
			int frame = (int) ((System.nanoTime() / 50_000_000L) % FIRE_FRAMES);
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(FIRE), (p, vc) -> {
				for (Flame f : flames) {
					flame(f, frame, p, vc);
				}
			});
		}
		if (extra != null) {
			extra.accept(poseStack, collector); // effects in viewmodel space (the muzzle flash)
		}
		poseStack.popPose();
	}

	/** A model in world space (a thrown grenade): no viewmodel FOV or offsets, the pose stack places it. */
	public void submitWorld(ViewmodelAsset asset, float[] pose, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		if (dev.countercraft.client.gpu.GpuAgents.active()) {
			var block = dev.countercraft.client.gpu.GpuAgents.block(poseStack.last().pose(), pose, asset.boneCount, light, OverlayTexture.NO_OVERLAY);
			if (block != null) {
				for (ViewmodelAsset.Part part : asset.parts) {
					dev.countercraft.client.gpu.GpuAgents.draw(part.pos(), part.nrm(), part.uv(), part.joints(), part.weights(), part.vertexCount(),
							part.indices(), part.texture(), block);
				}
				return;
			}
		}
		for (ViewmodelAsset.Part part : asset.parts) {
			skin(part, pose);
			float[] pos = sPos.clone(); // several grenades can share an asset in one frame
			float[] nrm = sNrm.clone();
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(part.texture()),
					(p, vc) -> FastMesh.emit(part.indices(), part.uv(), pos, nrm, part.vertexCount(), null, p, vc, light, OverlayTexture.NO_OVERLAY));
		}
	}

	/** The first-person viewmodel: one per frame, so each part skins into its own reused arrays (no garbage). */
	/** The weapon the next {@link #submit} draws and its finish (null: no finish / not a CS weapon viewmodel). */
	private String gun;
	private dev.countercraft.client.skins.Skins.Skin skin;

	public void skin(String gun, dev.countercraft.client.skins.Skins.Skin skin) {
		this.gun = gun;
		this.skin = skin;
	}

	/** The ADS blend of the viewmodel being drawn, and the asset (for its scope lens). */
	private float adsNow;
	private ViewmodelAsset current;

	private static final float[] WORLD_UP = {0f, 1f, 0f};
	/** A viewmodel or agent texture's roughness / metal / AO and normal maps (converter step vmmaps), or nulls. */
	private static final java.util.Map<Identifier, Identifier[]> MAPS = new java.util.HashMap<>();

	/**
	 * A part's shading maps: its material's own; for a finish, the material the finish paints (the same mesh in
	 * {@code asset}), so the finish sits on the blade's own polish and metal. Also the agents' held guns.
	 */
	public static Identifier[] maps(ViewmodelAsset asset, ViewmodelAsset.Part part) {
		Identifier base = part.texture();
		if (!base.getPath().startsWith("textures/viewmodel/") && asset != null) {
			for (ViewmodelAsset.Part p : asset.parts) {
				if (p.pos() == part.pos()) {
					base = p.texture();
					break;
				}
			}
		}
		final Identifier stockTex = base;
		return COMBINED.computeIfAbsent(part.texture(), t -> {
			Identifier[] own = mapsOf(t);
			Identifier[] stock = stockTex == t ? own : mapsOf(stockTex);
			// a finish's own map (knife paints: non-metal; glove finishes) wins, else the material it paints
			return new Identifier[] {own[0] != null ? own[0] : stock[0], own[1] != null ? own[1] : stock[1]};
		});
	}

	private static final java.util.Map<Identifier, Identifier[]> COMBINED = new java.util.HashMap<>();

	/** A texture's own maps: {@code <tex>_s.png} and {@code <tex>_n.png} when the pack has them (agents' textures too). */
	public static Identifier[] mapsOf(Identifier tex) {
		return MAPS.computeIfAbsent(tex, b -> {
			var rm = net.minecraft.client.Minecraft.getInstance().getResourceManager();
			Identifier s = b.withPath(b.getPath().replace(".png", "_s.png"));
			Identifier n = b.withPath(b.getPath().replace(".png", "_n.png"));
			return new Identifier[] {rm.getResource(s).isPresent() ? s : null, rm.getResource(n).isPresent() ? n : null};
		});
	}

	/** Resources reloaded: look the maps up again. */
	public static void clearMaps() {
		MAPS.clear();
		COMBINED.clear();
	}

	/** This frame's GPU pose block for the viewmodel, or null (drawn on the CPU). */
	private dev.countercraft.client.gpu.GpuAgents.Block gpuBlock;

	private void submitParts(ViewmodelAsset asset, float[] pose, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		current = asset;
		portal = null;
		float[] window = adsNow > 0.5f && asset.lens != null ? lensWindow(pose, poseStack.last().pose(), asset.lens) : null;
		// on the GPU when it's on (skinned there, the lens window cut per pixel); the CPU path below otherwise
		// lit the CS:GO way (agent_vm.fsh) unless Viewmodel Shaders is off (plain entity shading, same textures); the hand
		// pose's output space is world-aligned, so the world's up is +Y
		gpuBlock = dev.countercraft.client.gpu.GpuAgents.active()
				? dev.countercraft.client.gpu.GpuAgents.block(poseStack.last().pose(), pose, asset.boneCount, light, OverlayTexture.NO_OVERLAY, false, window,
						dev.countercraft.client.options.CcOptions.viewmodelShaders() ? WORLD_UP : null)
				: null;
		ViewmodelAsset.Part[] arms = dev.countercraft.client.GunClient.agentArms();
		for (ViewmodelAsset.Part part : asset.parts) {
			if (ViewmodelAsset.isArms(part) && arms == null) {
				submitPart(part, pose, poseStack, collector, light);
			}
		}
		// the gun: its finish (texture, or the CS:GO mesh for a legacy-model finish)
		ViewmodelAsset.Part last = null;
		for (ViewmodelAsset.Part part : gun == null ? dev.countercraft.client.skins.Skins.gunParts(asset, "", null)
				: dev.countercraft.client.skins.Skins.gunParts(asset, gun, skin)) {
			submitPart(part, pose, poseStack, collector, light);
			last = part;
		}
		if (portal != null && last != null) {
			lensRing(portal, last.texture(), poseStack, collector, light);
		}
		if (arms != null) {
			for (ViewmodelAsset.Part part : arms) {
				submitPart(part, pose, poseStack, collector, light); // the agent's own gloves and sleeves
			}
		}
	}

	private void submitPart(ViewmodelAsset.Part part, float[] pose, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		if (gpuBlock != null && !part.texture().getPath().contains("chargemeter")) {
			Identifier[] m = maps(current, part);
			dev.countercraft.client.gpu.GpuAgents.draw(part.pos(), part.nrm(), part.uv(), part.joints(), part.weights(), part.vertexCount(),
					part.indices(), part.texture(), gpuBlock, m[0], m[1]);
			return;
		}
		skin(part, pose);
		float[][] buf = skinned.computeIfAbsent(part, k -> new float[2][]);
		int n = part.vertexCount() * 3;
		if (buf[0] == null || buf[0].length < n) {
			buf[0] = new float[n];
			buf[1] = new float[n];
		}
		System.arraycopy(sPos, 0, buf[0], 0, n);
		System.arraycopy(sNrm, 0, buf[1], 0, n);
		float[] pos = buf[0];
		float[] nrm = buf[1];
		if (part.texture().getPath().contains("chargemeter")) {
			chargeMeter(part, pos, nrm, poseStack, collector);
			return;
		}
		int[] idx = adsNow > 0.5f && current != null && current.lens != null && !ViewmodelAsset.isArms(part)
				? lensCull(part, pos, pose, poseStack.last().pose(), current.lens) : part.indices();
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(part.texture()),
				(p, vc) -> FastMesh.emit(idx, part.uv(), pos, nrm, part.vertexCount(), null, p, vc, light, OverlayTexture.NO_OVERLAY));
	}

	/**
	 * CS2's scope lens, as a portal: the triangles that lie behind the lens plane and, on screen, wholly inside the
	 * eyecup bore aren't drawn, so the view shows through the bore (its wall, in front of the plane, stays: the dark
	 * ring around CS2's lens). {@code m} is the viewmodel transform (view space: -Z forward).
	 */
	private int[] lensCull(ViewmodelAsset.Part part, float[] pos, float[] pose, Matrix4f m, float[] lens) {
		float[] win = lensWindow(pose, m, lens);
		if (win == null) {
			return part.indices();
		}
		float ex0 = win[0], ey0 = win[1], ez0 = win[2];
		float[] c = {win[3], win[4], win[5]};
		float r = win[6];
		float[] a = {win[7], win[8], win[9]};
		float ecx = c[0] - ex0, ecy = c[1] - ey0, ecz = c[2] - ez0;
		float num = ecx * a[0] + ecy * a[1] + ecz * a[2];
		float r2 = r * r * 0.97f;
		int n = part.vertexCount();
		if (inside.length < n) {
			inside = new boolean[n];
		}
		for (int v = 0; v < n; v++) {
			float wx = pos[v * 3] - ex0, wy = pos[v * 3 + 1] - ey0, wz = pos[v * 3 + 2] - ez0;
			float den = wx * a[0] + wy * a[1] + wz * a[2];
			if (den <= num) {
				inside[v] = false; // not beyond the plane
				continue;
			}
			float k = num / den;
			float px = wx * k - ecx, py = wy * k - ecy, pz = wz * k - ecz;
			inside[v] = px * px + py * py + pz * pz < r2;
		}
		// culled triangles become zero-area ones, in a buffer reused frame to frame (no garbage per frame)
		int[] src = part.indices();
		int[] out = cullBuffers.computeIfAbsent(part, q -> new int[src.length]);
		int culled = 0;
		for (int t = 0; t < src.length; t += 3) {
			boolean gone = inside[src[t]] && inside[src[t + 1]] && inside[src[t + 2]];
			out[t] = src[t];
			out[t + 1] = gone ? src[t] : src[t + 1];
			out[t + 2] = gone ? src[t] : src[t + 2];
			culled += gone ? 1 : 0;
		}
		lastCulled = culled;
		return out;
	}

	/**
	 * The lens window this frame, in viewmodel (skinned) space: {eye xyz, centre xyz, radius, axis away from the eye
	 * xyz}, or null when the eye isn't behind it; also sets {@link #portal} for the ring. {@code m}: the viewmodel
	 * transform.
	 */
	private float[] lensWindow(float[] pose, Matrix4f m, float[] lens) {
		int o = (int) lens[0] * 12;
		// lens[4..6] points away from the eye; t: towards it
		float tx = -lens[4], ty = -lens[5], tz = -lens[6];
		// where the eye is along the scope axis (model space of the lens bone): through the viewmodel transform and the
		// bone's (rigid) skinning matrix, backwards
		Vector3f eyeVm = new Matrix4f(m).invert().transformPosition(new Vector3f());
		float ex = eyeVm.x - pose[o + 3], ey = eyeVm.y - pose[o + 7], ez = eyeVm.z - pose[o + 11];
		float mx = pose[o] * ex + pose[o + 4] * ey + pose[o + 8] * ez;
		float my = pose[o + 1] * ex + pose[o + 5] * ey + pose[o + 9] * ez;
		float mz = pose[o + 2] * ex + pose[o + 6] * ey + pose[o + 10] * ez;
		float eyeAlong = (mx - lens[1]) * tx + (my - lens[2]) * ty + (mz - lens[3]) * tz;
		// the portal: across the bore at its mouth, or 1 cm ahead of the eye when the eye is inside it, as wide as the
		// bore there
		float along = Math.max(0.002f, Math.min(LENS_PLANE_BACK, eyeAlong - 0.01f));
		float r = LENS_CORE + (LENS_RADIUS - LENS_CORE) * along / LENS_PLANE_BACK;
		float[] c = bone(pose, o, lens[1] + tx * along, lens[2] + ty * along, lens[3] + tz * along, true);
		float[] a = bone(pose, o, lens[4], lens[5], lens[6], false);
		boolean alongZ = Math.abs(lens[6]) > 0.9f;
		float[] u = bone(pose, o, alongZ ? 1f : 0f, 0f, alongZ ? 0f : 1f, false);
		// all in viewmodel space (Minecraft's hand pose may rotate the view): a vertex is behind the portal when it lies
		// beyond the portal plane and the line from the eye to it passes through the portal disc
		float[] w2 = bone(pose, o, 0f, 1f, 0f, false);
		portal = new float[] {c[0], c[1], c[2], u[0], u[1], u[2], w2[0], w2[1], w2[2], r, -a[0], -a[1], -a[2]};
		float ecx = c[0] - eyeVm.x, ecy = c[1] - eyeVm.y, ecz = c[2] - eyeVm.z;
		float num = ecx * a[0] + ecy * a[1] + ecz * a[2];
		if (num <= 1e-5f) {
			lastCircle = String.format(java.util.Locale.ROOT, "portal not ahead of the eye: eye along %.3f", eyeAlong);
			portal = null;
			return null;
		}
		lastCircle = String.format(java.util.Locale.ROOT, "eye along %.3f, portal at %.3f r %.3f%s", eyeAlong, along, r,
				gpuBlock != null || dev.countercraft.client.gpu.GpuAgents.active() ? " (GPU, per pixel)" : " (CPU)");
		return new float[] {eyeVm.x, eyeVm.y, eyeVm.z, c[0], c[1], c[2], r * 0.985f, a[0], a[1], a[2]};
	}

	private final java.util.Map<ViewmodelAsset.Part, int[]> cullBuffers = new java.util.IdentityHashMap<>();

	/** cc_ads_debug: what the lens portal did last frame. */
	public static int lastCulled = -1;
	public static String lastCircle = "";

	public String lensDebug() {
		return "ads " + adsNow + ", asset " + (current == null ? "none" : current.lens == null ? "no lens" : "lens bone " + (int) current.lens[0])
				+ ", culled " + lastCulled + " tris, " + lastCircle;
	}

	private boolean[] inside = new boolean[0];
	/** This frame's lens portal in viewmodel space: centre, across 1, across 2, radius, towards the eye; or null. */
	private float[] portal;

	/**
	 * CS2's dark ring around the lens: a thin black annulus on the portal's edge, a hair towards the eye. It also hides
	 * the ragged edge where triangles crossing the lens were kept.
	 */
	private static void lensRing(float[] q, Identifier texture, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		float r = q[9];
		float in = r * 0.95f; // CS2: the ring is ~15% of its outer radius
		float out = r * 1.12f;
		float bx = q[0] + q[10] * 0.001f, by = q[1] + q[11] * 0.001f, bz = q[2] + q[12] * 0.001f;
		int seg = 48;
		collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture), (p, vc) -> {
			for (int i = 0; i < seg; i++) {
				double t0 = Math.PI * 2 * i / seg, t1 = Math.PI * 2 * (i + 1) / seg;
				float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0), c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
				ringVertex(p, vc, q, bx, by, bz, c0 * in, s0 * in, light);
				ringVertex(p, vc, q, bx, by, bz, c0 * out, s0 * out, light);
				ringVertex(p, vc, q, bx, by, bz, c1 * out, s1 * out, light);
				ringVertex(p, vc, q, bx, by, bz, c1 * in, s1 * in, light);
				// both faces
				ringVertex(p, vc, q, bx, by, bz, c1 * in, s1 * in, light);
				ringVertex(p, vc, q, bx, by, bz, c1 * out, s1 * out, light);
				ringVertex(p, vc, q, bx, by, bz, c0 * out, s0 * out, light);
				ringVertex(p, vc, q, bx, by, bz, c0 * in, s0 * in, light);
			}
		});
	}

	private static void ringVertex(PoseStack.Pose p, VertexConsumer vc, float[] q, float bx, float by, float bz, float a, float b, int light) {
		vc.addVertex(p, bx + q[3] * a + q[6] * b, by + q[4] * a + q[7] * b, bz + q[5] * a + q[8] * b)
				.setColor(0xFF000000)
				.setUv(0f, 0f)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(p, q[10], q[11], q[12]);
	}
	/**
	 * The portal sits on the eyepiece glass at the back of the scope, this far eye-side of it (m): CS2's lens shows the
	 * world there, not the scope's insides (its tube, the front lens, the front sight beyond).
	 */
	public static float LENS_PLANE_BACK = 0.003f;
	/** The portal's radius (m): the eyepiece glass (r 1.28 cm) and a little of its bezel. */
	public static float LENS_RADIUS = 0.014f;
	private static final float LENS_CORE = 0.014f;

	/** A point (or direction) in a bone's model space, through that bone's skinning matrix (12 floats at o). */
	private static float[] bone(float[] m, int o, float x, float y, float z, boolean point) {
		float t = point ? 1f : 0f;
		return new float[] {m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 3] * t, m[o + 4] * x + m[o + 5] * y + m[o + 6] * z + m[o + 7] * t,
				m[o + 8] * x + m[o + 9] * y + m[o + 10] * z + m[o + 11] * t};
	}

	/**
	 * The Zeus's charge display, as CS2's material drives it (weapon_pist_taser_chargemeter.vmat, unlit, its dynamic
	 * expressions decoded): charged ($ent_ammo), the texture slides to its "ready" frame (U + 0.9) at full brightness;
	 * recharging, it slides one texel per percent (U + floor($taser_charge x 99) / 128: the number and the red to yellow
	 * colour) and flickers (brightness 0.7 + 0.3 x frac(time x 6)). Drawn glowing over the gun, through its alpha.
	 */
	private void chargeMeter(ViewmodelAsset.Part part, float[] pos, float[] nrm, PoseStack poseStack, SubmitNodeCollector collector) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		net.minecraft.world.item.ItemStack st = mc.player == null ? net.minecraft.world.item.ItemStack.EMPTY : mc.player.getMainHandItem();
		int ammo = st.getOrDefault(dev.countercraft.ModComponents.AMMO, 1);
		Long end = st.get(dev.countercraft.ModComponents.RELOAD_END);
		boolean ready = ammo > 0 && end == null;
		float u;
		float bright;
		if (ready || mc.level == null) {
			u = 0.9f;
			bright = 1f;
		} else {
			double now = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			double charge = end == null ? 0 : 1 - (end - now) / dev.countercraft.gun.GunItem.TASER_RECHARGE_TICKS;
			charge = Math.max(0, Math.min(1, charge));
			u = (float) (Math.floor(charge * 99) / 128.0);
			double t = System.nanoTime() / 1e9;
			bright = (float) (0.7 + 0.3 * (t * 6 - Math.floor(t * 6)));
		}
		float[] base = part.uv();
		float[] uv = meterUv.length == base.length ? meterUv : (meterUv = new float[base.length]);
		for (int i = 0; i < base.length; i += 2) {
			uv[i] = base[i] + u;
			uv[i + 1] = base[i + 1];
		}
		int c = Math.round(255 * bright);
		int color = 0xFF000000 | c << 16 | c << 8 | c;
		float[] uvs = uv.clone();
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(part.texture()), (p, vc) -> {
			FastMesh.color = color;
			FastMesh.emit(part.indices(), uvs, pos, nrm, part.vertexCount(), null, p, vc, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false);
			FastMesh.color = 0xFFFFFFFF;
		});
	}

	private float[] meterUv = new float[0];

	/** Two crossed quads of animated fire, full bright, rising from the point. */
	/** Drawing mirrored (left hand): quads are emitted in the other order so they keep facing the camera. */
	private static boolean mirrored;

	private static void flame(Flame f, int frame, PoseStack.Pose p, VertexConsumer vc) {
		float h = f.height();
		float w = h * 0.5f;
		float v0 = (float) frame / FIRE_FRAMES;
		float v1 = (float) (frame + 1) / FIRE_FRAMES;
		float y0 = f.y() - h * 0.25f;
		float y1 = y0 + h;
		for (int q = 0; q < 2; q++) {
			float dx = q == 0 ? w : w * 0.7f;
			float dz = q == 0 ? 0f : w * 0.7f;
			if (mirrored) {
				fireVertex(p, vc, f.x() - dx, y0, f.z() - dz, 0f, v1);
				fireVertex(p, vc, f.x() - dx, y1, f.z() - dz, 0f, v0);
				fireVertex(p, vc, f.x() + dx, y1, f.z() + dz, 1f, v0);
				fireVertex(p, vc, f.x() + dx, y0, f.z() + dz, 1f, v1);
			} else {
				fireVertex(p, vc, f.x() - dx, y0, f.z() - dz, 0f, v1);
				fireVertex(p, vc, f.x() + dx, y0, f.z() + dz, 1f, v1);
				fireVertex(p, vc, f.x() + dx, y1, f.z() + dz, 1f, v0);
				fireVertex(p, vc, f.x() - dx, y1, f.z() - dz, 0f, v0);
			}
		}
	}

	private static void fireVertex(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, float u, float v) {
		vc.addVertex(p, x, y, z)
				.setColor(0xFFFFFFFF)
				.setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(p, 0f, 0f, 1f);
	}

	/**
	 * CS2's viewmodel_fov is horizontal at 4:3 (68 = ~53.7 degrees vertical, the same at any aspect ratio). Scaling
	 * view-space X/Y by this factor is exactly equivalent to projecting the viewmodel with that FOV.
	 */
	static float fovScale(float viewmodelFov) {
		double vfov = 2 * Math.atan(Math.tan(Math.toRadians(viewmodelFov / 2)) * 3.0 / 4.0);
		return (float) (Math.tan(Math.toRadians(MC_HAND_VFOV / 2)) / Math.tan(vfov / 2));
	}

	private void skin(ViewmodelAsset.Part part, float[] m) {
		int n = part.vertexCount();
		if (sPos.length < n * 3) {
			sPos = new float[n * 3];
			sNrm = new float[n * 3];
		}
		float[] pos = part.pos();
		float[] nrm = part.nrm();
		byte[] jn = part.joints();
		byte[] wt = part.weights();
		for (int v = 0; v < n; v++) {
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
			}
			sNrm[v * 3] = qx;
			sNrm[v * 3 + 1] = qy;
			sNrm[v * 3 + 2] = qz;
		}
	}
}
