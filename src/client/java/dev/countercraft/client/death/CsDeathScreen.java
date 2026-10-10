package dev.countercraft.client.death;

import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.mixin.DeathScreenAccessor;
import dev.countercraft.net.DeathInfoPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Replaces Minecraft's death screen while CS2's death cam plays ({@link DeathCam}): no red wash, CS2's death panel at the
 * top (huddeathpanel.vxml / .vcss: 410 px card with the killer's face, name, a red "killed you with their ..." line,
 * damage taken / given this life with hit counts, and a 150 px weapon panel), then, once the sequence has played,
 * Respawn (Spectate World in hardcore) and Main Menu. The killer's face: a player's skin face, a mob's own head drawn
 * from its model.
 */
public final class CsDeathScreen extends Screen {
	private static final int NAME = 0xFFCCCCCC;
	private static final int DESC = 0xFFC93121;
	private static final int GREY = 0xFFA0A0A0;
	private static final int LINE = 0x40252525;
	/** csgostyles negativeColor (the lost round banner's colour). */
	private static final int NEGATIVE = 0xFFDB4437;

	private final DeathScreen vanilla;
	private final boolean hardcore;
	private final Component cause;
	private boolean buttons;
	private long buttonsAt;
	private Entity dummyKiller;
	private boolean faceFailed;

