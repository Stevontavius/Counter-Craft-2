package dev.countercraft.gun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * CS2's 2026 bomb shockwave, Counter-Craft's way: how far a planted C4's blast has to travel through open space to
 * reach each block around it. CS2 bakes this into each map ("precomputed simulation values"); here it's worked out
 * while the fuse runs, a slice each tick ({@link #step}), and started again as soon as a pass is done, so blocks
 * placed or broken count from the next pass (a few seconds). Walls stop it; it wraps around corners, and every block
 * of detour costs it {@link #CORNER} more (the wave loses strength bending round them).
 * <p>A Dijkstra over the 26 neighbours of each open block (diagonal steps only between open blocks, so it never leaks
 * through a diagonal crack), in buckets of 1/8 block. Unloaded chunks count as solid.</p>
 */
public final class BlastField {
	/** Grid half size (blocks): nothing further than the blast radius in a straight line can be hit. */
	static final int H = (int) Math.ceil(PlantedC4.BLAST_RADIUS) + 1;
	static final int N = 2 * H + 1;
	/** Each block of detour around walls counts this much extra on top of the detour itself (Counter-Craft's value). */
	public static final double CORNER = 1.0;
	/** The longest path followed (blocks): past it even the corner-free remainder would be out of reach. */
	static final double PATH_MAX = PlantedC4.BLAST_RADIUS * 1.6;
	private static final float SCALE = 64f;
	private static final char UNSET = Character.MAX_VALUE;
	private static final int BUCKETS_PER_BLOCK = 8;
	private static final int[][] STEPS = new int[26][];
	private static final float[] COST = new float[26];

	static {
		int i = 0;
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				for (int z = -1; z <= 1; z++) {
					if (x != 0 || y != 0 || z != 0) {
						STEPS[i] = new int[] {x, y, z};
						COST[i++] = (float) Math.sqrt(x * x + y * y + z * z);
					}
				}
			}
		}
	}

	private final ServerLevel level;
	/** The bomb's block: the grid's centre. */
	public final BlockPos origin;
	private char[] done;
	private char[] work = new char[N * N * N];
	/** Per pass: 0 not looked at, 1 open, 2 solid. */
	private final byte[] open = new byte[N * N * N];
	private int[][] buckets = new int[(int) (PATH_MAX * BUCKETS_PER_BLOCK) + 2][];
	private final int[] sizes = new int[buckets.length];
	private int bucket = -1;
	private int passes;
	private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

	public BlastField(ServerLevel level, Vec3 centre) {
		this.level = level;
		BlockPos o = BlockPos.containing(centre);
		this.origin = level.getBlockState(o).getCollisionShape(level, o).isEmpty() ? o : o.above();
		start();
	}

	private static int index(int x, int y, int z) {
		return (x * N + y) * N + z;
	}

	private void start() {
		Arrays.fill(work, UNSET);
		Arrays.fill(open, (byte) 0);
		Arrays.fill(sizes, 0);
		int o = index(H, H, H);
		work[o] = 0;
		push(0, o);
		bucket = 0;
	}

	private void push(int b, int cell) {
		int[] a = buckets[b];
		if (a == null) {
			a = buckets[b] = new int[64];
		} else if (sizes[b] == a.length) {
			a = buckets[b] = Arrays.copyOf(a, a.length * 2);
		}
		a[sizes[b]++] = cell;
	}

	/** Whether this grid block lets the blast through (looked up once a pass). */
	private boolean isOpen(int cell, int x, int y, int z) {
		byte o = open[cell];
		if (o == 0) {
			int wx = origin.getX() + x - H;
			int wy = origin.getY() + y - H;
			int wz = origin.getZ() + z - H;
			boolean pass;
			if (wy < level.getMinY() || wy >= level.getMaxY()
					|| level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(wx), SectionPos.blockToSectionCoord(wz)) == null) {
				pass = false;
			} else {
				m.set(wx, wy, wz);
				BlockState s = level.getBlockState(m);
				pass = s.isAir() || s.getCollisionShape(level, m).isEmpty();
			}
			open[cell] = o = (byte) (pass ? 1 : 2);
		}
		return o == 1;
	}

	private boolean isOpen(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < N && y < N && z < N && isOpen(index(x, y, z), x, y, z);
	}

	/** Work on the pass for up to {@code nanos}: true when a pass finished during this call (and another began). */
	public boolean step(long nanos) {
		long end = System.nanoTime() + nanos;
		double r2 = (PlantedC4.BLAST_RADIUS + 1) * (PlantedC4.BLAST_RADIUS + 1);
		int checks = 0;
		while (bucket < buckets.length) {
			if (sizes[bucket] == 0) {
				bucket++;
				continue;
			}
			int cell = buckets[bucket][--sizes[bucket]];
			float d = work[cell] / SCALE;
			if ((int) (d * BUCKETS_PER_BLOCK) < bucket) {
				continue; // reached more cheaply since it was queued
			}
			int x = cell / (N * N);
			int y = cell / N % N;
			int z = cell % N;
			for (int i = 0; i < 26; i++) {
				int[] s = STEPS[i];
				int nx = x + s[0];
				int ny = y + s[1];
				int nz = z + s[2];
				if (nx < 0 || ny < 0 || nz < 0 || nx >= N || ny >= N || nz >= N) {
					continue;
				}
				int dx = nx - H;
				int dy = ny - H;
				int dz = nz - H;
				if (dx * dx + dy * dy + dz * dz > r2) {
					continue;
				}
				float nd = d + COST[i];
				int n = index(nx, ny, nz);
				// compared as stored (1/64 block): a path that only rounds to the same length is no shorter
				char q = (char) Math.round(nd * SCALE);
				if (nd > PATH_MAX || q >= work[n] || !isOpen(n, nx, ny, nz)) {
					continue;
				}
				// a diagonal step only between open blocks: never through a diagonal crack in a wall
				if ((s[0] != 0 && !isOpen(x + s[0], y, z)) || (s[1] != 0 && !isOpen(x, y + s[1], z)) || (s[2] != 0 && !isOpen(x, y, z + s[2]))) {
					continue;
				}
				work[n] = q;
				push((int) (nd * BUCKETS_PER_BLOCK), n);
			}
			if ((++checks & 255) == 0 && System.nanoTime() > end) {
				return false;
			}
		}
		// a pass is done: it's the field now, and the next one starts (blocks may have changed)
		char[] t = done == null ? new char[N * N * N] : done;
		done = work;
		work = t;
		passes++;
		start();
		return true;
	}

	/** Finish the pass under way now (only if no pass is done yet: a bomb going off before its first). */
	public void finish() {
		while (done == null) {
			step(Long.MAX_VALUE / 4);
		}
	}

	public boolean ready() {
		return done != null;
	}

	public int passes() {
		return passes;
	}

	/** How far the pass under way has got, 0..1. */
	public float progress() {
		return Math.min(1f, (float) bucket / buckets.length);
	}

	/** The path length to a grid block (blocks), or NaN if the blast can't get there. */
	public double path(int x, int y, int z) {
		if (done == null || x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) {
			return Double.NaN;
		}
		char p = done[index(x, y, z)];
		return p == UNSET ? Double.NaN : p / SCALE;
	}

	private int gx(double wx) {
		return (int) Math.floor(wx) - origin.getX() + H;
	}

	private int gy(double wy) {
		return (int) Math.floor(wy) - origin.getY() + H;
	}

	private int gz(double wz) {
		return (int) Math.floor(wz) - origin.getZ() + H;
	}

	/** The shortest path to any block {@code box} is in (NaN if none is reached), and that block. */
	private double[] nearest(AABB box) {
		double best = Double.NaN;
		int bx = 0;
		int by = 0;
		int bz = 0;
		for (int x = gx(box.minX + 0.01); x <= gx(box.maxX - 0.01); x++) {
			for (int y = gy(box.minY + 0.01); y <= gy(box.maxY - 0.01); y++) {
				for (int z = gz(box.minZ + 0.01); z <= gz(box.maxZ - 0.01); z++) {
					double p = path(x, y, z);
					if (!Double.isNaN(p) && (Double.isNaN(best) || p < best)) {
						best = p;
						bx = x;
						by = y;
						bz = z;
					}
				}
			}
		}
		return new double[] {best, bx, by, bz};
	}

	/** How far the wave travels to reach {@code box} (blocks), or NaN if it can't. */
	public double path(AABB box) {
		return nearest(box)[0];
	}

	/**
	 * The distance the damage goes by ({@link PlantedC4#damageAt}): the straight distance from {@code centre}, plus the
	 * detour the wave makes around walls times (1 + {@link #CORNER}). NaN if the wave can't get there.
	 */
	public double effective(AABB box, Vec3 centre) {
		double[] n = nearest(box);
		if (Double.isNaN(n[0])) {
			return Double.NaN;
		}
		double straight = box.getCenter().distanceTo(centre);
		double detour = Math.max(0, n[0] - octile(n[1] - H, n[2] - H, n[3] - H));
		return straight + detour * (1 + CORNER);
	}

	/**
	 * Past the grid (beyond the blast's reach, where the wave is still heard and felt, harmlessly): the path to where the
	 * straight line from the bomb leaves the grid, then straight on, if nothing solid stands in that last stretch.
	 * {path, detour} (the detour made inside the grid), or null if walled off. Inside the grid: null.
	 */
	private double[] beyond(AABB box, Vec3 centre) {
		Vec3 to = box.getCenter();
		int cx = gx(to.x);
		int cy = gy(to.y);
		int cz = gz(to.z);
		if (cx >= 0 && cy >= 0 && cz >= 0 && cx < N && cy < N && cz < N) {
			return null;
		}
		Vec3 d = to.subtract(centre);
		double edge = (H - 1.5) / Math.max(Math.abs(d.x), Math.max(Math.abs(d.y), Math.abs(d.z)));
		Vec3 out = centre.add(d.scale(edge));
		double best = Double.NaN;
		int bx = 0;
		int by = 0;
		int bz = 0;
		for (int x = gx(out.x) - 1; x <= gx(out.x) + 1; x++) {
			for (int y = gy(out.y) - 1; y <= gy(out.y) + 1; y++) {
				for (int z = gz(out.z) - 1; z <= gz(out.z) + 1; z++) {
					double p = path(x, y, z);
					if (!Double.isNaN(p) && (Double.isNaN(best) || p < best)) {
						best = p;
						bx = x;
						by = y;
						bz = z;
					}
				}
			}
		}
		if (Double.isNaN(best) || level.clip(new ClipContext(out, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
				.getType() != HitResult.Type.MISS) {
			return null;
		}
		return new double[] {best + out.distanceTo(to), Math.max(0, best - octile(bx - H, by - H, bz - H))};
	}

	/**
	 * As {@link #path(AABB)}, on out to wherever the wave is heard ({@link #beyond}: past the grid, the rest of the way
	 * is taken as a straight line, walled off if anything solid is in it). For telling players it went past.
	 */
	public double pathFar(AABB box, Vec3 centre) {
		double near = path(box);
		if (!Double.isNaN(near)) {
			return near;
		}
		double[] far = beyond(box, centre);
		return far == null ? Double.NaN : far[0];
	}

	/** As {@link #effective}, on out to wherever the wave is heard (see {@link #pathFar}). */
	public double effectiveFar(AABB box, Vec3 centre) {
		double near = effective(box, centre);
		if (!Double.isNaN(near)) {
			return near;
		}
		double[] far = beyond(box, centre);
		return far == null ? Double.NaN : box.getCenter().distanceTo(centre) + far[1] * (1 + CORNER);
	}

	/** The path length across open space on this grid (26 neighbours): what a straight shot to that block costs. */
	static double octile(double x, double y, double z) {
		double[] v = {Math.abs(x), Math.abs(y), Math.abs(z)};
		Arrays.sort(v);
		double c = v[0];
		double b = v[1];
		double a = v[2];
		return Math.sqrt(3) * c + Math.sqrt(2) * (b - c) + (a - b);
	}

	/** Which way the wave is travelling where {@code box} is (unit vector; away from the bomb if unknown). */
	public Vec3 direction(AABB box, Vec3 centre) {
		double[] n = nearest(box);
		Vec3 away = box.getCenter().subtract(centre);
		away = away.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : away.normalize();
		if (Double.isNaN(n[0])) {
			return away;
		}
		int x = (int) n[1];
		int y = (int) n[2];
		int z = (int) n[3];
		double here = n[0];
		Vec3 g = new Vec3(slope(x, y, z, 1, 0, 0, here), slope(x, y, z, 0, 1, 0, here), slope(x, y, z, 0, 0, 1, here));
		return g.lengthSqr() < 1e-6 ? away : g.normalize();
	}

	/** The path's rise along one axis at a block (central difference, one-sided where a neighbour isn't reached). */
	private double slope(int x, int y, int z, int ax, int ay, int az, double here) {
		double up = path(x + ax, y + ay, z + az);
		double down = path(x - ax, y - ay, z - az);
		if (!Double.isNaN(up) && !Double.isNaN(down)) {
			return (up - down) / 2;
		}
		return !Double.isNaN(up) ? up - here : !Double.isNaN(down) ? here - down : 0;
	}

	/**
	 * Floor blocks the wave reaches (open, with solid ground under), every {@code stride} blocks across: their offset
	 * from {@link #origin} and their path and effective distances. For the shockwave's dust and the debug map.
	 */
	public List<float[]> floor(int stride, Vec3 centre) {
		List<float[]> out = new ArrayList<>();
		if (done == null) {
			return out;
		}
		for (int x = H % stride; x < N; x += stride) {
			for (int z = H % stride; z < N; z += stride) {
				for (int y = 1; y < N; y++) {
					int cell = index(x, y, z);
					if (done[cell] == UNSET || isOpenStatic(x, y - 1, z)) {
						continue;
					}
					double p = done[cell] / SCALE;
					Vec3 at = new Vec3(origin.getX() + x - H + 0.5, origin.getY() + y - H + 0.05, origin.getZ() + z - H + 0.5);
					double eff = at.distanceTo(centre) + Math.max(0, p - octile(x - H, y - H, z - H)) * (1 + CORNER);
					if (eff <= PlantedC4.BLAST_RADIUS) {
						out.add(new float[] {x - H, y - H, z - H, (float) p, (float) eff});
					}
				}
			}
		}
		return out;
	}

	/** Whether a block is open, looking it up if this pass hasn't (the floor scan reads blocks the pass didn't need). */
	private boolean isOpenStatic(int x, int y, int z) {
		int cell = index(x, y, z);
		if (open[cell] != 0) {
			return open[cell] == 1;
		}
		int wx = origin.getX() + x - H;
		int wy = origin.getY() + y - H;
		int wz = origin.getZ() + z - H;
		if (wy < level.getMinY() || wy >= level.getMaxY()) {
			return false;
		}
		m.set(wx, wy, wz);
		BlockState s = level.getBlockState(m);
		return s.isAir() || s.getCollisionShape(level, m).isEmpty();
	}
}
