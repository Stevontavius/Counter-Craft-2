package dev.countercraft.team;

import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import dev.countercraft.gun.DroppedWeaponEntity;
import dev.countercraft.gun.GrenadeEntity;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import dev.countercraft.gun.Guns;
import dev.countercraft.gun.KnifeItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * CS2 weapon dropping and picking up (CS2 items only; Minecraft items drop the Minecraft way).
 *
 * <ul>
 * <li>Drop (G, or Q on a CS item): Source's CCSPlayer::CSWeaponDrop: the weapon leaves the right hand (+10 units ahead)
 * and is tossed with VecCheckToss (height ratio 0.2, sv_gravity 800) to land at the body centre + 64 units along the
 * view; you switch to your best remaining weapon.</li>
 * <li>Death: CS2 competitive (gamemode_competitive.cfg): mp_death_drop_gun 1 (the best gun: primary, else pistol),
 * mp_death_drop_grenade 2 (the grenade in hand, else the best), mp_death_drop_taser (engine default: on, not
 * verified). The rest is lost; the knife stays. Death drops just fall from the hand (no toss target).</li>
 * <li>Pick-up: walking over a weapon whose CS slot is free (and grenades within the carry limits); E (+use) on a
 * weapon within the use radius takes it and drops what was in its slot. On the Minecraft hotbar (not CS mode), E stashes
 * it in the Minecraft inventory instead, leaving the CS slots alone: from there it stores like any item (chests,
 * shulker boxes, ender chests) and shift-clicks back into its CS slot.</li>
 * </ul>
 * Approximations: the hand position (eye height - 14 units), carrying the player's velocity into the toss, and the
 * dropped weapon's own physics (see {@link DroppedWeaponEntity}).
 */
public final class CsDrop {
	private static final double U = GrenadeEntity.UNITS;
	private static final double GRAVITY = 800.0;
	/** Source PLAYER_USE_RADIUS (units). */
	public static final double USE_RADIUS = 80.0;

	private CsDrop() {
	}

	/** Can this stack be dropped the CS way (a CS weapon that isn't the knife). */
	public static boolean isCsItem(ItemStack s) {
		return s.getItem() instanceof GunItem || s.getItem() instanceof GrenadeItem || s.getItem() instanceof KnifeItem;
	}

	/** The stack as it lies on the ground: no reload, scope or burst state carried over (CS2 cancels them on drop). */
	private static ItemStack settle(ItemStack s) {
		ItemStack out = s.copy();
		if (!(out.getItem() instanceof GunItem g && g.spec().taser())) {
			out.remove(ModComponents.RELOAD_END); // a Zeus keeps recharging; a reload is cancelled
		}
		out.remove(ModComponents.RELOAD_START);
		out.remove(ModComponents.RELOAD_SHELLS);
		out.remove(ModComponents.REZOOM_AT);
		if (out.has(ModComponents.ZOOM)) {
			out.set(ModComponents.ZOOM, 0);
		}
		return out;
	}

