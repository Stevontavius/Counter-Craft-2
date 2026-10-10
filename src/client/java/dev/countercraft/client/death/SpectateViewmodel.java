package dev.countercraft.client.death;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.client.GunClient;
import dev.countercraft.client.viewmodel.ViewmodelAnimator;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import dev.countercraft.gun.CsWeapon;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Watching someone in first person (Another Chance): what they hold, as they see it. A CS weapon in their CS slots: its
 * CS2 viewmodel, in their agent's arms and gloves with their finish, drawn and then idling, playing the shots, reloads,
 * knife swings, throws and inspects the server sends for them (what their third-person agent plays). Anything else:
 * Minecraft's own first-person hands and items, filled from them instead of you.
 */
public final class SpectateViewmodel {
	private static int owner = -1;
	private static String gun = "";
	private static ViewmodelAnimator animator;

	private SpectateViewmodel() {
	}

	/** The player watched through their own eyes (not the third person camera), or null. */
	public static AbstractClientPlayer target() {
		Minecraft mc = Minecraft.getInstance();
		return Spectate.orbiting() || !mc.options.getCameraType().isFirstPerson() ? null : Spectate.target(mc);
	}

	/** The CS weapon they use, or "" (the CS slots aren't synced to other players: the server says, SpectatePayload). */
	private static String weaponOf(AbstractClientPlayer t) {
		var info = Spectate.info();
		boolean cs = info != null && info.target() == t.getId() && info.csMode();
		return cs && t.getMainHandItem().getItem() instanceof CsWeapon w ? w.weaponId() : "";
	}

	/**
	 * Their CS viewmodel (true: drawn, or loading: nothing this frame). False: they hold no CS weapon, so the
	 * Minecraft hands go ahead ({@link #vanillaHands}).
	 */
	public static boolean render(AbstractClientPlayer t, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		String id = weaponOf(t);
		if (id.isEmpty()) {
			owner = -1;
			gun = "";
			animator = null;
			return false;
		}
		if (t.getId() != owner || !id.equals(gun)) {
			Optional<ViewmodelAsset> a = GunClient.viewmodelIfReady(id);
			if (a == null) {
				return true; // loading in the background
			}
			if (a.isEmpty()) {
				return false; // the pack has no viewmodel for it: their item in Minecraft's hands
			}
			owner = t.getId();
			gun = id;
			animator = new ViewmodelAnimator(a.get());
			animator.play("draw", 1f);
		}
		GunClient.renderFor(t, gun, animator, poseStack, collector, light);
		return true;
	}

	/** AgentClient.action: what the watched player did, as their viewmodel plays it. */
	public static void action(int entity, String action, String attack) {
		if (entity != owner || animator == null) {
			return;
		}
		String role = switch (action) {
			case "action_reload" -> animator.has("reload") ? "reload" : null;
			case "action_inspect" -> "inspect";
			case "action_attack" -> switch (attack) {
				case "attack_knife_lightmiss" -> "slash";
				case "attack_knife_lighthit" -> "slash_hit";
				case "attack_knife_lightbackstab" -> animator.has("backstab") ? "backstab" : "slash_hit";
				case "attack_knife_heavymiss" -> "stab";
				case "attack_knife_heavyhit" -> "stab_hit";
				case "attack_knife_heavybackstab" -> animator.has("stab_backstab") ? "stab_backstab" : "stab_hit";
				case "attack_grenade_throw" -> "throw";
				default -> "shoot";
			};
			default -> null;
		};
		if (role != null && animator.has(role)) {
			animator.play(role, 1f);
		}
	}

	/** Their Minecraft hands and items: the first-person state (and the arms' skin, swing and light) from them, not you. */
	public static void vanillaHands(AbstractClientPlayer t, PlayerRenderState player, FirstPersonHandsAndItemsRenderState s, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getEntityRenderDispatcher().getRenderer(t).createRenderState(t, partial) instanceof AvatarRenderState avatar) {
			player.avatarRenderState = avatar;
		}
		boolean right = t.getMainArm() == HumanoidArm.RIGHT;
		s.attackHand = t.getCurrentSwing() == null ? InteractionHand.MAIN_HAND : t.getCurrentSwing().hand();
		s.viewXRot = t.getViewXRot(partial);
		s.viewYRot = t.getViewYRot(partial);
		s.xBob = s.viewXRot;
		s.yBob = s.viewYRot;
		s.isScoping = t.isScoping();
		s.useItemRemainingTicks = t.getUseItemRemainingTicks();
		s.handRenderSelection = FirstPersonHandsAndItemsRenderState.HandRenderSelection.RENDER_BOTH_HANDS;
		s.mainHandItem = t.getMainHandItem();
		s.offHandItem = t.getOffhandItem();
		s.mainHandHeight = s.oldMainHandHeight = 1f;
		s.offHandHeight = s.oldOffHandHeight = 1f;
		item(mc, t, s.mainHandRenderState, s.mainHandItem, right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND);
		item(mc, t, s.offHandRenderState, s.offHandItem, right ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND);
		s.mainHandUseDuration = s.mainHandItem.getUseDuration(t);
		s.offHandUseDuration = s.offHandItem.getUseDuration(t);
		s.mainHandChargeDuration = CrossbowItem.getChargeDuration(s.mainHandItem, t);
		s.offHandChargeDuration = CrossbowItem.getChargeDuration(s.offHandItem, t);
		s.mainHandSwapScale = mc.getItemModelResolver().swapAnimationScale(s.mainHandItem);
		s.offHandSwapScale = mc.getItemModelResolver().swapAnimationScale(s.offHandItem);
		s.hasMainHandMapData = false;
		s.hasOffHandMapData = false;
	}

	private static void item(Minecraft mc, AbstractClientPlayer t, net.minecraft.client.renderer.item.ItemStackRenderState state, ItemStack stack,
			ItemDisplayContext context) {
		state.clear();
		mc.getItemModelResolver().updateForTopItem(state, stack, context, t.level(), t, t.getId() + context.ordinal());
	}
}
