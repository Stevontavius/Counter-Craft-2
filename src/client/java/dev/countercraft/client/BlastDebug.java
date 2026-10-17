package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.countercraft.gun.BlastField;
import dev.countercraft.gun.PlantedC4;
import dev.countercraft.net.BlastMapPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * The C4 test's blast debug view (on with {@code /beta test_c4 1}, not in the settings): each planted C4's shockwave
 * map, every other floor block it reaches tinted by the damage it would take there (red lethal, through orange,
 * yellow and lime, to cyan at the edge; nothing where walls keep it out), two rings for the instant-kill and outer
 * reach, and a panel ({@link DebugPanels}): the damage curve, each bomb's fuse and map, and your own distances and
 * damage as the server works them out.
 */
public final class BlastDebug {
	/** Lethal red, then orange, yellow, lime, cyan as the damage drops. */
	private static final int[] COLORS = {0xFFFF2020, 0xFFFF8A20, 0xFFFFE020, 0xFF90FF40, 0xFF40E0FF};
	private static final int SEGMENTS = 96;
	/** Map tiles drawn at most this far from the camera (blocks). */
	private static final double MAP_RANGE = 72;

	/** A bomb's map: its origin block, and 4 bytes a floor block (offset x, y, z, damage x 4; 255 lethal). */
	private record Map3(int x, int y, int z, byte[] data) {
	}

	private static final Map<Integer, Map3> MAPS = new HashMap<>();

	private BlastDebug() {
	}

