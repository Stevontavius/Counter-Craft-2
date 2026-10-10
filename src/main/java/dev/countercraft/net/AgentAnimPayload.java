package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A player's weapon action for the third-person CS2 agent animation (graph control parameters): action
 * ("action_reload", "action_attack", "action_inspect"...), attack type ("attack_knife_lightmiss"...) and how long the
 * action lasts. Server to the players who can see them; client to server only for the inspect, which the server relays.
 */
public record AgentAnimPayload(int entity, String action, String attack, float seconds) implements CustomPacketPayload {
	public static final Type<AgentAnimPayload> TYPE = new Type<>(CounterCraft.id("agent_anim"));
	public static final StreamCodec<ByteBuf, AgentAnimPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, AgentAnimPayload::entity, ByteBufCodecs.STRING_UTF8, AgentAnimPayload::action,
			ByteBufCodecs.STRING_UTF8, AgentAnimPayload::attack, ByteBufCodecs.FLOAT, AgentAnimPayload::seconds, AgentAnimPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
