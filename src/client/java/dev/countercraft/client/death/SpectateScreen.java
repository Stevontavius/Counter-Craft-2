package dev.countercraft.client.death;

import com.mojang.blaze3d.platform.InputConstants;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.net.ChancePayload;
import dev.countercraft.net.SpectatePayload;
import dev.countercraft.team.AnotherChance;
import dev.countercraft.team.Teams;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * Another Chance with the cursor free ({@link Spectate}): while waiting, the players alive to pick who to watch; once the
 * team has made it to dawn, where to come back: "Respawn on Player" (then which one) or "Respawn at Bed" ("at Spawn"
 * without a bed), at home by itself after {@link AnotherChance#AUTO_RESPAWN_SECONDS}. The world keeps playing behind it. N
 * or Esc closes it; Space and X work as outside.
 */
public final class SpectateScreen extends net.minecraft.client.gui.screens.Screen {
	private static final int ACCENT = 0xFF8FD18F;
	private boolean pickPlayer;

	public SpectateScreen() {
		super(Component.literal("Spectating"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private boolean ready() {
		return minecraft.player != null && AnotherChance.of(minecraft.player).state() == AnotherChance.READY;
	}

	private List<SpectatePayload.Mate> mates() {
		SpectatePayload i = Spectate.info();
		return i == null ? List.of() : i.mates();
	}

	@Override
	public void tick() {
		if (!Spectate.locked(minecraft)) {
			onClose(); // respawned, or the run is over
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0x30000000);
	}

	// layout (CS2 1080p px): a 520 px column centred, below where the round end banner shows (y 190-270)
	private float k() {
		return CsUi.k(height);
	}

	private float colX() {
		return (width - 520 * k()) / 2;
	}

	private float top() {
		return 300 * k();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		float k = k();
		float x = colX();
		float w = 520 * k;
		float y = top();
		boolean ready = ready();
		CsUi.text(g, ready ? "ANOTHER CHANCE" : "SPECTATE", width / 2f, y, 40, height, ready ? ACCENT : 0xFFFFFFFF, 0);
		CsUi.text(g, ready ? "Your team made it through the night. Choose where to come back." : "Choose a player to watch.", width / 2f,
				y + 46 * k, 18, height, 0xFFCCCCCC, 0);
		if (ready) {
			CsUi.text(g, home() + " in " + Spectate.autoRespawnIn() + " s", width / 2f, y + 68 * k, 15, height, ACCENT, 0);
		}
		y += 96 * k;
		if (ready && !pickPlayer) {
			option(g, "Respawn on Player", "Next to a teammate who's still alive", x, y, w, mouseX, mouseY, "on");
			option(g, home(), homeHint(), x, y + 70 * k, w, mouseX, mouseY, "home");
		} else {
			if (ready) {
				CsUi.text(g, "RESPAWN ON", x, y - 22 * k, 16, height, ACCENT, -1);
			}
			List<SpectatePayload.Mate> mates = mates();
			if (mates.isEmpty()) {
				CsUi.text(g, "Nobody is alive.", width / 2f, y + 10 * k, 18, height, 0xFFCCCCCC, 0);
			}
			for (int i = 0; i < mates.size(); i++) {
				mate(g, mates.get(i), x, y + i * 58 * k, w, mouseX, mouseY);
			}
		}
		String keys = "[" + Spectate.key(Spectate.CURSOR) + "] / [Esc] Close   [" + Spectate.key(minecraft.options.keyJump) + "] "
				+ (Spectate.thirdPersonOn() ? "First person" : "Third person") + "   [" + Spectate.key(Spectate.XRAY) + "] X-Ray " + (Spectate.xrayOn() ? "off" : "on");
		CsUi.text(g, keys, width / 2f, height - 140 * k, 15, height, 0xC0FFFFFF, 0);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private String home() {
		return AnotherChance.of(minecraft.player).bed() ? "Respawn at Bed" : "Respawn at Spawn";
	}

	private String homeHint() {
		return AnotherChance.of(minecraft.player).bed() ? "Your bed or respawn anchor" : "The world spawn (you have no bed)";
	}

	private void option(GuiGraphicsExtractor g, String title, String hint, float x, float y, float w, int mx, int my, String key) {
		float k = k();
		float h = 60 * k;
		boolean on = CsUi.inside(mx, my, x, y, w, h);
		CsUi.card(g, x, y, w, h, CsUi.hover(key, on), ACCENT & 0xFFFFFF, false);
		CsUi.text(g, title, x + 16 * k, y + 10 * k, 24, height, 0xFFFFFFFF, -1);
		CsUi.text(g, hint, x + 16 * k, y + 36 * k, 15, height, 0xFFAAAAAA, -1);
	}

	private void mate(GuiGraphicsExtractor g, SpectatePayload.Mate m, float x, float y, float w, int mx, int my) {
		float k = k();
		float h = 50 * k;
		boolean on = CsUi.inside(mx, my, x, y, w, h);
		int team = Teams.CT.equals(m.team()) ? Teams.CT_COLOR : Teams.T_COLOR;
		CsUi.card(g, x, y, w, h, CsUi.hover(m.id(), on), team, false);
		g.fill((int) x, (int) y, (int) (x + 3 * k), (int) (y + h), 0xFF000000 | team);
		PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(m.id());
		if (info != null) {
			PlayerFaceExtractor.extractRenderState(g, info.getSkin(), (int) (x + 10 * k), (int) (y + 7 * k), (int) (36 * k));
		}
		CsUi.text(g, m.name(), x + 58 * k, y + 15 * k, 22, height, 0xFF000000 | team, -1);
		boolean watching = Spectate.target(minecraft) != null && Spectate.target(minecraft).getUUID().equals(m.id());
		if (watching) {
			CsUi.text(g, "WATCHING", x + w - 12 * k, y + 18 * k, 14, height, 0xB0FFFFFF, 1);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(e, doubleClick);
		}
		float k = k();
		float x = colX();
		float w = 520 * k;
		float y = top() + 96 * k;
		if (ready() && !pickPlayer) {
			if (CsUi.inside(e.x(), e.y(), x, y, w, 60 * k)) {
				pickPlayer = true;
				return true;
			}
			if (CsUi.inside(e.x(), e.y(), x, y + 70 * k, w, 60 * k)) {
				Spectate.send(ChancePayload.RESPAWN_HOME, ChancePayload.NOBODY);
				return true;
			}
			return super.mouseClicked(e, doubleClick);
		}
		List<SpectatePayload.Mate> mates = mates();
		for (int i = 0; i < mates.size(); i++) {
			if (CsUi.inside(e.x(), e.y(), x, y + i * 58 * k, w, 50 * k)) {
				Spectate.send(ready() ? ChancePayload.RESPAWN_ON : ChancePayload.SPECTATE, mates.get(i).id());
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_ESCAPE && pickPlayer) {
			pickPlayer = false; // back to the two choices
			return true;
		}
		if (Spectate.CURSOR.matches(e)) {
			onClose();
			return true;
		}
		if (minecraft.options.keyJump.matches(e)) {
			Spectate.toggleThirdPerson();
			return true;
		}
		if (Spectate.XRAY.matches(e)) {
			Spectate.toggleXray();
			return true;
		}
		return super.keyPressed(e);
	}
}
