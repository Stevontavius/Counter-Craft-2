package dev.countercraft.block;

import dev.countercraft.movement.CsMove;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A surf ramp: a stair's 45 degree slope, rising towards {@link #FACING}. Players on CS movement surf it: to them it
 * has no Minecraft collision, and CS movement collides with the exact slope itself ({@link #push}): the ramp never
 * counts as ground, so gravity slides you down it and strafing into it holds you up, as on CS surf maps. Everything
 * else (vanilla movement, mobs, items) meets a fine staircase and walks up it like stairs.
 */
public class SurfRampBlock extends HorizontalDirectionalBlock {
	private static final Map<Direction, VoxelShape> COLLISION = steps(8);
	private static final Map<Direction, VoxelShape> OUTLINE = steps(4);

	public SurfRampBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** Like stairs: it rises the way the player faces. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext ctx) {
		return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		return OUTLINE.get(state.getValue(FACING));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
		if (ctx instanceof EntityCollisionContext e && e.getEntity() instanceof Player p && CsMove.enabled(p)) {
			return Shapes.empty(); // CS movement meets the slope itself
		}
		return COLLISION.get(state.getValue(FACING));
	}

	/** Landing on a ramp isn't a fall (CS2 takes no fall damage on surf ramps); see also {@link SurfRamps} for CS movement. */
	@Override
	public void fallOn(net.minecraft.world.level.Level level, BlockState state, BlockPos pos, net.minecraft.world.entity.Entity entity, double fallDistance) {
		entity.resetFallDistance();
	}

	/** A staircase of {@code n} steps under (and up to) the slope, for each facing. */
	private static Map<Direction, VoxelShape> steps(int n) {
		Map<Direction, VoxelShape> out = new EnumMap<>(Direction.class);
		double s = 16.0 / n;
		for (Direction f : Direction.Plane.HORIZONTAL) {
			VoxelShape shape = Shapes.empty();
			for (int i = 0; i < n; i++) {
				// facing north: step i spans z [i s, (i + 1) s], as high as the slope at its high side
				shape = Shapes.or(shape, box(f, 0, 0, i * s, 16, 16 - i * s, (i + 1) * s));
			}
			out.put(f, shape.optimize());
		}
		return out;
	}

	/** A box given for a ramp facing north (rising towards z = 0), turned to {@code f}. */
	private static VoxelShape box(Direction f, double x0, double y0, double z0, double x1, double y1, double z1) {
		double[] a = turn(f, x0, z0);
		double[] b = turn(f, x1, z1);
		return Block.box(Math.min(a[0], b[0]), y0, Math.min(a[1], b[1]), Math.max(a[0], b[0]), y1, Math.max(a[1], b[1]));
	}

	/** (x, z) in a north-facing ramp's frame (0..16) to the frame of a ramp facing {@code f}. */
	private static double[] turn(Direction f, double x, double z) {
		return switch (f) {
			case EAST -> new double[] {16 - z, x};
			case SOUTH -> new double[] {16 - x, 16 - z};
			case WEST -> new double[] {z, 16 - x};
			default -> new double[] {x, z};
		};
	}

	/**
	 * How to push a box out of this ramp's solid wedge (the triangular prism under the slope), or null if they don't
	 * meet: the shortest way out through the slope or through a face whose neighbour isn't solid (a face against a block
	 * or another ramp is inside the ramp, never a way out: so sliding across a row of ramps, or down a ramp built of
	 * several, never snags on the seams). Separating-axis test on the box's axes and the slope's normal, which for a box
	 * and this prism is exact. {@code normal[0]} receives the direction of the push.
	 */
	public static Vec3 push(BlockGetter level, BlockPos pos, BlockState state, AABB box, Vec3[] normal) {
		Direction f = state.getValue(FACING);
		// the prism's corners: the low edge, the bottom back edge, the top back edge, at both ends
		Vec3 back = Vec3.atLowerCornerOf(f.getUnitVec3i()); // towards the high side
		Vec3 slope = new Vec3(-back.x, 1, -back.z).normalize(); // out of the slope: up and towards the low side
		Vec3[] corners = new Vec3[6];
		int k = 0;
		for (double side : new double[] {0, 1}) {
			double[] low = turn(f, side * 16, 16);
			double[] high = turn(f, side * 16, 0);
			corners[k++] = new Vec3(pos.getX() + low[0] / 16, pos.getY(), pos.getZ() + low[1] / 16);
			corners[k++] = new Vec3(pos.getX() + high[0] / 16, pos.getY(), pos.getZ() + high[1] / 16);
			corners[k++] = new Vec3(pos.getX() + high[0] / 16, pos.getY() + 1, pos.getZ() + high[1] / 16);
		}
		Vec3[] axes = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), slope};
		Vec3 best = null;
		double bestDepth = Double.MAX_VALUE;
		Vec3 centre = box.getCenter();
		for (Vec3 a : axes) {
			double pMin = Double.MAX_VALUE;
			double pMax = -Double.MAX_VALUE;
			for (Vec3 c : corners) {
				double d = c.dot(a);
				pMin = Math.min(pMin, d);
				pMax = Math.max(pMax, d);
			}
			double r = (box.getXsize() * Math.abs(a.x) + box.getYsize() * Math.abs(a.y) + box.getZsize() * Math.abs(a.z)) / 2;
			double bc = centre.dot(a);
			if (bc - r >= pMax || bc + r <= pMin) {
				return null; // separated on this axis
			}
			// the two ways out along this axis: past the prism's far side (+a) or its near side (-a)
			for (int sign : new int[] {1, -1}) {
				Vec3 dir = a.scale(sign);
				double depth = sign > 0 ? pMax - (bc - r) : (bc + r) - pMin;
				if (depth <= 0 || depth >= bestDepth || !open(level, pos, f, dir, slope)) {
					continue;
				}
				best = dir;
				bestDepth = depth;
			}
		}
		if (best == null) {
			return null;
		}
		normal[0] = best;
		return best.scale(bestDepth + 1e-6);
	}

	/**
	 * Can a box leave the wedge this way? Through the slope, always; through the bottom, the back or a side, only if the
	 * block there isn't solid or another ramp. Other directions (into the slope's own corner) never.
	 */
	private static boolean open(BlockGetter level, BlockPos pos, Direction f, Vec3 dir, Vec3 slope) {
		if (dir.distanceToSqr(slope) < 1e-6) {
			return true;
		}
		Direction d = Direction.getApproximateNearest(dir.x, dir.y, dir.z);
		if (Math.abs(dir.get(d.getAxis())) < 0.999) {
			return false;
		}
		// the open sides of the wedge are the slope's: a box above the low edge leaves upwards or forwards through it
		if (d == Direction.UP || d == f.getOpposite()) {
			return false;
		}
		BlockPos n = pos.relative(d);
		BlockState there = level.getBlockState(n);
		return !(there.getBlock() instanceof SurfRampBlock) && !there.isCollisionShapeFullBlock(level, n);
	}
}
