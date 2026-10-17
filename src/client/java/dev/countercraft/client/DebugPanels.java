package dev.countercraft.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * Counter-Craft's debug panels (the C4 test's blast debug, the grenade debug) in one stack down the left of the screen,
 * in Minecraft's Unifont: each below the last, on into a second column when they run out of room, so they never
 * overlap. Their in-world labels go under them. Hidden while Minecraft's own debug screen (F3) covers that corner.
 */
public final class DebugPanels {
	private static final FontDescription UNIFONT = new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));
	private static final int LINE = 10;
	private static final int GAP = 6;

	private DebugPanels() {
	}

	/** A line in the debug panels' font. */
	public static MutableComponent uni(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(UNIFONT));
	}

	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		// labels in the world first, the panels over them
		BlastDebug.labels(g);
		GrenadeTracer.labels(g);
		List<List<Component>> panels = new ArrayList<>();
		for (List<Component> p : new List[] {BlastDebug.panel(), GrenadeTracer.panel()}) {
			if (p != null && !p.isEmpty()) {
				panels.add(p);
			}
		}
		var font = mc.font;
		// under the C4 news at the top left while it shows
		int top = Math.max(g.guiHeight() / 4, Math.round(dev.countercraft.client.hud.HudSpace.bottom(dev.countercraft.client.hud.HudSpace.Panel.NOTICE, 0) + 8));
		int x = 6;
		int y = top;
		int column = 0;
		for (List<Component> p : panels) {
			int w = 0;
			for (Component l : p) {
				w = Math.max(w, font.width(l));
			}
			int h = p.size() * LINE;
			if (y > top && y + h > g.guiHeight() - 8) {
				x += column + GAP + 6;
				y = top;
				column = 0;
			}
			g.fill(x - 3, y - 3, x + w + 3, y + h + 1, 0xA0000000);
			int ly = y;
			for (Component l : p) {
				g.text(font, l, x, ly, 0xFFFFFFFF, true);
				ly += LINE;
			}
			column = Math.max(column, w);
			y += h + GAP + 4;
		}
	}
}
