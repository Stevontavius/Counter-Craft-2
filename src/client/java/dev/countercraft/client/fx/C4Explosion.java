package dev.countercraft.client.fx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A C4 going off, as Minecraft would draw it, only far bigger: Minecraft's own particle art throughout (its explosion
 * puffs, its flame, its campfire smoke, its lava embers and block debris), each in Minecraft's pixels, scaled up.
 * <ul>
 * <li>The blast (the first third of a second): yellow-hot explosion puffs bursting out to 14 blocks.</li>
 * <li>The fireball (the first second): big flames rolling out and up into a column, mushrooming at the top, cooling from
 * yellow through orange and red to soot as they go.</li>
 * <li>Embers and debris: lava sparks arcing out, and the ground the bomb lay on, broken and flung.</li>
 * <li>Black smoke: a dark column about 30 blocks high and its cap, growing, rising, drifting with the wind and clearing
 * gradually over 20 to 30 s.</li>
 * </ul>
 * Client side, from BlastWavePayload (everyone who can see or hear it), so it's seen from afar; fewer particles at
 * Minecraft's Decreased / Minimal particle settings.
 */
public final class C4Explosion {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final List<C4Explosion> LIVE = new ArrayList<>();
	/** The black smoke: puffed out from this tick to that after the blast, each puff lasting between these (ticks). */
	private static final int SMOKE_FROM = 3;
	private static final int SMOKE_TO = 80;
	private static final int SMOKE_LIFE_MIN = 400;
	private static final int SMOKE_LIFE_MAX = 600;
	/** When the last of the smoke has cleared (seconds after the blast). */
	public static final double SMOKE_SECONDS = (SMOKE_TO + SMOKE_LIFE_MAX) / 20.0;

	/**
	 * How much of the black smoke is left, seconds after the blast: 1 while it's all there, easing to 0 as it clears (its
	 * puffs' fade, (1 - age / life)^1.6, averaged over when they were puffed out and how long they last).
	 */
	public static double smokeLeft(double seconds) {
		double sum = 0;
		int n = 0;
		for (int e = SMOKE_FROM; e < SMOKE_TO; e += 7) {
			for (int life = SMOKE_LIFE_MIN; life <= SMOKE_LIFE_MAX; life += 50) {
				double t = (seconds * 20 - e) / life;
				sum += t <= 0 ? 1 : t >= 1 ? 0 : Math.pow(1 - t, 1.6);
				n++;
			}
		}
		return sum / n;
	}

	private final Vec3 c;
	private final RandomSource random = RandomSource.create();
	private final float density;
	private final Vec3 wind;
	private int age;

	private C4Explosion(Vec3 centre, float density) {
		this.c = centre;
		this.density = density;
		double a = random.nextDouble() * Math.PI * 2;
		this.wind = new Vec3(Math.cos(a), 0, Math.sin(a)).scale(0.012 + random.nextDouble() * 0.01);
	}

	public static void start(Vec3 centre) {
		Minecraft mc = Minecraft.getInstance();
		ParticleStatus s = mc.options.particles().get();
		float density = s == ParticleStatus.MINIMAL ? 0.25f : s == ParticleStatus.DECREASED ? 0.55f : 1f;
		LIVE.add(new C4Explosion(centre, density));
	}

