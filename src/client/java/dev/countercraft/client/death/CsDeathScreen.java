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
 * Replaces Minecraft's death screen ({@link DeathCam}).
 * <ul>
 * <li>While the death cam plays: no red wash, and the death note low in the middle of the screen. It's CS2's death
 * panel (huddeathpanel.vxml / .vcss), square cornered:
 * <ul>
 * <li>a red edge, the killer's face (a player's skin face, a mob's own head drawn from its model) with a bar of the
 * health they had left, their name and that health;</li>
 * <li>a red "killed you with their ..." line, the damage both ways this life with hit counts;</li>
 * <li>a panel with the weapon, and CS2's headshot icon for a headshot.</li>
 * </ul>
 * Killed by the world (or yourself): your own face and name, and how you died.</li>
 * <li>Then the world fades to solid black, and the death screen shows on the black: YOU DIED (GAME OVER in hardcore)
 * and the days survived, the note gliding up under them, and Respawn (Spectate World in hardcore) / Main Menu.</li>
 * </ul>
 */
public final class CsDeathScreen extends Screen {
	private static final int NAME = 0xEDEDED;
	private static final int DESC = 0xE0473A;
	private static final int GREY = 0xA0A0A0;
	private static final int ACCENT = 0xD8352A;
	private static final int PANEL = 0x0E0F12;
	/** The note's parts (1080p pixels): the main card, the weapon panel, the top block, a damage row, the face. */
	private static final float NOTE_W = 540;
	private static final float WEAPON_W = 160;
	private static final float TOP_H = 88;
	private static final float ROW_H = 30;
	private static final float AVATAR = 66;
	/** The death screen's entrance on the black: the title fading in, the note gliding up (seconds). */
	private static final double ENTER = 0.4;

	private final DeathScreen vanilla;
	private final boolean hardcore;
	private final Component cause;
	private final long openedAt = System.nanoTime();
	private boolean buttons;
	private long buttonsAt;
	/** Respawn / Main Menu: shown once the death screen's entrance is over. */
	private final java.util.List<Button> shownLater = new java.util.ArrayList<>();
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

	/** The player who died (a respawn makes a new one), and whether this screen has seen them dead (see DeathCam.tick). */
	private net.minecraft.world.entity.Entity died;
	private boolean seenDead;

