package com.smeshariki.gameplay.slide;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Пологий скат 22,5°. {@code part} задаёт высоту низкого края ({@code part}/16 блока), поэтому скат идёт без ступеней через сколько
 * угодно блоков. Высокий край может быть выше блока (до 1,35), сверху нужен воздух.
 */
public class GentleSlideBlock extends SlideBlock {
	public static final MapCodec<GentleSlideBlock> CODEC = simpleCodec(GentleSlideBlock::new);
	public static final IntegerProperty PART = IntegerProperty.create("part", 0, 15);
	private static final int STEPS = 16;
	private static final double RISE = 0.41421356;
	private static final VoxelShape[][] SHAPES = new VoxelShape[4][16];

	static {
		for (Direction d : Direction.Plane.HORIZONTAL)
			for (int p = 0; p < 16; p++) {
				VoxelShape shape = Shapes.empty();
				for (int i = 0; i < STEPS; i++)
					shape = Shapes.or(shape, slice(d, (double) i / STEPS, (double) (i + 1) / STEPS, p / 16.0 + RISE * (STEPS - i) / STEPS));
				SHAPES[d.get2DDataValue()][p] = shape;
			}
	}

	/** Полоса от {@code t0} до {@code t1} вдоль спуска, высотой {@code h}. */
	static VoxelShape slice(Direction d, double t0, double t1, double h) {
		return switch (d) {
			case EAST -> Shapes.box(t0, 0, 0, t1, h, 1);
			case WEST -> Shapes.box(1 - t1, 0, 0, 1 - t0, h, 1);
			case SOUTH -> Shapes.box(0, 0, t0, 1, h, t1);
			default -> Shapes.box(0, 0, 1 - t1, 1, h, 1 - t0);
		};
	}

	public GentleSlideBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(PART);
	}

	/** Продолжает скат, если выше по склону уже стоит такой же наклон той же стороны (материал может быть другим). */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction facing = context.getHorizontalDirection();
		BlockPos pos = context.getClickedPos();
		BlockPos behind = pos.relative(facing.getOpposite());
		for (int dy : new int[] {0, 1, -1}) {
			BlockState s = context.getLevel().getBlockState(behind.above(dy));
			if (s.getBlock() instanceof GentleSlideBlock && s.getValue(FACING) == facing) {
				int sixteenths = s.getValue(PART) + 16 * dy - 7;
				return defaultBlockState().setValue(FACING, facing).setValue(PART, Math.floorMod(sixteenths, 16));
			}
		}
		return defaultBlockState().setValue(FACING, facing);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(FACING).get2DDataValue()][state.getValue(PART)];
	}

	/** Основание под поверхностью, на случай тел, которые скаты не замечают; поверхность считает {@link SlopeSurface}. */
	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		int p = state.getValue(PART);
		return p == 0 ? Shapes.empty() : Shapes.box(0, 0, 0, 1, p / 16.0, 1);
	}

	@Override
	protected double surfaceAt(BlockState state, double t) {
		return state.getValue(PART) / 16.0 + RISE * (1 - t);
	}

	@Override
	public double accel() {
		return 0.105;
	}

	@Override
	public double maxSpeed() {
		return 1.2;
	}

	@Override
	public double tangent() {
		return RISE;
	}
}
