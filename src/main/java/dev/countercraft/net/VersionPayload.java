package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * While connecting (the configuration phase), both ways: the server's Counter-Craft 2 release and version, then the
 * client's in reply ({@link dev.countercraft.VersionCheck}).
 */
public record VersionPayload(String release, String version) implements CustomPacketPayload {
	public static final Type<VersionPayload> TYPE = new Type<>(CounterCraft.id("version"));
	public static final StreamCodec<ByteBuf, VersionPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, VersionPayload::release,
			ByteBufCodecs.STRING_UTF8, VersionPayload::version,
			VersionPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
