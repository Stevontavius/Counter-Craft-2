package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.ServerCvars;
import dev.countercraft.net.ThrowPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A CS2 grenade. Hold fire to pull the pin, release to throw: left = full throw, right = underhand lob, both =
 * medium (CS2's throw strengths 1.0 / 0.0 / 0.5). The client sends a {@code ThrowPayload} 0.1 s after the release.
 */
public class GrenadeItem extends Item implements CsWeapon {
	private final Guns.GrenadeDef def;

	public GrenadeItem(Properties properties, Guns.GrenadeDef def) {
		super(properties);
		this.def = def;
	}

	public Guns.GrenadeDef def() {
		return def;
	}

	@Override
	public String weaponId() {
		return def.id();
	}

	@Override
	public float deploySeconds() {
		return def.deploy();
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
		return false;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return InteractionResult.CONSUME;
	}

	/**
	 * Server: throw one (the client sends this 0.1 s after the button is let go, as CS2 throws). CS2's
	 * CBaseCSGrenade::ThrowGrenade: speed = clamp(throw velocity x 0.9, 15, 750) x lerp(0.3, 1, strength) along the
	 * remapped pitch, from the eye lowered by 12 x (1 - strength) units and pushed 22 units forward (a 2-unit hull
	 * trace, so it never starts inside a wall), plus 1.25 x the thrower's velocity. Then the throw animation plays out
	 * and the next grenade is drawn; the last one leaves the inventory unless sv_infinite_ammo is set.
	 */
	public void throwGrenade(ServerPlayer player, ItemStack stack, ThrowPayload t) {
		ServerLevel level = player.level();
		float s = Math.max(0f, Math.min(1f, t.strength()));
		Vec3 dir = GrenadePhysics.direction(GrenadePhysics.throwPitch(Math.max(-90f, Math.min(90f, t.pitch()))), t.yaw());
		Vec3 eye = player.getEyePosition().add(0, (12.0 * s - 12.0) / GrenadeEntity.UNITS, 0);
		Vec3 src = GrenadeEntity.hullTrace(level, player, eye, eye.add(dir.scale(22.0 / GrenadeEntity.UNITS))).end();
		Vec3 own = new Vec3(t.vx(), t.vy(), t.vz());
		if (own.length() > dev.countercraft.movement.CsMove.maxVelocity + 1.0 || !Double.isFinite(own.length())) {
			own = Vec3.ZERO;
		}
		GrenadeEntity g = new GrenadeEntity(level, player, stack.copyWithCount(1), def.id());
		if ("decoy".equals(def.id())) {
			g.setDecoyGun(decoyGun(player));
		}
		g.launch(src, GrenadePhysics.throwVelocity(t.yaw(), t.pitch(), s, def.throwVelocity(), own));
		level.addFreshEntity(g);
		dev.countercraft.RunStats.add(player, "grenades", 1);
		if ("flashbang".equals(def.id()) && level instanceof net.minecraft.server.level.ServerLevel sl) {
			dev.countercraft.BossArmor.flashThrown(sl, player, g); // any Warden in earshot stares you down
		}
		dev.countercraft.Radio.grenade(player, def.id()); // the agent's "Fire in the hole!" / "Flashbang out!"...
		dev.countercraft.AgentAnims.broadcast(player, "action_attack", "attack_grenade_throw", def.throwSeconds(s));
		String snd = def.sounds().get("throw");
		if (snd != null) {
			level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id(snd)),
					SoundSource.PLAYERS, 1f, 1f);
		}
		// the throw animation started 0.1 s before this packet; after it the next grenade is drawn (or the last weapon)
		long animTicks = Math.max(0, Math.round((def.throwSeconds(s) - 0.1f) * 20f));
		if (ServerCvars.keepGrenades()) {
			Deploy.lock(player, animTicks + Math.round(def.deploy() * 20f));
		} else {
			stack.shrink(1);
			if (stack.isEmpty()) {
				Deploy.switchBackLater(player, animTicks);
			} else {
				Deploy.lock(player, animTicks + Math.round(def.deploy() * 20f));
			}
		}
	}

	/** The thrower's primary gun, else their pistol, else an AK. */
	private static String decoyGun(ServerPlayer player) {
		String pistol = null;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (player.getInventory().getItem(i).getItem() instanceof GunItem gun) {
				if ("primary".equals(gun.spec().slot())) {
					return gun.spec().id();
				}
				if (pistol == null && "secondary".equals(gun.spec().slot())) {
					pistol = gun.spec().id();
				}
			}
		}
		return pistol != null ? pistol : "ak47";
	}
}
