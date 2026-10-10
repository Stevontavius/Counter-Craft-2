package dev.countercraft.client.loadout;

import dev.countercraft.CounterCraft;
import dev.countercraft.net.TeamSelectPayload;
import dev.countercraft.team.TeamLock;
import dev.countercraft.team.Teams;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/**
 * CS2's team select screen (panorama teamselectmenu) on a plain dark background: Terrorists on the left half and
 * Counter-Terrorists on the right (logo, title, player count; hovering brightens the logo), and Cancel / Auto-select at
 * the bottom right. The music kit's choose-team track plays while it is open ({@link MusicKit}).
 *
 * <p>Counter-Craft (hardcore co-op): a team is locked for {@link TeamLock#LOCK_DAYS} days once joined, so every pick
 * asks "are you sure?" first (in the team's colour), and a locked team can't be changed here. On the first spawn
 * ({@link #firstPick}) the player isn't in the world until they pick: Cancel leaves for the main menu.</p>
 */
public final class TeamSelectScreen extends Screen {
	private static final Identifier T_LOGO = CounterCraft.id("textures/gui/team/t_logo.png");
	private static final Identifier CT_LOGO = CounterCraft.id("textures/gui/team/ct_logo.png");
	private static final Identifier CANCEL = CounterCraft.id("textures/gui/icons/cancel.png");
	private static final Identifier AUTO = CounterCraft.id("textures/gui/icons/refresh.png");

	/** The first pick in a Counter-Craft world (the server holds the player until then). */
	private final boolean firstPick;
	/** The team waiting for "are you sure?", or null. */
	private String confirm;

	public TeamSelectScreen() {
		this(false);
	}

	public TeamSelectScreen(boolean firstPick) {
		super(Component.literal("Select Team"));
		this.firstPick = firstPick;
	}

	private boolean hardcore() {
		return minecraft != null && minecraft.level != null && minecraft.level.getLevelData().isHardcore();
	}

