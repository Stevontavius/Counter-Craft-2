package dev.countercraft.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;

/**
 * A player's equipped CS2 cosmetics per team, chosen in the loadout (client side) and sent to the server: weapon
 * finishes (weapon id -> paint kit), the knife (type + finish), gloves (type + finish), the agent (model + voice).
 * Synced to everyone: other players see your agent, gloves and knife, and hear your voice. Bought weapons get your
 * finish ({@link Buying}); the knife slot holds your knife ({@link CsInventory#refreshKnife}).
 */
public final class Cosmetics {
	/** One team's side of the loadout. Empty strings / 0 = CS2's defaults. */
	public record Side(Map<String, Integer> skins, String knife, int knifeSkin, String gloves, int gloveSkin, String agent, String voice) {
		public static final Side DEFAULT = new Side(Map.of(), "", 0, "", 0, "", "");

		static final Codec<Side> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("skins").forGetter(Side::skins),
				Codec.STRING.fieldOf("knife").forGetter(Side::knife),
				Codec.INT.fieldOf("knife_skin").forGetter(Side::knifeSkin),
				Codec.STRING.fieldOf("gloves").forGetter(Side::gloves),
				Codec.INT.fieldOf("glove_skin").forGetter(Side::gloveSkin),
				Codec.STRING.fieldOf("agent").forGetter(Side::agent),
				Codec.STRING.fieldOf("voice").forGetter(Side::voice)).apply(i, Side::new));

		static final StreamCodec<ByteBuf, Side> STREAM_CODEC = new StreamCodec<>() {
			@Override
			public Side decode(ByteBuf b) {
				int n = Math.min(ByteBufCodecs.VAR_INT.decode(b), 512);
				Map<String, Integer> m = new HashMap<>();
				for (int k = 0; k < n; k++) {
					m.put(ByteBufCodecs.stringUtf8(64).decode(b), ByteBufCodecs.VAR_INT.decode(b));
				}
				return new Side(m, ByteBufCodecs.stringUtf8(64).decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.stringUtf8(64).decode(b),
						ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.stringUtf8(64).decode(b), ByteBufCodecs.stringUtf8(64).decode(b));
			}

			@Override
			public void encode(ByteBuf b, Side s) {
				ByteBufCodecs.VAR_INT.encode(b, s.skins().size());
				s.skins().forEach((k, v) -> {
					ByteBufCodecs.stringUtf8(64).encode(b, k);
					ByteBufCodecs.VAR_INT.encode(b, v);
				});
				ByteBufCodecs.stringUtf8(64).encode(b, s.knife());
				ByteBufCodecs.VAR_INT.encode(b, s.knifeSkin());
				ByteBufCodecs.stringUtf8(64).encode(b, s.gloves());
				ByteBufCodecs.VAR_INT.encode(b, s.gloveSkin());
				ByteBufCodecs.stringUtf8(64).encode(b, s.agent());
				ByteBufCodecs.stringUtf8(64).encode(b, s.voice());
			}
		};
	}

	/** Both teams' sides. */
	public record Loadout(Side t, Side ct) {
		public static final Loadout DEFAULT = new Loadout(Side.DEFAULT, Side.DEFAULT);
		public static final Codec<Loadout> CODEC = RecordCodecBuilder.create(i -> i.group(
				Side.CODEC.fieldOf("t").forGetter(Loadout::t), Side.CODEC.fieldOf("ct").forGetter(Loadout::ct)).apply(i, Loadout::new));
		public static final StreamCodec<ByteBuf, Loadout> STREAM_CODEC = StreamCodec.composite(
				Side.STREAM_CODEC, Loadout::t, Side.STREAM_CODEC, Loadout::ct, Loadout::new);

		public Side side(String team) {
			return Teams.CT.equals(team) ? ct : t;
		}
	}

	public static final AttachmentType<Loadout> LOADOUT = AttachmentRegistry.<Loadout>builder()
			.persistent(Loadout.CODEC)
			.copyOnDeath()
			.syncWith(Loadout.STREAM_CODEC, AttachmentSyncPredicate.all())
			.buildAndRegister(CounterCraft.id("cosmetics"));

	private Cosmetics() {
	}

	/** Load the class at startup so the attachment is registered before anyone joins. */
	public static void init() {
	}

	/** The player's side for their current team (defaults if they never sent a loadout). */
	public static Side of(Player p) {
		Loadout l = p.getAttached(LOADOUT);
		return (l == null ? Loadout.DEFAULT : l).side(Teams.of(p));
	}

	/** The finish the player equipped for a weapon (mod id), 0 = none. */
	public static int skinFor(Player p, String weaponId) {
		return of(p).skins().getOrDefault(weaponId, 0);
	}
}