	/** Client tick: each explosion's next particles. */
	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			if (mc.level == null) {
				LIVE.clear();
			}
			return;
		}
		for (Iterator<C4Explosion> it = LIVE.iterator(); it.hasNext(); ) {
			if (it.next().step(mc.level)) {
				it.remove();
			}
		}
	}

	/** Minecraft's particle atlas (looked up each time: a resource reload replaces it). */
	private static TextureAtlas particleAtlas() {
		var atlases = Minecraft.getInstance().getAtlasManager();
		try {
			return atlases.getAtlasOrThrow(net.minecraft.data.AtlasIds.PARTICLES);
		} catch (RuntimeException e) {
			return atlases.getAtlasOrThrow(TextureAtlas.LOCATION_PARTICLES);
		}
	}

	private int n(int count) {
		return Math.max(1, Math.round(count * density));
	}

	private double r(double a, double b) {
		return a + random.nextDouble() * (b - a);
	}

	/** A random direction, flattened toward the ground by {@code flat} (0: a sphere). */
	private Vec3 dir(double flat) {
		double u = random.nextDouble() * 2 - 1;
		double a = random.nextDouble() * Math.PI * 2;
		double s = Math.sqrt(1 - u * u);
		return new Vec3(Math.cos(a) * s, Math.abs(u) * (1 - flat) + 0.05, Math.sin(a) * s);
	}

	/** One tick of it: true once it has nothing left to spawn. */
	private boolean step(ClientLevel level) {
		int t = age++;
		TextureAtlas sprites = particleAtlas();
		if (t == 0) {
			// the blast: white-hot puffs, bursting out
			for (int i = 0; i < n(110); i++) {
				Vec3 d = dir(0.35);
				double dist = r(0, 6);
				Puff p = new Puff(level, c.add(d.scale(dist)), d.scale(r(0.35, 0.8)), sprites, (float) r(5, 10), random.nextInt(4));
				Minecraft.getInstance().particleEngine.add(p);
			}
			// embers and the broken ground
			for (int i = 0; i < n(90); i++) {
				Vec3 d = dir(0.2);
				level.addAlwaysVisibleParticle(ParticleTypes.LAVA, true, c.x, c.y + 0.5, c.z, d.x * r(0.3, 0.9), r(0.4, 1.0), d.z * r(0.3, 0.9));
			}
			BlockState ground = level.getBlockState(BlockPos.containing(c.x, c.y - 0.6, c.z));
			if (!ground.isAir()) {
				for (int i = 0; i < n(160); i++) {
					Vec3 d = dir(0.4);
					level.addAlwaysVisibleParticle(new BlockParticleOption(ParticleTypes.BLOCK, ground), true, c.x + d.x, c.y + 0.3, c.z + d.z,
							d.x * r(0.4, 1.6), r(0.3, 1.2), d.z * r(0.4, 1.6));
				}
			}
		}
		if (t < 6) {
			// more puffs as the blast grows out to 14 blocks
			for (int i = 0; i < n(26); i++) {
				Vec3 d = dir(0.3);
				double dist = r(3, 4 + t * 2);
				Minecraft.getInstance().particleEngine.add(new Puff(level, c.add(d.scale(dist)), d.scale(r(0.15, 0.4)), sprites,
						(float) r(4, 8), random.nextInt(4)));
			}
		}
		if (t < 20) {
			// the fireball: flames rolling out low and wide, and up into a column that mushrooms at the top
			for (int i = 0; i < n(40); i++) {
				Vec3 d = dir(0.55);
				Minecraft.getInstance().particleEngine.add(new Flame(level, c.add(d.scale(r(1, 6))), d.scale(r(0.3, 0.85)).add(0, r(0, 0.12), 0),
						sprites, (float) r(1.8, 4), (int) r(22, 44), 0.9f));
			}
			for (int i = 0; i < n(14); i++) {
				double a = random.nextDouble() * Math.PI * 2;
				double rr = r(0, 3.5);
				Minecraft.getInstance().particleEngine.add(new Flame(level, c.add(Math.cos(a) * rr, r(0, 3), Math.sin(a) * rr),
						new Vec3(Math.cos(a) * 0.04, r(0.75, 1.15), Math.sin(a) * 0.04), sprites, (float) r(2.4, 4.6), (int) r(32, 50), 0.95f));
			}
		}
		if (t >= SMOKE_FROM && t < SMOKE_TO) {
			// the black smoke: low and wide at first, then the column and its cap
			int count = t < 25 ? n(14) : n(6);
			for (int i = 0; i < count; i++) {
				Vec3 at;
				Vec3 v;
				double kind = random.nextDouble();
				if (kind < 0.35 && t < 30) {
					Vec3 d = dir(0.8);
					at = c.add(d.scale(r(3, 15)));
					v = d.scale(r(0.02, 0.06)).add(0, r(0.02, 0.06), 0);
				} else if (kind < 0.7) {
					double a = random.nextDouble() * Math.PI * 2;
					double rr = r(0, 5.5);
					at = c.add(Math.cos(a) * rr, r(0, Math.min(30, 5 + t * 0.8)), Math.sin(a) * rr);
					v = new Vec3(Math.cos(a) * 0.01, r(0.07, 0.16), Math.sin(a) * 0.01);
				} else {
					double a = random.nextDouble() * Math.PI * 2;
					double rr = r(0, Math.min(16, 4 + t * 0.35));
					at = c.add(Math.cos(a) * rr, Math.min(30, 8 + t * 0.8) + r(-3, 4), Math.sin(a) * rr);
					v = new Vec3(Math.cos(a) * 0.04, r(0.02, 0.07), Math.sin(a) * 0.04);
				}
				Minecraft.getInstance().particleEngine.add(new Smoke(level, at, v, wind, sprites, random.nextInt(12), (float) r(3, 6),
						(int) r(SMOKE_LIFE_MIN, SMOKE_LIFE_MAX)));
			}
		}
		return t >= SMOKE_TO;
	}

	/** Minecraft's explosion puff (its 16 frames), huge, yellow-hot cooling to orange, glowing. */
	private static final class Puff extends SingleQuadParticle {
		private final TextureAtlas atlas;

		Puff(ClientLevel level, Vec3 at, Vec3 v, TextureAtlas atlas, float size, int delay) {
			super(level, at.x, at.y, at.z, atlas.getSprite(Identifier.withDefaultNamespace("explosion_0")));
			this.atlas = atlas;
			this.xd = v.x;
			this.yd = v.y;
			this.zd = v.z;
			this.quadSize = size;
			this.lifetime = 14 + delay * 2;
			this.hasPhysics = false;
			this.friction = 0.82f;
			this.gravity = -0.01f;
			this.rCol = 1f;
			this.gCol = 0.92f;
			this.bCol = 0.5f;
		}

		@Override
		public void tick() {
			super.tick();
			float t = (float) age / lifetime;
			setSprite(atlas.getSprite(Identifier.withDefaultNamespace("explosion_" + Math.min(15, (int) (t * 16)))));
			// yellow-hot to orange
			gCol = 0.92f - 0.55f * t;
			bCol = 0.5f - 0.45f * t;
		}

		@Override
		public int getLightCoords(float partialTick) {
			return FULL_BRIGHT;
		}

		@Override
		protected Layer getLayer() {
			return Layer.OPAQUE;
		}
	}

	/** Minecraft's flame, blown up: rolling out and rising, cooling yellow, orange, red, then soot. */
	private static final class Flame extends SingleQuadParticle {
		private final float size;

		Flame(ClientLevel level, Vec3 at, Vec3 v, TextureAtlas atlas, float size, int life, float drag) {
			super(level, at.x, at.y, at.z, atlas.getSprite(Identifier.withDefaultNamespace("flame")));
			this.xd = v.x;
			this.yd = v.y;
			this.zd = v.z;
			this.size = size;
			this.quadSize = size;
			this.lifetime = life;
			this.hasPhysics = false;
			this.friction = drag;
			this.gravity = -0.012f;
			this.roll = this.oRoll = (float) (level.getRandom().nextFloat() * Math.PI * 2);
		}

		@Override
		public void tick() {
			super.tick();
			float t = (float) age / lifetime;
			// yellow-white, orange, deep red, then soot
			rCol = t < 0.7f ? 1f : 1f - (t - 0.7f) / 0.3f * 0.8f;
			gCol = t < 0.25f ? 1f - t * 1.2f : Math.max(0.15f, 0.7f - (t - 0.25f) * 1.3f);
			bCol = t < 0.15f ? 0.8f - t * 4f : 0.1f;
			quadSize = size * (1f + 0.6f * t) * (t > 0.85f ? (1 - t) / 0.15f : 1f);
		}

		@Override
		public int getLightCoords(float partialTick) {
			return FULL_BRIGHT;
		}

		@Override
		protected Layer getLayer() {
			return Layer.OPAQUE;
		}
	}

	/** Minecraft's campfire smoke, black: growing, rising and drifting, and thinning out until it's gone. */
	private static final class Smoke extends SingleQuadParticle {
		private final float start;
		private final Vec3 wind;

		Smoke(ClientLevel level, Vec3 at, Vec3 v, Vec3 wind, TextureAtlas atlas, int frame, float size, int life) {
			super(level, at.x, at.y, at.z, atlas.getSprite(Identifier.withDefaultNamespace("big_smoke_" + frame)));
			this.xd = v.x;
			this.yd = v.y;
			this.zd = v.z;
			this.wind = wind;
			this.start = size;
			this.quadSize = size;
			this.lifetime = life;
			this.hasPhysics = false;
			this.friction = 0.96f;
			this.gravity = 0f;
			float shade = 0.05f + level.getRandom().nextFloat() * 0.1f;
			this.rCol = shade;
			this.gCol = shade;
			this.bCol = shade;
			this.alpha = 0.95f;
			this.roll = this.oRoll = (float) (level.getRandom().nextFloat() * Math.PI * 2);
		}

		@Override
		public void tick() {
			super.tick();
			float t = (float) age / lifetime;
			xd += wind.x * 0.05;
			zd += wind.z * 0.05;
			yd = Math.max(yd, 0.012); // it keeps rising, slower and slower
			quadSize = start * (1f + 1.8f * t);
			alpha = 0.95f * (float) Math.pow(1 - t, 1.6);
			oRoll = roll;
			roll += 0.002f;
		}

		@Override
		protected Layer getLayer() {
			return Layer.TRANSLUCENT;
		}
	}
}
