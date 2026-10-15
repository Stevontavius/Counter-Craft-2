package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.team.CsInventory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;

/**
 * CS2's C4 (a test for a future PvE mode: {@code /cc give beta c4} with {@code /beta test_c4 1} only). It lives in the
 * C4 slot of the CS hotbar (key 5). Holding attack (mouse 1) with it out, standing on the
 * ground, plants it in {@link #PLANT_TICKS} ({@link PlantedC4}): CS2's plant clip plays and the player can't move; letting
 * go, leaving the ground or switching away stops. The client asks ({@code C4ActionPayload}), the server times it.
 */
public class C4Item extends Item implements CsWeapon {
	/** How long planting takes: not measured in CS2 (its plant clip is 4 s; the bomb is down before it ends). */
	public static final int PLANT_TICKS = 60;
	/** CS2 weapon_c4 m_flDeployDuration (weapons.vdata). */
	private static final float DEPLOY_SECONDS = 1.233333f;
	/** CS2's notice (SFUI_Notice_C4_Plant_Must_Be_On_Ground). */
	private static final String ON_GROUND = "You must be standing on the ground to plant the C4";
	/** Planting holds the planter still (CS2 roots you while you plant). */
	private static final AttributeModifier ROOTED = new AttributeModifier(CounterCraft.id("planting_c4"), -1.0,
			AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
	/** Players planting: when they started (game time). */
	private static final Map<UUID, Long> PLANTING = new ConcurrentHashMap<>();

	public C4Item(Properties properties) {
		super(properties);
	}

	/** The C4 test is off ({@code /beta test_c4 0}): no C4s in this world (a vanilla /give, a chest). */
	@Override
	public void inventoryTick(net.minecraft.world.item.ItemStack stack, ServerLevel level, net.minecraft.world.entity.Entity owner,
			net.minecraft.world.entity.EquipmentSlot slot) {
		if (!CsInventory.c4Test(level)) {
			stack.setCount(0);
		}
	}

	@Override
	public String weaponId() {
		return "c4";
	}

	@Override
	public float deploySeconds() {
		return DEPLOY_SECONDS;
	}

	/** Holding the C4 out of its CS slot. */
	private static boolean holding(ServerPlayer p) {
		return p.isAlive() && !p.isSpectator() && CsInventory.active(p) && p.getInventory().getSelectedSlot() == CsInventory.C4
				&& CsInventory.of(p).get(CsInventory.C4).getItem() instanceof C4Item;
	}

	/** Attack pressed with the C4 out: start planting (on the ground only). */
	public static void startPlanting(ServerPlayer p) {
		if (!holding(p) || PLANTING.containsKey(p.getUUID())) {
			return;
		}
		if (!p.onGround()) {
			p.sendSystemMessage(Component.literal(ON_GROUND), true);
			return;
		}
		PLANTING.put(p.getUUID(), p.level().getGameTime());
		p.getAttribute(Attributes.MOVEMENT_SPEED).addOrUpdateTransientModifier(ROOTED);
		// third person: CS2's agent graph's own plant action (its plant clip is 4 s)
		dev.countercraft.AgentAnims.broadcast(p, "action_c4_plant", "", 4f);
	}

	/** Attack let go (or anything else that stops it). */
	public static void stopPlanting(ServerPlayer p) {
		if (PLANTING.remove(p.getUUID()) != null) {
			p.getAttribute(Attributes.MOVEMENT_SPEED).removeModifier(ROOTED.id());
			dev.countercraft.AgentAnims.broadcast(p, "action_reset", "", 0f);
		}
	}

	/** Every server tick: planting goes on while they hold it on the ground; done, the bomb is down. */
	public static void tick(MinecraftServer server) {
		for (var e : PLANTING.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				PLANTING.remove(e.getKey());
				continue;
			}
			if (!holding(p) || !p.onGround()) {
				stopPlanting(p);
				continue;
			}
			if (p.level().getGameTime() - e.getValue() >= PLANT_TICKS) {
				stopPlanting(p);
				CsInventory.of(p).items.set(CsInventory.C4, net.minecraft.world.item.ItemStack.EMPTY);
				CsInventory.markDirty(p);
				PlantedC4.plant((ServerLevel) p.level(), p);
				CsInventory.selectBest(p);
			}
		}
	}
}
