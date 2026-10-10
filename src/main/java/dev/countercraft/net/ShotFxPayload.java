package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Server to clients: someone fired. Where each bullet (pellet) ended, which of them carry a tracer (bit mask, CS2's
 * every-Nth-bullet tracer frequency), and flags: {@link #SILENCED}, {@link #ALT_MUZZLE} (Dual Berettas' left gun).
 * Clients draw the muzzle flash (others' guns) and the tracers.
 */
public record ShotFxPayload(int shooter, String gun, int flags, List<Vec3> ends, int tracers) implements CustomPacketPayload {
	public static final int SILENCED = 1;
	public static final int ALT_MUZZLE = 2;

	public static final Type<ShotFxPayload> TYPE = new Type<>(CounterCraft.id("shot_fx"));
	public static final StreamCodec<FriendlyByteBuf, ShotFxPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.shooter);
		buf.writeUtf(p.gun);
		buf.writeByte(p.flags);
		buf.writeVarInt(p.ends.size());
		for (Vec3 e : p.ends) {
			buf.writeDouble(e.x);
			buf.writeDouble(e.y);
			buf.writeDouble(e.z);
		}
		buf.writeVarInt(p.tracers);
	}, buf -> {
		int shooter = buf.readVarInt();
		String gun = buf.readUtf(64);
		int flags = buf.readByte();
		int n = Math.min(buf.readVarInt(), 64);
		List<Vec3> ends = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			ends.add(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
		}
		return new ShotFxPayload(shooter, gun, flags, ends, buf.readVarInt());
	});

	public boolean silenced() {
		return (flags & SILENCED) != 0;
	}

	public boolean altMuzzle() {
		return (flags & ALT_MUZZLE) != 0;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
