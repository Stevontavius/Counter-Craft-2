package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to everyone: an MVP banner ({@link dev.countercraft.Mvp} for a challenge, {@link dev.countercraft.BossFights}
 * for a boss): its label ("CHALLENGE COMPLETE", "BOSS DEFEATED"), who (name, faction), the title and description, and
 * the MVP's music kit (its MVP anthem plays; "" = the default kit).
 */
public record MvpPayload(String label, String name, String team, String title, String description, String kit) implements CustomPacketPayload {
	public static final Type<MvpPayload> TYPE = new Type<>(CounterCraft.id("mvp"));
	public static final StreamCodec<ByteBuf, MvpPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(32), MvpPayload::label,
			ByteBufCodecs.stringUtf8(64), MvpPayload::name,
			ByteBufCodecs.stringUtf8(8), MvpPayload::team,
			ByteBufCodecs.stringUtf8(256), MvpPayload::title,
			ByteBufCodecs.stringUtf8(512), MvpPayload::description,
			ByteBufCodecs.stringUtf8(64), MvpPayload::kit, MvpPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
