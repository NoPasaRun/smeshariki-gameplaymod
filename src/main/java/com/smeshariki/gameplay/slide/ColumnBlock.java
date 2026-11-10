package com.smeshariki.gameplay.slide;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Колонна: блоки друг на друге сами складываются в два кола, снизу и сверху, остриями навстречу; в середине высоты они соединяются тонкой шейкой. Основание растёт с высотой
 * колонны до ширины большого кола, поэтому при 20 блоках это два больших кола по 10. Каждый блок знает своё место: {@code index} снизу и {@code height} всей колонны (не больше {@link #MAX_HEIGHT}).
 * Столкновение есть, но только в пределах своей клетки: шире блока ваниль форму не проверяет, поэтому широкое основание за её краем проходимо.
 */
public class ColumnBlock extends Block {
	public static final MapCodec<ColumnBlock> CODEC = simpleCodec(ColumnBlock::new);
	public static final int MAX_HEIGHT = 24;
	public static final IntegerProperty INDEX = IntegerProperty.create("index", 0, MAX_HEIGHT - 1);
	public static final IntegerProperty HEIGHT = IntegerProperty.create("height", 1, MAX_HEIGHT);

	/** Радиус основания: столько блоков высоты на блок радиуса, но не уже блока и не шире большого кола; радиус шейки. */
	private static final double HEIGHT_PER_RADIUS = 8, MIN_RADIUS = 0.5, MAX_RADIUS = 1.25, NECK = 0.12;
	/** Профиль половины колонны от основания к середине (как у кола): доля пути и доля радиуса. */
	private static final double[][] PROFILE = { { 0, 1 }, { 0.3, 0.8 }, { 0.55, 0.58 }, { 0.8, 0.3 }, { 1, 0 } };
	/** Сколько поясов в каждом блоке. */
	private static final int SLICES = 4;
	private static final VoxelShape[][] SHAPES = shapes();

	public ColumnBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(INDEX, 0).setValue(HEIGHT, 1));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(INDEX, HEIGHT);
	}

	private static double radiusAt(double position, int height) {
		// position — доля высоты колонны; path — путь от ближайшего основания к середине, от 0 до 1
		double path = 1 - Math.abs(position - 0.5) * 2;
		double fraction = 0;
		for (int i = 0; i + 1 < PROFILE.length; i++) {
			if (path <= PROFILE[i + 1][0]) {
				fraction = PROFILE[i][1] + (PROFILE[i + 1][1] - PROFILE[i][1]) * (path - PROFILE[i][0]) / (PROFILE[i + 1][0] - PROFILE[i][0]);
				break;
			}
		}
		double base = Math.clamp(height / HEIGHT_PER_RADIUS, MIN_RADIUS, MAX_RADIUS);
		return NECK + (base - NECK) * fraction;
	}

	/** Кольца формы блока {@code index} в колонне высотой {@code height}: высота в клетке и радиус. Середина колонны всегда попадает на кольцо. */
	public static double[][] rings(int index, int height) {
		boolean split = height % 2 == 1 && index == height / 2 && SLICES % 2 == 1;
		int count = split ? SLICES + 1 : SLICES;
		double[][] out = new double[count + 1][];
		for (int i = 0; i <= count; i++) {
			double y = (double) i / count;
			out[i] = new double[] { y, radiusAt((index + y) / height, height) };
		}
		return out;
	}

	private static VoxelShape[][] shapes() {
		VoxelShape[][] all = new VoxelShape[MAX_HEIGHT + 1][];
		for (int height = 1; height <= MAX_HEIGHT; height++) {
			all[height] = new VoxelShape[height];
			for (int index = 0; index < height; index++) {
				double[][] rings = rings(index, height);
				VoxelShape shape = Shapes.empty();
				for (int i = 0; i + 1 < rings.length; i++) {
					double half = Math.min(0.5, Math.max(rings[i][1], rings[i + 1][1]));
					shape = Shapes.or(shape, Shapes.box(0.5 - half, rings[i][0], 0.5 - half, 0.5 + half, rings[i + 1][0], 0.5 + half));
				}
				all[height][index] = shape;
			}
		}
		return all;
	}

	private BlockState placed(LevelReader level, BlockPos pos) {
		int below = 0, above = 0;
		while (below < MAX_HEIGHT - 1 && level.getBlockState(pos.below(below + 1)).is(this)) below++;
		while (above < MAX_HEIGHT - 1 && level.getBlockState(pos.above(above + 1)).is(this)) above++;
		int height = Math.min(MAX_HEIGHT, below + above + 1);
		return defaultBlockState().setValue(HEIGHT, height).setValue(INDEX, Math.min(below, height - 1));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return placed(context.getLevel(), context.getClickedPos());
	}

	/** Изменившийся блок меняет своё состояние, и это будит следующего соседа: колонна пересчитывается целиком. */
	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighbor, RandomSource random) {
		return direction.getAxis() == Direction.Axis.Y ? placed(level, pos) : state;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		int height = state.getValue(HEIGHT);
		return SHAPES[height][Math.min(state.getValue(INDEX), height - 1)];
	}
}