	@Override
	public void tick() {
		var p = minecraft.player;
		died = died == null ? p : died;
		seenDead |= p != null && p == died && p.isDeadOrDying();
		if (p != null && (p != died || seenDead && p.isAlive() && !p.isDeadOrDying())) {
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
		boolean again = buttonsAt != 0; // (a resize lays the buttons out again: no second entrance)
		buttons = true;
		if (anotherChance() && minecraft.player != null) {
			minecraft.player.respawn(); // straight on to spectating the players still alive, like CS2
			return;
		}
		if (!again) {
			buttonsAt = System.nanoTime();
		}
		Layout l = layout(DeathCam.info(), CsUi.k(height));
		int bw = 150;
		int gap = 10;
		shownLater.clear();
		shownLater.add(addRenderableWidget(Button.builder(Component.translatable(hardcore ? "deathScreen.spectate" : "deathScreen.respawn"), b -> {
			b.active = false;
			if (minecraft.player != null) {
				minecraft.player.respawn();
			}
		}).bounds(width / 2 - bw - gap / 2, l.buttonsY(), bw, 20).build()));
		shownLater.add(addRenderableWidget(Button.builder(Component.literal("Main Menu"), b -> ((DeathScreenAccessor) vanilla).countercraft$handleExitToTitleScreen())
				.bounds(width / 2 + gap / 2, l.buttonsY(), bw, 20).build()));
		boolean in = (System.nanoTime() - buttonsAt) / 1e9 >= ENTER;
		shownLater.forEach(b -> b.visible = in);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// the world shows through (the death cam), then fades to solid black (CS2's phase 2); the death screen is on black
		float black = DeathCam.active() ? DeathCam.blackout() : 1f;
		if (black > 0) {
			g.fill(0, 0, width, height, Math.round(black * 255) << 24);
		}
	}

	/** Where the death screen puts its parts (GUI pixels): the title, its red rule, the days survived, the note, the buttons. */
	private record Layout(float titleY, float ruleY, float subY, float noteY, int buttonsY) {
	}

	private Layout layout(DeathInfoPayload info, float k) {
		float noteH = noteHeight(info) * k;
		float total = (64 + 16 + 2 + 14 + 20 + 44 + 30) * k + noteH + 20;
		float top = Math.max(6, (height - total) / 2);
		float ruleY = top + (64 + 16) * k;
		float subY = ruleY + (2 + 14) * k;
		float noteY = subY + (20 + 44) * k;
		return new Layout(top, ruleY, subY, noteY, Math.min(height - 24, Math.round(noteY + noteH + 30 * k)));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		DeathInfoPayload info = DeathCam.info();
		boolean chance = anotherChance();
		float k = CsUi.k(height);
		double shown = buttons && !chance ? (System.nanoTime() - buttonsAt) / 1e9 : -1;
		if (hardcore && !chance && shown < 0) {
			gameOver(g, info, 1f - DeathCam.blackout()); // over the world; the death screen says it again on the black
		}
		// the note: low in the middle over the world (sliding up as it comes in), then up under the title on the black
		float camY = height * 0.68f;
		float y;
		float alpha;
		if (shown < 0) {
			double t = DeathCam.active() ? DeathCam.seconds() : (System.nanoTime() - openedAt) / 1e9;
			alpha = (float) Math.min(1, t / 0.25);
			y = camY + (1 - alpha) * 12 * k;
		} else {
			Layout l = layout(info, k);
			float m = (float) smooth(shown / ENTER);
			y = camY + (l.noteY() - camY) * m;
			alpha = 1f;
			int a = Math.round((float) Math.min(1, shown / ENTER) * 255) << 24;
			float cx = width / 2f;
			CsUi.text(g, hardcore ? "GAME OVER" : "YOU DIED", cx, l.titleY(), 64, height, a | 0xFFFFFF, 0);
			float rw = 140 * k * m;
			g.fill(Math.round(cx - rw / 2), Math.round(l.ruleY()), Math.round(cx + rw / 2), Math.round(l.ruleY() + Math.max(1, 2 * k)), a | ACCENT);
			if (info != null) {
				int days = info.daysSurvived();
				CsUi.text(g, "You survived " + days + (days == 1 ? " day" : " days"), cx, l.subY(), 20, height, a | GREY, 0);
			}
		}
		float w = noteWidth(info) * k;
		note(g, info, (width - w) / 2, y, alpha);
		if (shown >= ENTER) {
			shownLater.forEach(b -> b.visible = true);
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	private static boolean killed(DeathInfoPayload info) {
		return info != null && "killed".equals(info.cause());
	}

	/** The note's size, 1080p pixels. */
	private static float noteWidth(DeathInfoPayload info) {
		return NOTE_W + (killed(info) ? WEAPON_W : 0);
	}

	private static float noteHeight(DeathInfoPayload info) {
		return TOP_H + (killed(info) ? 2 * ROW_H + 10 : 0);
	}

	private static int col(int rgb, float alpha) {
		return Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24 | rgb;
	}

	/** The death note at (x, y), {@code alpha} faded. */
	private void note(GuiGraphicsExtractor g, DeathInfoPayload info, float x, float y, float alpha) {
		float k = CsUi.k(height);
		boolean killed = killed(info);
		float cw = NOTE_W * k;
		float iw = killed ? WEAPON_W * k : 0;
		float w = cw + iw;
		float h = noteHeight(info) * k;
		int px = Math.max(1, Math.round(k));
		// the card: darker at the left, with a red edge
		int slices = 24;
		for (int i = 0; i < slices; i++) {
			float t = i / (slices - 1f);
			g.fill(Math.round(x + w * i / slices), Math.round(y), Math.round(x + w * (i + 1) / slices), Math.round(y + h),
					col(PANEL, alpha * (0.86f - 0.2f * t)));
		}
		g.fill(Math.round(x), Math.round(y), Math.round(x + 4 * k), Math.round(y + h), col(ACCENT, alpha));
		g.fill(Math.round(x + 4 * k), Math.round(y), Math.round(x + w), Math.round(y) + px, col(0xFFFFFF, alpha * 0.12f)); // a faint top edge
		// the face, framed, and (killed) a bar of the health the killer had left under it
		float ax = x + 18 * k;
		float ay = y + 11 * k;
		float as = AVATAR * k;
		g.fill(Math.round(ax) - px, Math.round(ay) - px, Math.round(ax + as) + px, Math.round(ay + as) + px, col(0xFFFFFF, alpha * 0.22f));
		g.fill(Math.round(ax), Math.round(ay), Math.round(ax + as), Math.round(ay + as), col(0x000000, alpha * 0.6f));
		if (alpha > 0.6f) {
			try {
				face(g, info, Math.round(ax), Math.round(ay), Math.round(as));
			} catch (RuntimeException e) {
				// an entity that can't be drawn outside the world: no face rather than a crash
				if (!faceFailed) {
					faceFailed = true;
					dev.countercraft.CounterCraft.LOG.warn("[countercraft] death panel face not drawn: {}", e.toString());
				}
			}
		}
		float tx = ax + as + 14 * k;
		float textW = x + cw - tx - 14 * k;
		if (killed) {
			float health = Math.max(0, Math.min(1, info.killerHealth()));
			float by = ay + as + 4 * k;
			g.fill(Math.round(ax), Math.round(by), Math.round(ax + as), Math.round(by + 3 * k), col(0x000000, alpha * 0.7f));
			g.fill(Math.round(ax), Math.round(by), Math.round(ax + as * health), Math.round(by + 3 * k), col(healthColor(health), alpha));
			String hp = Math.round(health * 100) + " HP";
			float hpW = CsUi.textWidth(hp, 16, height);
			CsUi.text(g, hp, x + cw - 14 * k, y + 19 * k, 16, height, col(GREY, alpha), 1);
			CsUi.text(g, CsUi.fit(info.killerName().getString(), textW - hpW - 10 * k, 24, height), tx, y + 15 * k, 24, height, col(NAME, alpha), -1);
			CsUi.text(g, CsUi.fit(description(info), textW, 18, height), tx, y + 51 * k, 18, height, col(DESC, alpha), -1);
			// the damage both ways this life
			float ry = y + TOP_H * k;
			g.fill(Math.round(x + 18 * k), Math.round(ry), Math.round(x + cw - 14 * k), Math.round(ry) + px, col(0xFFFFFF, alpha * 0.14f));
			damageRow(g, "Damage taken:", info.damageTaken(), info.hitsTaken(), x + 18 * k, ry + 9 * k, alpha);
			damageRow(g, "Damage given:", info.damageGiven(), info.hitsGiven(), x + 18 * k, ry + (9 + ROW_H) * k, alpha);
			// the weapon, and the headshot icon under it
			float ix = x + cw;
			g.fill(Math.round(ix), Math.round(y + 10 * k), Math.round(ix) + px, Math.round(y + h - 10 * k), col(0xFFFFFF, alpha * 0.14f));
			// (a headshot: the weapon in the upper part, CS2's headshot icon centred under it)
			int[] hs = info.headshot() ? dev.countercraft.client.hud.KillFeed.iconSize("icon_headshot") : null;
			float area = h - 24 * k;
			float top = hs != null ? area * 0.62f : area;
			weapon(g, info, ix + 14 * k, y + 12 * k, iw - 28 * k, top, alpha);
			if (hs != null) {
				float hsH = 34 * k;
				float hw = hsH * hs[0] / hs[1];
				CsUi.image(g, dev.countercraft.CounterCraft.id("textures/hud/killfeed/icon_headshot.png"), ix + (iw - hw) / 2,
						y + 12 * k + top + (area - top - hsH) / 2, hw, hsH, hs[0], hs[1], col(0xFFFFFF, alpha));
			}
			return;
		}
		// killed by the world or yourself: your name and how you died (the death message, your name taken off its front)
		String name = minecraft.player != null ? minecraft.player.getName().getString() : "You";
		String desc = cause == null ? "" : cause.getString();
		if (desc.startsWith(name + " ")) {
			desc = desc.substring(name.length() + 1);
		}
		CsUi.text(g, CsUi.fit(name, textW, 24, height), tx, y + 15 * k, 24, height, col(NAME, alpha), -1);
		if (CsUi.textWidth(desc, 18, height) <= textW) {
			CsUi.text(g, desc, tx, y + 51 * k, 18, height, col(DESC, alpha), -1);
			return;
		}
		// a long message: two lines
		String[] words = desc.split(" ");
		StringBuilder first = new StringBuilder();
		int i = 0;
		while (i < words.length && CsUi.textWidth(first + words[i] + " ", 18, height) <= textW) {
			first.append(words[i++]).append(' ');
		}
		StringBuilder rest = new StringBuilder();
		for (; i < words.length; i++) {
			rest.append(words[i]).append(' ');
		}
		CsUi.text(g, first.toString().trim(), tx, y + 43 * k, 18, height, col(DESC, alpha), -1);
		CsUi.text(g, CsUi.fit(rest.toString().trim(), textW, 18, height), tx, y + 63 * k, 18, height, col(DESC, alpha), -1);
	}

	/** The killer's health bar: green, yellow under half, red under a fifth. */
	private static int healthColor(float health) {
		return health > 0.5f ? 0x5FD35F : health > 0.2f ? 0xE8C547 : 0xE04040;
	}

	/** Counter-Craft (hardcore): CS2's round lost banner ({@link dev.countercraft.client.hud.WinPanel}) saying GAME OVER. */
	private void gameOver(GuiGraphicsExtractor g, DeathInfoPayload info, float alpha) {
		if (alpha <= 0f) {
			return;
		}
		double t = DeathCam.active() ? DeathCam.seconds() - 0.3 : 10;
		int days = info == null ? 0 : info.daysSurvived();
		dev.countercraft.client.hud.WinPanel.gameOver(g, width, height, t, "You survived " + days + (days == 1 ? " day" : " days"), alpha);
	}

	/** CS2's DeathPanel_DamageTaken / _Given (grey label, white numbers). */
	private void damageRow(GuiGraphicsExtractor g, String label, int damage, int hits, float x, float y, float alpha) {
		float cx = x;
		CsUi.text(g, label + "  ", cx, y, 18, height, col(GREY, alpha), -1);
		cx += CsUi.textWidth(label + "  ", 18, height);
		String dmg = Integer.toString(damage);
		CsUi.text(g, dmg, cx, y, 18, height, col(0xFFFFFF, alpha), -1);
		cx += CsUi.textWidth(dmg, 18, height);
		CsUi.text(g, "  in ", cx, y, 18, height, col(GREY, alpha), -1);
		cx += CsUi.textWidth("  in ", 18, height);
		CsUi.text(g, hits + (hits == 1 ? " hit" : " hits"), cx, y, 18, height, col(0xFFFFFF, alpha), -1);
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

	private void weapon(GuiGraphicsExtractor g, DeathInfoPayload info, float x, float y, float w, float h, float alpha) {
		if (!info.csWeapon().isEmpty() && CsUi.iconSize(csItem(info.csWeapon())) != null) {
			CsUi.iconIn(g, csItem(info.csWeapon()), x, y, w, h, 0.6f, col(0xFFFFFF, alpha));
			return;
		}
		if (!info.weapon().isEmpty() && alpha > 0.6f) {
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