	public CsDeathScreen(DeathScreen vanilla) {
		super(Component.translatable("deathScreen.title"));
		this.vanilla = vanilla;
		this.hardcore = ((DeathScreenAccessor) vanilla).countercraft$hardcore();
		this.cause = ((DeathScreenAccessor) vanilla).countercraft$causeOfDeath();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	protected void init() {
		buttons = false;
		addButtonsIfDone();
	}

	@Override
	public void tick() {
		if (minecraft.player != null && minecraft.player.isAlive() && !minecraft.player.isDeadOrDying()) {
			minecraft.gui.setScreen(null); // respawned
			return;
		}
		addButtonsIfDone();
	}

	/** Counter-Craft's Another Chance: the run goes on without you for now (no GAME OVER; spectate once the cam is done). */
	private boolean anotherChance() {
		DeathInfoPayload info = DeathCam.info();
		return hardcore && (info != null ? info.anotherChance()
				: minecraft != null && minecraft.player != null && dev.countercraft.team.AnotherChance.waiting(minecraft.player));
	}

	private void addButtonsIfDone() {
		if (buttons || !DeathCam.finished()) {
			return;
		}
		buttons = true;
		if (anotherChance() && minecraft.player != null) {
			minecraft.player.respawn(); // straight on to spectating the players still alive, like CS2
			return;
		}
		buttonsAt = System.nanoTime();
		int y = height - 58;
		addRenderableWidget(Button.builder(Component.translatable(hardcore ? "deathScreen.spectate" : "deathScreen.respawn"), b -> {
			b.active = false;
			if (minecraft.player != null) {
				minecraft.player.respawn();
			}
		}).bounds(16, y, 160, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Main Menu"), b -> ((DeathScreenAccessor) vanilla).countercraft$handleExitToTitleScreen())
				.bounds(16, y + 24, 160, 20).build());
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// the world shows through (the death cam); CS2's phase 2 fades it towards black at the end
		float black = DeathCam.blackout();
		if (!DeathCam.active()) {
			black = 0.6f;
		}
		if (black > 0) {
			g.fill(0, 0, width, height, (int) (black * 255) << 24);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		DeathInfoPayload info = DeathCam.info();
		boolean chance = anotherChance();
		if (hardcore && !chance) {
			gameOver(g, info);
		}
		panel(g, info);
		if (buttons && !chance) {
			float a = (float) Math.min(1, (System.nanoTime() - buttonsAt) / 3e8);
			int c = (int) (a * 255) << 24 | 0xFFFFFF;
			g.text(font, hardcore ? Component.translatable("deathScreen.title.hardcore") : Component.translatable("deathScreen.title"), 16, height - 72, c, true);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private void panel(GuiGraphicsExtractor g, DeathInfoPayload info) {
		float k = CsUi.k(height);
		boolean killed = info != null && "killed".equals(info.cause());
		boolean rows = killed;
		float cw = 520 * k;
		float iw = killed ? 150 * k : 0;
		float w = cw + iw;
		float x = (width - w) / 2;
		float y = height * 0.6f;
		float rowH = 30 * k;
		float h = 80 * k + (rows ? 2 * rowH + 6 * k : 0);
		// CS2: rgba(0,0,0,0.5) card (with a world blur in CS2)
		g.fill((int) x, (int) y, (int) (x + w), (int) (y + h), 0x80000000);
		// avatar
		float ax = x + 8 * k;
		float ay = y + 7 * k;
		g.fill((int) ax, (int) ay, (int) (ax + 66 * k), (int) (ay + 66 * k), 0x60000000);
		try {
			face(g, info, (int) ax, (int) ay, (int) (66 * k));
		} catch (RuntimeException e) {
			// an entity that can't be drawn outside the world: no face rather than a crash
			if (!faceFailed) {
				faceFailed = true;
				dev.countercraft.CounterCraft.LOG.warn("[countercraft] death panel face not drawn: {}", e.toString());
			}
		}
		float tx = ax + 66 * k + 12 * k;
		float textW = x + cw - tx - 8 * k;
		String name;
		String desc;
		if (killed) {
			name = info.killerName().getString();
			desc = description(info);
		} else if (info != null && "self".equals(info.cause())) {
			name = "YOU KILLED YOURSELF";
			desc = cause == null ? "" : cause.getString();
		} else {
			name = "YOU DIED";
			desc = cause == null ? "" : cause.getString();
		}
		if (!killed && desc.length() > 0 && CsUi.textWidth(desc, 18, height) > textW - 8 * k) {
			// two lines for a long death message
			String[] words = desc.split(" ");
			StringBuilder first = new StringBuilder();
			int i = 0;
			while (i < words.length && CsUi.textWidth(first + words[i] + " ", 18, height) <= textW - 8 * k) {
				first.append(words[i++]).append(' ');
			}
			StringBuilder rest = new StringBuilder();
			for (; i < words.length; i++) {
				rest.append(words[i]).append(' ');
			}
			CsUi.text(g, CsUi.fit(name, textW, 24, height), tx, y + 10 * k, 24, height, NAME, -1);
			CsUi.text(g, first.toString().trim(), tx + 8 * k, y + 38 * k, 18, height, DESC, -1);
			CsUi.text(g, CsUi.fit(rest.toString().trim(), textW - 8 * k, 18, height), tx + 8 * k, y + 56 * k, 18, height, DESC, -1);
			return;
		}
		CsUi.text(g, CsUi.fit(name, textW, 24, height), tx, y + 16 * k, 24, height, NAME, -1);
		CsUi.text(g, CsUi.fit(desc, textW, 18, height), tx + 8 * k, y + 48 * k, 18, height, DESC, -1);
		if (rows) {
			float ry = y + 80 * k;
			g.fill((int) x, (int) ry, (int) (x + cw), (int) (ry + 2 * k), LINE);
			damageRow(g, "Damage taken:", info.damageTaken(), info.hitsTaken(), x + 10 * k, ry + 7 * k);
			ry += rowH;
			damageRow(g, "Damage given:", info.damageGiven(), info.hitsGiven(), x + 10 * k, ry + 7 * k);
		}
		if (killed) {
			float ix = x + cw;
			g.fill((int) ix, (int) y, (int) (ix + 2 * k), (int) (y + h), LINE);
			weapon(g, info, ix + 10 * k, y + 8 * k, iw - 20 * k, h - 16 * k);
		}
	}

	/** Counter-Craft (hardcore): CS2's round lost banner ({@link dev.countercraft.client.hud.WinPanel}) saying GAME OVER. */
	private void gameOver(GuiGraphicsExtractor g, DeathInfoPayload info) {
		double t = DeathCam.active() ? DeathCam.seconds() - 0.3 : 10;
		int days = info == null ? 0 : info.daysSurvived();
		dev.countercraft.client.hud.WinPanel.draw(g, width, height, t, "GAME OVER", "You survived " + days + (days == 1 ? " day" : " days"), NEGATIVE,
				0xE0200202);
	}

	/** CS2's DeathPanel_DamageTaken / _Given (grey label, white numbers). */
	private void damageRow(GuiGraphicsExtractor g, String label, int damage, int hits, float x, float y) {
		float k = CsUi.k(height);
		float cx = x;
		CsUi.text(g, label + "  ", cx, y, 18, height, GREY, -1);
		cx += CsUi.textWidth(label + "  ", 18, height);
		String dmg = Integer.toString(damage);
		CsUi.text(g, dmg, cx, y, 18, height, 0xFFFFFFFF, -1);
		cx += CsUi.textWidth(dmg, 18, height);
		CsUi.text(g, "  in ", cx, y, 18, height, GREY, -1);
		cx += CsUi.textWidth("  in ", 18, height);
		CsUi.text(g, hits + (hits == 1 ? " hit" : " hits"), cx, y, 18, height, 0xFFFFFFFF, -1);
	}

	/** "killed you with their AK-47" (CS2's DeathPanel_Killer1_KillerWeapon), or "slew you" without a weapon. */
	private static String description(DeathInfoPayload info) {
		String weapon = weaponName(info);
		return weapon.isEmpty() ? "slew you" : "killed you with their " + weapon;
	}

	private static String weaponName(DeathInfoPayload info) {
		if (!info.csWeapon().isEmpty()) {
			return CsUi.name(csItem(info.csWeapon()));
		}
		return info.weapon().isEmpty() ? "" : info.weapon().getHoverName().getString();
	}

	/** Kill feed weapon id -> buy menu item id ("usp_silencer_off" -> "usp_silencer"). */
	private static String csItem(String cs) {
		String id = cs.endsWith("_off") ? cs.substring(0, cs.length() - 4) : cs;
		return id.startsWith("knife") || id.equals("bayonet") || id.equals("hegrenade") || id.equals("inferno") ? id : "weapon_" + id;
	}

	private void weapon(GuiGraphicsExtractor g, DeathInfoPayload info, float x, float y, float w, float h) {
		if (!info.csWeapon().isEmpty() && CsUi.iconSize(csItem(info.csWeapon())) != null) {
			CsUi.iconIn(g, csItem(info.csWeapon()), x, y, w, h, 0.6f, 0xFFFFFFFF);
			return;
		}
		if (!info.weapon().isEmpty()) {
			float size = Math.min(w, h) * 0.8f;
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x + (w - size) / 2, y + (h - size) / 2);
			pose.scale(size / 16f, size / 16f);
			g.item(info.weapon(), 0, 0);
			pose.popMatrix();
		}
	}

	/** The killer's face: a player's skin face, a mob's own head (its model, framed on the head). */
	private void face(GuiGraphicsExtractor g, DeathInfoPayload info, int x, int y, int size) {
		if (minecraft.level == null) {
			return;
		}
		if (info == null || info.killerId() < 0) {
			if (minecraft.player != null) {
				PlayerFaceExtractor.extractRenderState(g, minecraft.player.getSkin(), x, y, size);
			}
			return;
		}
		Entity killer = minecraft.level.getEntity(info.killerId());
		if (killer instanceof AbstractClientPlayer p) {
			PlayerFaceExtractor.extractRenderState(g, p.getSkin(), x, y, size);
			return;
		}
		if (!(killer instanceof LivingEntity)) {
			killer = dummy(info.killerType());
		}
		if (!(killer instanceof LivingEntity le)) {
			return;
		}
		var renderer = minecraft.getEntityRenderDispatcher().getRenderer(le);
		EntityRenderState state = renderer.createRenderState(le, 1f);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		float head = le.getEyeHeight();
		if (state instanceof LivingEntityRenderState ls) {
			ls.bodyRot = 180f;
			ls.yRot = 0f;
			ls.xRot = 0f;
			ls.walkAnimationSpeed = 0f;
			ls.deathTime = 0f;
			ls.boundingBoxWidth /= ls.scale;
			ls.boundingBoxHeight /= ls.scale;
			head /= ls.scale;
			ls.scale = 1f;
		}
		// frame the head: about 0.7 blocks across (bigger mobs: a bigger frame)
		float span = Math.max(0.7f, Math.min(3f, le.getBbWidth() * 0.9f));
		float scale = size / span;
		Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
		g.entity(state, scale, new Vector3f(0f, head, 0f), rot, new Quaternionf(), x, y, x + size, y + size);
	}

	private Entity dummy(String type) {
		if (dummyKiller == null && minecraft.level != null && !type.isEmpty()) {
			var t = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(type));
			dummyKiller = t.map(et -> (Entity) et.create(minecraft.level, EntitySpawnReason.LOAD)).orElse(null);
			if (dummyKiller != null) {
				dummyKiller.setId(Integer.MIN_VALUE + 7); // never in the world, but rendering needs an id
			}
		}
		return dummyKiller;
	}
}