	/** Where the dropping hand is (blocks): Source moves the weapon to the hand bone + 10 units along the body. */
	private static Vec3 hand(ServerPlayer p) {
		Vec3 fwd = Vec3.directionFromRotation(0, p.getYRot());
		Vec3 eye = p.getEyePosition();
		Vec3 h = eye.add(0, -14.0 / U, 0).add(fwd.scale(10.0 / U));
		// keep it on this side of a wall the player is facing
		var hit = p.level().clip(new ClipContext(eye, h, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.MISS ? h : hit.getLocation().subtract(fwd.scale(4.0 / U));
	}

	/** G / Q: toss {@code stack} forward the CS way. */
	public static void toss(ServerPlayer p, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		Refunds.used(stack); // dropped: no refund
		Vec3 from = hand(p);
		Vec3 centre = p.position().add(0, p.getBbHeight() / 2, 0);
		Vec3 target = centre.add(p.getLookAngle().scale(64.0 / U));
		Vec3 vel = vecCheckToss(p, from, target, 0.2);
		Vec3 pv = p.getDeltaMovement().scale(20.0 * U); // approximation: the weapon leaves with the player's motion
		vel = vel.add(pv.x, 0, pv.z);
		DroppedWeaponEntity.spawn(p.level(), settle(stack), from, vel, p);
	}

	/** Death: the weapon falls from the dead player's hand, carrying their motion. */
	public static void fall(ServerPlayer p, ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		Vec3 pv = p.getDeltaMovement().scale(20.0 * U);
		DroppedWeaponEntity.spawn(p.level(), settle(stack), hand(p), pv, p);
	}

	/**
	 * Source's VecCheckToss (units): the launch velocity for an arc from {@code a} to {@code b} (blocks) whose apex is at
	 * most {@code ratio} x the distance above the higher end (and under any ceiling within 300 units of the midpoint);
	 * zero if there's no such arc (then the weapon just drops).
	 */
	static Vec3 vecCheckToss(ServerPlayer p, Vec3 a, Vec3 b, double ratio) {
		Vec3 s1 = a.scale(U);
		Vec3 s2 = b.scale(U);
		if (s2.y - s1.y > 500) {
			return Vec3.ZERO;
		}
		Vec3 mid = s1.add(s2).scale(0.5);
		Vec3 up = mid.add(0, 300, 0);
		var hit = p.level().clip(new ClipContext(mid.scale(1 / U), up.scale(1 / U), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
		double midY = hit.getType() == HitResult.Type.MISS ? up.y : hit.getLocation().y * U - 15;
		double maxH = ratio * s2.subtract(s1).length();
		double highest = Math.max(s1.y, s2.y);
		if (midY - highest > maxH) {
			midY = highest + maxH;
		}
		if (midY < s1.y || midY < s2.y) {
			return Vec3.ZERO;
		}
		double t1 = Math.sqrt((midY - s1.y) / (0.5 * GRAVITY));
		double t2 = Math.sqrt((midY - s2.y) / (0.5 * GRAVITY));
		if (t1 < 0.1) {
			return Vec3.ZERO;
		}
		Vec3 v = s2.subtract(s1).scale(1.0 / (t1 + t2));
		return new Vec3(v.x, GRAVITY * t1, v.z);
	}

	/** G: drop the weapon in hand (CS mode: the selected CS slot; else a CS item in the Minecraft hand). */
	public static void dropHeld(ServerPlayer p) {
		if (CsInventory.active(p)) {
			int slot = p.getInventory().getSelectedSlot();
			CsInventory.Data d = CsInventory.of(p);
			ItemStack s = d.get(slot);
			if (s.isEmpty() || slot == CsInventory.KNIFE) {
				return; // CS2: mp_drop_knife_enable 0
			}
			ItemStack out = s.getCount() > 1 ? s.split(1) : s;
			if (out == s) {
				d.items.set(slot, ItemStack.EMPTY);
			}
			CsInventory.markDirty(p);
			toss(p, out);
			if (d.get(slot).isEmpty()) {
				CsInventory.selectBest(p);
			}
			return;
		}
		ItemStack held = p.getMainHandItem();
		if (isCsItem(held)) {
			toss(p, held.split(1));
		}
	}

	/** Walk-over pick-up: only into a free CS slot. True if taken. */
	public static boolean walkOverPickup(ServerPlayer p, DroppedWeaponEntity e) {
		ItemStack s = e.getItem();
		if (!CsInventory.fits(p, s)) {
			return false;
		}
		take(p, e);
		return true;
	}

	/** E on a weapon: take it, dropping what's in its slot (CS2 swaps). */
	public static void usePickup(ServerPlayer p, int entityId) {
		Entity target = p.level().getEntity(entityId);
		if (!(target instanceof DroppedWeaponEntity e) || !e.isAlive()) {
			return;
		}
		double reach = USE_RADIUS / U + DroppedWeaponEntity.TRIGGER;
		if (e.getBoundingBox().distanceToSqr(p.getEyePosition()) > reach * reach) {
			return;
		}
		ItemStack s = e.getItem();
		int slot = CsInventory.slotFor(s);
		if (!CsInventory.active(p)) {
			stash(p, e);
			return;
		}
		if (slot < 0 || slot == CsInventory.KNIFE) {
			return;
		}
		if (!CsInventory.fits(p, s)) {
			if (slot == CsInventory.GRENADE0) {
				return; // grenades don't swap: over the carry limit
			}
			ItemStack old = CsInventory.of(p).get(slot);
			CsInventory.of(p).items.set(slot, ItemStack.EMPTY);
			CsInventory.markDirty(p);
			toss(p, old);
		}
		take(p, e);
		if (CsInventory.active(p)) {
			int now = CsInventory.slotOf(p, s);
			if (now >= 0) {
				p.getInventory().setSelectedSlot(now); // CS2: +use pick-up switches to it
				p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(now));
			}
		}
	}

	/** On the Minecraft hotbar: the weapon goes into the Minecraft inventory (if there's room). */
	private static void stash(ServerPlayer p, DroppedWeaponEntity e) {
		ItemStack s = e.getItem().copy();
		String snd = pickupSound(s);
		// the main inventory rows first: a CS weapon in the hotbar is only an item
		var inv = p.getInventory();
		for (int i = 9; i < 36 && !s.isEmpty(); i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setItem(i, s.copy());
				s.setCount(0);
			}
		}
		if (!s.isEmpty()) {
			inv.add(s); // takes what fits; s keeps the rest
		}
		if (!s.isEmpty()) {
			e.setItem(s);
			p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Inventory full."));
			return;
		}
		e.setItem(ItemStack.EMPTY);
		e.discard();
		((ServerLevel) p.level()).playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id(snd)),
				SoundSource.PLAYERS, 1f, 1f);
	}

