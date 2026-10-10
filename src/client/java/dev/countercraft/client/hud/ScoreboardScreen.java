package dev.countercraft.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.countercraft.net.ScoreboardPayload;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * The scoreboard with a free cursor, like CS2 (right-click while holding the player list key). Click a player for
 * their options; let go of the key to close it. The game keeps running underneath.
 */
public final class ScoreboardScreen extends Screen {
	private static final String[] OPTIONS = {"Copy name", "Mute chat"};
	private UUID selected;
	private String selectedName;
	private int menuX;
	private int menuY;

	public ScoreboardScreen() {
		super(Component.literal("Scoreboard"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		if (!ScoreboardHud.held()) {
			onClose();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// no blur or dimming: the world stays visible, like CS2
	}

	private String option(int i) {
		if (i == 1) {
			return minecraft.getPlayerSocialManager().isHidden(selected) ? "Unmute chat" : "Mute chat";
		}
		return OPTIONS[i];
	}

	private boolean canMute() {
		return minecraft.player != null && !selected.equals(minecraft.player.getUUID());
	}

	private int options() {
		return canMute() ? 2 : 1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		ScoreboardHud.draw(g, selected != null ? -1 : mouseX, mouseY, selected);
		if (selected != null) {
			int w = 90;
			g.fill(menuX, menuY, menuX + w, menuY + 14 + options() * 13, 0xF0181C20);
			g.outline(menuX, menuY, w, 14 + options() * 13, 0x60FFFFFF);
			g.text(font, font.plainSubstrByWidth(selectedName, w - 8), menuX + 4, menuY + 3, 0xFFBFBFBF, false);
			for (int i = 0; i < options(); i++) {
				int y = menuY + 14 + i * 13;
				boolean hover = mouseX >= menuX && mouseX < menuX + w && mouseY >= y && mouseY < y + 13;
				if (hover) {
					g.fill(menuX + 1, y, menuX + w - 1, y + 13, 0x40FFFFFF);
				}
				g.text(font, option(i), menuX + 6, y + 3, 0xFFFFFFFF, false);
			}
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(e, doubleClick);
		}
		if (selected != null) {
			int w = 90;
			for (int i = 0; i < options(); i++) {
				int y = menuY + 14 + i * 13;
				if (e.x() >= menuX && e.x() < menuX + w && e.y() >= y && e.y() < y + 13) {
					click();
					if (i == 0) {
						minecraft.keyboardHandler.setClipboard(selectedName);
					} else if (minecraft.getPlayerSocialManager().isHidden(selected)) {
						minecraft.getPlayerSocialManager().showPlayer(selected);
					} else {
						minecraft.getPlayerSocialManager().hidePlayer(selected);
					}
					selected = null;
					return true;
				}
			}
			selected = null;
			return true;
		}
		for (Object[] hit : ScoreboardHud.HITS) {
			int y = (int) hit[0];
			int x = (int) hit[2];
			if (e.y() >= y && e.y() < y + 12 && e.x() >= x && e.x() < x + (int) hit[3]) {
				ScoreboardPayload.Row r = (ScoreboardPayload.Row) hit[1];
				click();
				selected = r.id();
				selectedName = r.name();
				menuX = (int) Math.min(e.x(), width - 92);
				menuY = (int) Math.min(e.y(), height - 44);
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	private void click() {
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
	}
}
