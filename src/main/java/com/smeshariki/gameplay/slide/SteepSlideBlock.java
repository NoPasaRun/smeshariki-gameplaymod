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
 * Крутой скат: 5 блоков вниз на 2 блока пути (68,2°, ближайшее к 67,5° с целым периодом). В одном блоке высоты поверхность проходит 0,4 блока
 * пути; {@code part} задаёт, откуда она начинается: {@code a = −0,2 + 0,2·part} от заднего края. До {@code a} блок полный, после прохода поверхности пустой.
 * За блоком той же высоты ниже по склону — {@code part − 5}; под блоком — {@code part + 2}.
 */
public class SteepSlideBlock extends SlideBlock {
	public static final MapCodec<SteepSlideBlock> CODEC = simpleCodec(SteepSlideBlock::new);
	public static final IntegerProperty PART = IntegerProperty.create("part", 0, 5);
	private static final VoxelShape[][] SHAPES = new VoxelShape[4][6];

	static {
		for (Direction d : Direction.Plane.HORIZONTAL)
			for (int p = 0; p < 6; p++) {
				double a = -0.2 + 0.2 * p;
				VoxelShape shape = a > 1e-6 ? GentleSlideBlock.slice(d, 0, a, 1) : Shapes.empty();
				for (int j = 0; j < 4; j++) {
					double t0 = Math.max(a + 0.1 * j, 0), t1 = Math.min(a + 0.1 * (j + 1), 1);
					if (t1 > t0 + 1e-6) shape = Shapes.or(shape, GentleSlideBlock.slice(d, t0, t1, 1 - j / 4.0));
				}
				SHAPES[d.get2DDataValue()][p] = shape;
			}
	}

	public SteepSlideBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(PART, 1));
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

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(FACING).get2DDataValue()][state.getValue(PART)];
	}

	@Override
	protected double surfaceAt(BlockState state, double t) {
		double a = -0.2 + 0.2 * state.getValue(PART);
		return t < a || t > a + 0.4 ? Double.NaN : 1 - (t - a) / 0.4;
	}

	/** В целиковой части блока поверхность на его верхе, за проходом поверхности блок пуст. */
	@Override
	protected double highestOn(BlockState state, double t0, double t1) {
		double a = -0.2 + 0.2 * state.getValue(PART);
		if (t1 <= a) return 1;
		if (t0 < a) return 1;
		return surfaceAt(state, t0);
	}

	@Override
	public double[][] profile(BlockState state) {
		double a = -0.2 + 0.2 * state.getValue(PART), b = a + 0.4;
		java.util.List<double[]> out = new java.util.ArrayList<>();
		out.add(new double[] {0, a > 0 ? 1 : surfaceAt(state, 0)});
		if (a > 1e-6) out.add(new double[] {a, 1});
		if (b < 1 - 1e-6) {
			out.add(new double[] {b, 0});
			out.add(new double[] {1, 0});
		} else out.add(new double[] {1, surfaceAt(state, 1)});
		return out.toArray(new double[0][]);
	}

	/** Продолжает скат: под таким же блоком — {@code part + 2}, ниже по склону на той же высоте — {@code part − 5}. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction facing = context.getHorizontalDirection();
		BlockPos pos = context.getClickedPos();
		BlockState above = context.getLevel().getBlockState(pos.above());
		if (above.getBlock() instanceof SteepSlideBlock && above.getValue(FACING) == facing && above.getValue(PART) + 2 <= 5)
			return defaultBlockState().setValue(FACING, facing).setValue(PART, above.getValue(PART) + 2);
		BlockState behind = context.getLevel().getBlockState(pos.relative(facing.getOpposite()));
		if (behind.getBlock() instanceof SteepSlideBlock && behind.getValue(FACING) == facing && behind.getValue(PART) >= 5)
			return defaultBlockState().setValue(FACING, facing).setValue(PART, behind.getValue(PART) - 5);
		return defaultBlockState().setValue(FACING, facing);
	}

	@Override
	public double accel() {
		return 0.27;
	}

	@Override
	public double maxSpeed() {
		return 2.4;
	}

	@Override
	public double tangent() {
		return 2.5;
	}
}