	/** On while the world's C4 test is (it can't be turned off on its own). */
	public static boolean on() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && dev.countercraft.team.CsInventory.c4Test(mc.level);
	}

	public static void onMap(BlastMapPayload p) {
		MAPS.put(p.bomb(), new Map3(p.x(), p.y(), p.z(), p.data()));
	}

	private static List<PlantedC4> bombs(Minecraft mc) {
		return mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(256));
	}

	/** A damage's colour against {@code maxHealth}: red once it kills, orange, yellow, lime, cyan as it falls. */
	public static int color(float damage, float maxHealth) {
		if (damage >= maxHealth) {
			return COLORS[0];
		}
		float f = damage / maxHealth;
		return f >= 0.75f ? COLORS[1] : f >= 0.5f ? COLORS[2] : f >= 0.25f ? COLORS[3] : COLORS[4];
	}

	/** The map tiles and the rings, in the world. */
	public static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!on() || mc.player == null) {
			MAPS.clear();
			return;
		}
		List<PlantedC4> bombs = bombs(mc);
		MAPS.keySet().removeIf(id -> bombs.stream().noneMatch(b -> b.getId() == id));
		if (bombs.isEmpty()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		float max = mc.player.getMaxHealth();
		PoseStack ps = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.debugQuads(), (pose, vc) -> {
			for (Map3 m : MAPS.values()) {
				byte[] d = m.data();
				for (int i = 0; i + 3 < d.length; i += 4) {
					double x = m.x() + d[i] + 0.5 - cam.x;
					double y = m.y() + d[i + 1] + 0.03 - cam.y;
					double z = m.z() + d[i + 2] + 0.5 - cam.z;
					if (x * x + y * y + z * z > MAP_RANGE * MAP_RANGE) {
						continue;
					}
					int q = d[i + 3] & 255;
					float dmg = q == 255 ? PlantedC4.LETHAL : q / 4f;
					int c = color(dmg, max) & 0xFFFFFF | 0x70 << 24;
					tile(pose, vc, (float) x, (float) y, (float) z, 0.85f, c);
				}
			}
		});
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.lines(), (pose, vc) -> {
			for (PlantedC4 bomb : bombs) {
				Vec3 c = bomb.blastCentre().subtract(cam);
				circle(pose, vc, c, PlantedC4.CERTAIN_DEATH, COLORS[0]);
				circle(pose, vc, c, PlantedC4.BLAST_RADIUS, COLORS[4]);
			}
		});
	}

	/** A flat square (a 2 x 2 block cell's floor), {@code size} blocks across, centred at (x, y, z). */
	private static void tile(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float size, int argb) {
		float s = size;
		vc.addVertex(pose, x - s, y, z - s).setColor(argb);
		vc.addVertex(pose, x - s, y, z + s).setColor(argb);
		vc.addVertex(pose, x + s, y, z + s).setColor(argb);
		vc.addVertex(pose, x + s, y, z - s).setColor(argb);
	}

	private static void circle(PoseStack.Pose pose, VertexConsumer vc, Vec3 c, double r, int color) {
		Vec3 prev = null;
		for (int s = 0; s <= SEGMENTS; s++) {
			double a = s * Math.PI * 2 / SEGMENTS;
			Vec3 p = c.add(Math.cos(a) * r, 0, Math.sin(a) * r);
			if (prev != null) {
				Vec3 n = p.subtract(prev).normalize();
				vc.addVertex(pose, (float) prev.x, (float) prev.y, (float) prev.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2.5f);
				vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2.5f);
			}
			prev = p;
		}
	}

	/** What a damage takes, as the HUD shows it: % of your max health, or lethal. */
	private static String takes(float damage, float maxHealth) {
		return damage >= PlantedC4.LETHAL ? "LETHAL" : "-" + Math.round(damage / maxHealth * 100) + "%";
	}

	/** Labels in the world: none (the panel and the tiles' colours say it). */
	static void labels(GuiGraphicsExtractor g) {
	}

	/** The panel's lines (null when off). */
	static List<Component> panel() {
		Minecraft mc = Minecraft.getInstance();
		if (!on() || mc.player == null) {
			return null;
		}
		float max = mc.player.getMaxHealth();
		List<Component> lines = new ArrayList<>();
		lines.add(DebugPanels.uni("C4 BLAST DEBUG  (/beta test_c4 0)"));
		lines.add(DebugPanels.uni(String.format("shockwave %.0f m/s, stopped by walls, detours cost x%.0f", PlantedC4.WAVE_SPEED,
				1 + BlastField.CORNER)).withColor(0xC0C0C0));
		lines.add(DebugPanels.uni("■ 0-" + fmt(PlantedC4.CERTAIN_DEATH) + " m   LETHAL at once").withColor(COLORS[0] & 0xFFFFFF));
		for (int i = 1; i < PlantedC4.CURVE_DISTANCE.length; i++) {
			double a = PlantedC4.CURVE_DISTANCE[i - 1];
			double b = PlantedC4.CURVE_DISTANCE[i];
			float da = i == 1 ? PlantedC4.CURVE_DAMAGE[0] : PlantedC4.CURVE_DAMAGE[i - 1];
			float db = PlantedC4.CURVE_DAMAGE[i];
			lines.add(DebugPanels.uni("■ " + fmt(a) + "-" + fmt(b) + " m   " + takes(da, max) + " to " + takes(db, max))
					.withColor(color((da + db) / 2, max) & 0xFFFFFF));
		}
		List<PlantedC4> bombs = bombs(mc);
		if (bombs.isEmpty()) {
			lines.add(DebugPanels.uni("no planted C4 near"));
			return lines;
		}
		for (PlantedC4 bomb : bombs) {
			Map3 m = MAPS.get(bomb.getId());
			lines.add(DebugPanels.uni(String.format("bomb #%d  fuse ~%ds  map: %s", bomb.getId(), Math.max(0, bomb.fuseAtBeep() / 20),
					m == null ? "working it out..." : m.data().length / 4 + " floor blocks")));
		}
		var v = mc.player.getAttached(PlantedC4.PREVIEW);
		if (v != null) {
			String path = Float.isNaN(v.path()) ? (v.progress() < 1 ? "not worked out yet" : "walled off") : fmt(v.path()) + " m";
			String eff = Float.isNaN(v.effective()) ? "-" : fmt(v.effective()) + " m";
			lines.add(DebugPanels.uni("you: straight " + fmt(v.straight()) + " m, wave path " + path + ", counts as " + eff));
			boolean kills = v.damage() >= mc.player.getHealth() + mc.player.getAbsorptionAmount();
			lines.add(DebugPanels.uni("    " + (v.damage() <= 0 ? "out of the blast" : takes(v.damage(), max) + (kills ? " (kills you)" : "")))
					.withColor(v.damage() <= 0 ? 0xC0C0C0 : color(v.damage(), max) & 0xFFFFFF));
		}
		return lines;
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? Long.toString(Math.round(v)) : String.format("%.1f", v);
	}
}
