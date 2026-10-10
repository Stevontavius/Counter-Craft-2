package dev.countercraft.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A stationary test bot: a real server player (so teams, PvP, backstabs, ragdolls, kill awards all treat it as one)
 * with no client. It stands where it was put, facing the way it was put, and only falls (no client sends its
 * movement, so it runs its own physics tick and drops any sideways push).
 */
public final class BotPlayer extends ServerPlayer {
	final String team;
	final String weapon;
	final Vec3 home;
	final float yaw;
	/** Ticks since it died (it comes back after {@link TestBots}' respawn delay; in hardcore it leaves then). */
	int deathWait;

	BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, String team, String weapon, Vec3 home, float yaw) {
		super(server, level, profile, ClientInformation.createDefault());
		this.team = team;
		this.weapon = weapon;
		this.home = home;
		this.yaw = yaw;
	}

	@Override
	public void tick() {
		super.tick();
		if (isAlive()) {
			Vec3 v = getDeltaMovement();
			setDeltaMovement(0, v.y, 0);
			setYRot(yaw);
			setYHeadRot(yaw);
			setYBodyRot(yaw);
			setXRot(0f);
			if (Math.abs(getX() - home.x) > 0.01 || Math.abs(getZ() - home.z) > 0.01) {
				snapTo(home.x, getY(), home.z, yaw, 0f);
			}
		}
		doTick();
	}
}