	private static void take(ServerPlayer p, DroppedWeaponEntity e) {
		ItemStack s = e.getItem();
		if (!CsInventory.put(p, s.copy())) {
			return;
		}
		e.setItem(ItemStack.EMPTY);
		e.discard();
		String snd = pickupSound(s);
		((ServerLevel) p.level()).playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id(snd)),
				SoundSource.PLAYERS, 1f, 1f);
	}

	/** CS2 Player.Pickup* by weapon class. */
	private static String pickupSound(ItemStack s) {
		if (s.getItem() instanceof GrenadeItem) {
			return "player.pickupgrenadeaudible";
		}
		if (s.getItem() instanceof GunItem g) {
			return switch (g.spec().type()) {
				case "pistol" -> "player.pickuppistol";
				case "rifle" -> "player.pickuprifle";
				case "submachinegun" -> "player.pickupsmg";
				case "shotgun" -> "player.pickupshotgun";
				case "sniper_rifle" -> "player.pickupsniper";
				case "machinegun" -> "player.pickupheavy";
				default -> "player.pickupweapon";
			};
		}
		return "player.pickupweapon";
	}

	/** CS2 physics impact sound (game_sounds_physics weapon.*.Impact) for a dropped weapon landing. */
	public static String impactSound(ItemStack s) {
		if (s.getItem() instanceof GrenadeItem g) {
			return switch (g.weaponId()) {
				case "hegrenade" -> "weapon.hegrenade.impact";
				case "flashbang" -> "weapon.flashbang.impact";
				case "molotov" -> "weapon.molotov.impact";
				case "incgrenade" -> "weapon.incendiary.impact";
				default -> {
					Guns.GrenadeDef def = Guns.GRENADES.get(g.weaponId());
					String bounce = def == null ? null : def.sounds().get("bounce");
					yield bounce == null ? "weapon.flashbang.impact" : bounce; // smoke / decoy: their own bounce
				}
			};
		}
		if (s.getItem() instanceof GunItem g) {
			return switch (g.spec().type()) {
				case "rifle" -> "weapon.rifle.impact";
				case "submachinegun" -> "weapon.smg.impact";
				case "shotgun" -> "weapon.shotgun.impact";
				case "sniper_rifle" -> "weapon.sniper.impact";
				case "machinegun" -> "weapon.heavy.impact";
				default -> "weapon.pistol.impact";
			};
		}
		return null;
	}
}