	/** Days until this player may change team (Counter-Craft; 0 = free to). */
	private int daysLeft() {
		if (firstPick || !hardcore() || minecraft.player == null || !minecraft.player.hasAttached(Teams.TEAM)) {
			return 0;
		}
		Long since = minecraft.player.getAttached(TeamLock.TEAM_DAY);
		long today = minecraft.level.getOverworldClockTime() / 24000L;
		return since == null ? 0 : (int) Math.max(0, since + TeamLock.LOCK_DAYS - today);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return !firstPick && confirm == null;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE && confirm != null) {
			confirm = null; // back out of "are you sure?"
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean isPauseScreen() {
		return true; // singleplayer: the world waits while you pick (or cancel)
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// the map background is drawn below
	}

	private float k() {
		return CsUi.k(height);
	}

	/** Bottom-right action buttons (CS2 PopupButton: 28 px icon + label), right to left. */
	private float[] button(int index) {
		float k = k();
		float bw = 190 * k;
		float bh = 44 * k;
		float x = width - 32 * k - (index + 1) * (bw + 10 * k);
		float y = height - 28 * k - bh;
		return new float[] {x, y, bw, bh};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		float k = k();
		g.fill(0, 0, width, height, 0xF0101214);
		int[] count = new int[2];
		if (minecraft.level != null) {
			for (Player p : minecraft.level.players()) {
				count[Teams.CT.equals(Teams.of(p)) ? 1 : 0]++;
			}
		}
		for (int side = 0; side < 2; side++) {
			float x0 = side == 0 ? 0 : width / 2f;
			boolean hover = mouseY < height * 0.9f && mouseX >= x0 && mouseX < x0 + width / 2f;
			// the icon-title column (800 px) sits against the centre line
			float colW = 800 * k;
			float cx = side == 0 ? width / 2f - colW / 2 : width / 2f + colW / 2;
			float blockH = (30 + 100 + 20 + 60 + 30) * k;
			float y = height * 0.45f - blockH / 2;
			float logo = 100 * k;
			int shade = hover ? 0xFFFFFFFF : 0xFF808080;
			CsUi.image(g, side == 0 ? T_LOGO : CT_LOGO, cx - logo / 2, y + 30 * k, logo, logo, 256, 256, shade);
			String title = side == 0 ? "TERRORISTS" : "COUNTER-TERRORISTS";
			CsUi.text(g, title, cx, y + 150 * k, 50, height, side == 0 ? CsUi.T_COLOR : CsUi.CT_COLOR, 0);
			int n = count[side];
			CsUi.text(g, n + (n == 1 ? " Player" : " Players"), cx, y + 215 * k, 20, height, 0xFFFFFFFF, 0);
		}
		if (hardcore()) {
			int left = daysLeft();
			String note = left > 0 ? "Your team is locked for " + left + (left == 1 ? " more day" : " more days")
					: "Counter-Craft: once you join a team you can't change it for " + TeamLock.LOCK_DAYS + " days";
			CsUi.text(g, note, width / 2f, 40 * k, 20, height, left > 0 ? 0xFFE05050 : 0xFFBFBFBF, 0);
			if (firstPick) {
				CsUi.text(g, "Your run starts when you join a team", width / 2f, 70 * k, 16, height, 0xFF8A8A8A, 0);
			}
		}
		String[] labels = {"CANCEL", "AUTO-SELECT"};
		Identifier[] icons = {CANCEL, AUTO};
		for (int i = 0; i < 2; i++) {
			float[] b = button(i);
			boolean hover = CsUi.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
			g.fill((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + b[3]), hover ? 0x40FFFFFF : 0x20FFFFFF);
			float ic = 28 * k;
			CsUi.image(g, icons[i], b[0] + 10 * k, b[1] + (b[3] - ic) / 2, ic, ic, 56, 56, 0xFFFFFFFF);
			CsUi.text(g, labels[i], b[0] + 18 * k + ic, b[1] + (b[3] - 18 * k) / 2, 20, height, 0xFFFFFFFF, -1);
		}
		if (confirm != null) {
			confirmPopup(g, mouseX, mouseY);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	/** "Are you sure?" box: x, y, w, h; and its two buttons (join, back). */
	private float[] popup() {
		float k = k();
		float w = 760 * k;
		float h = 260 * k;
		return new float[] {(width - w) / 2, (height - h) / 2, w, h};
	}

	private float[] popupButton(int i) {
		float k = k();
		float[] p = popup();
		float bw = 220 * k;
		float bh = 44 * k;
		float gap = 20 * k;
		float x = p[0] + p[2] / 2 + (i == 0 ? -bw - gap / 2 : gap / 2);
		return new float[] {x, p[1] + p[3] - bh - 24 * k, bw, bh};
	}

	private void confirmPopup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		float k = k();
		int color = Teams.CT.equals(confirm) ? CsUi.CT_COLOR : CsUi.T_COLOR;
		g.fill(0, 0, width, height, 0xA0000000);
		float[] p = popup();
		g.fill((int) p[0], (int) p[1], (int) (p[0] + p[2]), (int) (p[1] + p[3]), 0xF0181A1D);
		g.fill((int) p[0], (int) p[1], (int) (p[0] + p[2]), (int) (p[1] + 3 * k), color);
		String name = Teams.CT.equals(confirm) ? "COUNTER-TERRORISTS" : "TERRORISTS";
		CsUi.text(g, "JOIN THE " + name + "?", p[0] + p[2] / 2, p[1] + 30 * k, 30, height, color, 0);
		CsUi.text(g, "Are you sure? You won't be able to change teams", p[0] + p[2] / 2, p[1] + 90 * k, 18, height, color, 0);
		CsUi.text(g, "until " + TeamLock.LOCK_DAYS + " days have passed.", p[0] + p[2] / 2, p[1] + 116 * k, 18, height, color, 0);
		String[] labels = {"JOIN", "BACK"};
		for (int i = 0; i < 2; i++) {
			float[] b = popupButton(i);
			boolean hover = CsUi.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
			int bg = i == 0 ? (hover ? 0xFF000000 | color : 0xC0000000 | (color & 0xFFFFFF)) : (hover ? 0x40FFFFFF : 0x20FFFFFF);
			g.fill((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + b[3]), bg);
			CsUi.text(g, labels[i], b[0] + b[2] / 2, b[1] + (b[3] - 18 * k) / 2, 20, height, i == 0 ? 0xFF000000 : color, 0);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(e, doubleClick);
		}
		if (confirm != null) {
			float[] yes = popupButton(0);
			float[] no = popupButton(1);
			if (CsUi.inside(e.x(), e.y(), yes[0], yes[1], yes[2], yes[3])) {
				String team = confirm;
				confirm = null;
				send(team);
			} else if (CsUi.inside(e.x(), e.y(), no[0], no[1], no[2], no[3])) {
				confirm = null;
			}
			return true; // the popup takes every click
		}
		float[] cancel = button(0);
		float[] auto = button(1);
		if (CsUi.inside(e.x(), e.y(), cancel[0], cancel[1], cancel[2], cancel[3])) {
			if (firstPick) {
				exitToTitle(); // no team, no run: back to the main menu, asked again on the next visit
			} else {
				onClose();
			}
			return true;
		}
		if (CsUi.inside(e.x(), e.y(), auto[0], auto[1], auto[2], auto[3])) {
			join(autoTeam());
			return true;
		}
		if (e.y() < height * 0.9f) {
			join(e.x() < width / 2f ? Teams.T : Teams.CT);
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	/** CS2 auto-select: the team with fewer players (T on a tie). */
	private String autoTeam() {
		int t = 0;
		int ct = 0;
		if (minecraft.level != null) {
			for (Player p : minecraft.level.players()) {
				if (Teams.CT.equals(Teams.of(p))) {
					ct++;
				} else {
					t++;
				}
			}
			if (minecraft.player != null) {
				// don't count yourself
				if (Teams.CT.equals(Teams.of(minecraft.player))) {
					ct--;
				} else {
					t--;
				}
			}
		}
		return ct < t ? Teams.CT : Teams.T;
	}

	private void join(String team) {
		if (hardcore()) {
			if (daysLeft() > 0) {
				return; // locked: the note at the top says for how long
			}
			confirm = team; // Counter-Craft: are you sure?
			return;
		}
		send(team);
	}

	private void send(String team) {
		ClientPlayNetworking.send(new TeamSelectPayload(team));
		onClose();
	}

	/** Leave the world for the title screen (as the death screen's Main Menu does). */
	private void exitToTitle() {
		if (minecraft.level != null) {
			minecraft.level.disconnect(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
		}
		minecraft.disconnectWithSavingScreen();
		minecraft.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
	}

	@Override
	public void removed() {
		// the choose-team track belongs to this screen
		Minecraft.getInstance().getMusicManager().stopPlaying();
		super.removed();
	}
}
