package dev.countercraft.client.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's own jump power (Jump Boost, honey blocks, the jump strength attribute) and block speed factor for CS movement. */
@Mixin(LivingEntity.class)
public interface LivingEntityMovementInvoker {
	@Invoker("getJumpPower")
	float countercraft$jumpPower();

	@Invoker("getBlockSpeedFactor")
	float countercraft$blockSpeedFactor();
}
