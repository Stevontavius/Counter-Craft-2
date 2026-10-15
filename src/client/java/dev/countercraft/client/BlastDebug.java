package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.countercraft.gun.PlantedC4;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The C4 test's blast debug view (on with {@code /beta test_c4 1}, not in the settings): every planted C4's blast bands (PlantedC4) drawn
 * around it as rings in each band's colour (flat at the blast's height, and two upright ones for its sphere), each ring
 * labelled with the health it takes, and a debug panel in its own font (Minecraft's Unifont): the bands, and for each
 * bomb its fuse, your distance and what it would do to you.
 */
public final class BlastDebug {
	/** Lethal red, then orange, yellow, lime, cyan as the damage drops. */
	private static final int[] COLORS = {0xFFFF2020, 0xFFFF8A20, 0xFFFFE020, 0xFF90FF40, 0xFF40E0FF};
	private static final int SEGMENTS = 96;
	private static final FontDescription UNIFONT = new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));

	private BlastDebug() {
	}

	/** On while the world's C4 test is (it can't be turned off on its own). */
	public static boolean on() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && dev.countercraft.team.CsInventory.c4Test(mc.level);
	}

	private static List<PlantedC4> bombs(Minecraft mc) {
		return mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(256));
	}

	/** The rings, in the world. */
	public static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!on() || mc.player == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		List<Object[]> rings = new ArrayList<>();
		for (PlantedC4 bomb : bombs(mc)) {
			Vec3 c = bomb.blastCentre().subtract(cam);
			for (int i = 0; i < PlantedC4.BAND_EDGES.length; i++) {
				rings.add(new Object[] {c, PlantedC4.BAND_EDGES[i], COLORS[i]});
			}
		}
		if (rings.isEmpty()) {
			return;
		}
		PoseStack ps = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.lines(), (pose, vc) -> {
			for (Object[] r : rings) {
				Vec3 c = (Vec3) r[0];
				double rad = (double) r[1];
				int color = (int) r[2];
				for (int plane = 0; plane < 3; plane++) {
					Vec3 prev = null;
					for (int s = 0; s <= SEGMENTS; s++) {
						double a = s * Math.PI * 2 / SEGMENTS;
						double u = Math.cos(a) * rad;
						double v = Math.sin(a) * rad;
						Vec3 p = c.add(plane == 0 ? new Vec3(u, 0, v) : plane == 1 ? new Vec3(u, v, 0) : new Vec3(0, v, u));
						if (prev != null) {
							line(pose, vc, prev, p, color);
						}
						prev = p;
					}
				}
			}
		});
	}

	private static void line(PoseStack.Pose pose, VertexConsumer vc, Vec3 a, Vec3 b, int color) {
		Vec3 n = b.subtract(a).normalize();
		vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2.5f);
		vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2.5f);
	}

	private static net.minecraft.network.chat.MutableComponent uni(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(UNIFONT));
	}

	/** What a band takes, as the HUD shows it: % of your max health, or lethal. */
	private static String takes(int band, float maxHealth) {
		float d = PlantedC4.BAND_DAMAGE[band];
		return d >= PlantedC4.LETHAL ? "LETHAL" : "-" + Math.round(d / maxHealth * 100) + "%";
	}

	/** The ring labels (on screen where the rings are) and the debug panel. */
	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (!on() || mc.player == null) {
			return;
		}
		var font = mc.font;
		float max = mc.player.getMaxHealth();
		List<PlantedC4> bombs = bombs(mc);
		// labels: each ring's four compass points, where they're in front of the camera
		for (PlantedC4 bomb : bombs) {
			Vec3 c = bomb.blastCentre();
			for (int i = 0; i < PlantedC4.BAND_EDGES.length; i++) {
				double r = PlantedC4.BAND_EDGES[i];
				for (Vec3 off : new Vec3[] {new Vec3(r, 0, 0), new Vec3(-r, 0, 0), new Vec3(0, 0, r), new Vec3(0, 0, -r)}) {
					Vec3 sp = mc.gameRenderer.projectPointToScreen(c.add(off));
					if (sp.z <= 0 || sp.z >= 1 || Math.abs(sp.x) > 1 || Math.abs(sp.y) > 1) {
						continue; // behind the camera or off screen
					}
					int x = (int) ((sp.x + 1) / 2 * g.guiWidth());
					int y = (int) ((1 - sp.y) / 2 * g.guiHeight());
					Component label = uni(takes(i, max) + "  " + fmt(r) + "m");
					g.text(font, label, x - font.width(label) / 2, y - 4, COLORS[i], true);
				}
			}
		}
		// the panel
		int x = 6;
		int y = g.guiHeight() / 4;
		List<Component> lines = new ArrayList<>();
		lines.add(uni("C4 BLAST RADIUS DEBUG  (/beta test_c4 0)"));
		double inner = 0;
		for (int i = 0; i < PlantedC4.BAND_EDGES.length; i++) {
			lines.add(uni("■ " + fmt(inner) + "-" + fmt(PlantedC4.BAND_EDGES[i]) + " m   " + takes(i, max)).withColor(COLORS[i] & 0xFFFFFF));
			inner = PlantedC4.BAND_EDGES[i];
		}
		if (bombs.isEmpty()) {
			lines.add(uni("no planted C4 near"));
		}
		Vec3 me = mc.player.getBoundingBox().getCenter();
		for (PlantedC4 bomb : bombs) {
			double d = me.distanceTo(bomb.blastCentre());
			float dmg = PlantedC4.damageAt(d);
			int band = -1;
			for (int i = 0; i < PlantedC4.BAND_EDGES.length && band < 0; i++) {
				band = d <= PlantedC4.BAND_EDGES[i] ? i : -1;
			}
			String you = band < 0 ? "outside the blast" : "band " + (band + 1) + ": " + takes(band, max)
					+ (dmg >= mc.player.getHealth() + mc.player.getAbsorptionAmount() ? " (kills you)" : "");
			lines.add(uni(String.format("bomb #%d  fuse ~%ds  you: %s m  %s", bomb.getId(), Math.max(0, bomb.fuseAtBeep() / 20), fmt(d), you))
					.withColor(band < 0 ? 0xC0C0C0 : COLORS[band] & 0xFFFFFF));
		}
		int w = 0;
		for (Component l : lines) {
			w = Math.max(w, font.width(l));
		}
		g.fill(x - 3, y - 3, x + w + 3, y + lines.size() * 10 + 1, 0xA0000000);
		for (Component l : lines) {
			g.text(font, l, x, y, 0xFFFFFFFF, true);
			y += 10;
		}
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? Long.toString(Math.round(v)) : String.format("%.1f", v);
	}
}
