package dev.countercraft.client.hud;

import dev.countercraft.client.agent.AgentClient;
import dev.countercraft.client.agent.AgentPip;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.loadout.LoadoutConfig;
import dev.countercraft.client.options.CcOptions;
import dev.countercraft.gun.CsWeapon;
import dev.countercraft.team.Loadouts;
import dev.countercraft.team.Teams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A static 3D paperdoll above the health / armor / hunger readout (bottom left), in survival and adventure:
 * Counter-Craft 2 Options > HUD &amp; Gameplay > Paperdoll. "Minecraft Skin" draws your Minecraft player model (your
 * skin and armor) the way the inventory screen does; "Equipped Agent" draws your CS2 agent holding the weapon in your hand
 * with its finish, a Minecraft item in CS2's knife pose, or (empty-handed) your loadout's first rifle. A fixed
 * three-quarter view; it doesn't follow the mouse or animate.
 */
public final class PaperdollHud {
	/** Panorama px at 1080p: the doll's box and its gap above the vitals. */
	private static final float WIDTH = 150f;
	private static final float HEIGHT = 270f;
	private static final float LEFT = 12f;
	private static final float BOTTOM = 72f;
	/** The doll turned this many degrees from facing the camera. */
	private static final float TURN = 25f;
	/** Camera for the agent: 120 units in front at mid-body height, 40 degree vertical FOV; the agent turned {@link #TURN}. */
	private static final AgentPip.Scene SCENE = new AgentPip.Scene(-120f, 0f, 37f, 0f, 40f, 0f, 0f, 0f, -180f + TURN);

	private PaperdollHud() {
	}

	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		CcOptions.Paperdoll mode = CcOptions.paperdoll();
		if (mode == CcOptions.Paperdoll.DISABLED || p == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer() || p.isSpectator()) {
			return;
		}
		int h = g.guiHeight();
		float k = CsUi.k(h);
		int x0 = Math.round(LEFT * k);
		int y1 = Math.round(h - BOTTOM * k);
		int x1 = x0 + Math.round(WIDTH * k);
		int y0 = y1 - Math.round(HEIGHT * k);
		HudSpace.mark(HudSpace.Panel.PAPERDOLL, y0, y1);
		if (mode == CcOptions.Paperdoll.AGENT && AgentClient.data() != null) {
			agent(g, p, x0, y0, x1, y1);
		} else {
			minecraft(g, mc, p, x0, y0, x1, y1);
		}
	}

	private static void agent(GuiGraphicsExtractor g, LocalPlayer p, int x0, int y0, int x1, int y1) {
		String team = Teams.of(p);
		net.minecraft.world.item.ItemStack held = p.getMainHandItem();
		if (!held.isEmpty() && !(held.getItem() instanceof CsWeapon)) {
			// a Minecraft item: held as agents hold them in the world (like the team's default knife; blocks upright), in
			// CS2's knife pose
			String knife = Teams.CT.equals(team) ? "knife_ct" : "knife_t";
			var item = new net.minecraft.client.renderer.item.ItemStackRenderState();
			Minecraft.getInstance().getItemModelResolver().updateForLiving(item, held, net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, p);
			AgentPip.draw(g, AgentClient.agentFor(p), null, null, AgentPip.clipFor(team, knife), 30f, SCENE, x0, y0, x1, y1, item, held,
					held.getItem() instanceof net.minecraft.world.item.BlockItem ? null : knife, AgentClient.gloveFor(team));
			return;
		}
		String gun = held.getItem() instanceof CsWeapon w ? w.weaponId() : null;
		if (gun == null || AgentPip.clipFor(team, gun) == null) {
			String rifle = LoadoutConfig.get(team, "rifle0");
			gun = rifle == null ? "ak47" : Loadouts.modId(rifle);
		}
		var finish = p.getMainHandItem().getItem() instanceof CsWeapon w && w.weaponId().equals(gun)
				? dev.countercraft.client.skins.Skins.of(p.getMainHandItem())
				: dev.countercraft.client.skins.Skins.get(gun, LoadoutConfig.skin(team, gun));
		// the end of the buy menu clip: a still standing pose with the weapon
		AgentPip.draw(g, AgentClient.agentFor(p), gun, finish, AgentPip.clipFor(team, gun), 30f, SCENE, x0, y0, x1, y1, null,
				net.minecraft.world.item.ItemStack.EMPTY, null, AgentClient.gloveFor(team));
	}

	/** As InventoryScreen.extractEntityInInventoryFollowsMouse, with a fixed angle and the vanilla model. */
	private static void minecraft(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer p, int x0, int y0, int x1, int y1) {
		var renderer = mc.getEntityRenderDispatcher().getRenderer(p);
		EntityRenderState state = renderer.createRenderState(p, 1f);
		state.shadowPieces.clear();
		state.outlineColor = 0;
		state.nameTag = null;
		// not a tracked id: the agent renderer leaves it to Minecraft's own player model
		if (state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar) {
			avatar.id = Integer.MIN_VALUE;
		}
		if (state instanceof LivingEntityRenderState ls) {
			ls.bodyRot = 180f + TURN;
			ls.yRot = 0f;
			ls.xRot = 0f;
			ls.walkAnimationSpeed = 0f;
			ls.boundingBoxWidth /= ls.scale;
			ls.boundingBoxHeight /= ls.scale;
			ls.scale = 1f;
		}
		float size = (y1 - y0) / 2.3f;
		Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
		Quaternionf cam = new Quaternionf();
		g.entity(state, size, new Vector3f(0f, state.boundingBoxHeight / 2f + 0.0625f, 0f), rot, cam, x0, y0, x1, y1);
	}
}
