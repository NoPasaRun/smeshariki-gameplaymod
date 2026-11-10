package com.smeshariki.gameplay.slide;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Скат горки: наклонная поверхность в 45°, высокий край сзади, низкий — в сторону {@code facing}. Материал (лёд, камень, земля) определяет только вид и
 * звук, скользят все одинаково. Блок задаёт форму и скорости; столкновения и скольжение — в {@link SlopeSurface}, рисует клин клиентский {@code SlideModels}.
 */
public class SlideBlock extends Block {
	public static final MapCodec<SlideBlock> CODEC = simpleCodec(SlideBlock::new);
	/** Сторона, в которую скат опускается. */
	public static final EnumProperty<Direction> FACING = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
	private static final int STEPS = 16;
	/** Прибавка скорости за тик и предел скорости вдоль ската, блоков за тик. */
	private static final double ACCEL = 0.18;
	private static final double MAX_SPEED = 1.65;

	private static final VoxelShape[] SHAPES = new VoxelShape[4];

	static {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			VoxelShape shape = Shapes.empty();
			for (int i = 0; i < STEPS; i++) {
				double t0 = (double) i / STEPS, t1 = (double) (i + 1) / STEPS, h = (double) (STEPS - i) / STEPS;
				VoxelShape step = switch (d) {
					case EAST -> Shapes.box(t0, 0, 0, t1, h, 1);
					case WEST -> Shapes.box(1 - t1, 0, 0, 1 - t0, h, 1);
					case SOUTH -> Shapes.box(0, 0, t0, 1, h, t1);
					default -> Shapes.box(0, 0, 1 - t1, 1, h, 1 - t0);
				};
				shape = Shapes.or(shape, step);
			}
			SHAPES[d.get2DDataValue()] = shape;
		}
	}

	public SlideBlock(Properties properties) {
		super(properties);
		if (stateDefinition.any().hasProperty(FACING)) registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** Скат уходит вниз по взгляду игрока. */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(FACING).get2DDataValue()];
	}

	/** Обычного столкновения нет: поверхность считает {@link SlopeSurface}. */
	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	/** Камера от третьего лица упирается в форму блока и не заходит внутрь ската. */
	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return getShape(state, level, pos, context);
	}

	/** Прибавка скорости за тик, блоков за тик. */
	public double accel() {
		return ACCEL;
	}

	/** Предел скорости, блоков за тик. */
	public double maxSpeed() {
		return MAX_SPEED;
	}

	/** Прибавка скорости за тик в этом состоянии; у скатов с одним уклоном постоянная. */
	public double accel(BlockState state) {
		return accel();
	}

	/** Предел скорости в этом состоянии. */
	public double maxSpeed(BlockState state) {
		return maxSpeed();
	}

	/** Единичный вектор вниз по склону в плоскости XZ. */
	public Vec3 downhill(BlockState state) {
		return Vec3.atLowerCornerOf(state.getValue(FACING).getUnitVec3i());
	}

	/** Наклон именно этого состояния (тангенс). */
	public double slope(BlockState state) {
		return tangent();
	}

	/** Спуск на блок пути: тангенс угла ската. */
	public double tangent() {
		return 1.0;
	}

	/** Высота поверхности внутри блока (доли блока от его низа) в точке пути {@code t}: 0 у высокого края, 1 у низкого; NaN, если здесь поверхности нет. */
	protected double surfaceAt(BlockState state, double t) {
		return 1 - t;
	}

	/** Высота поверхности в точке блока ({@code fx}, {@code fz} от 0 до 1). */
	public double surface(BlockState state, double fx, double fz) {
		double t = switch (state.getValue(FACING)) {
			case EAST -> fx;
			case WEST -> 1 - fx;
			case SOUTH -> fz;
			default -> 1 - fz;
		};
		return surfaceAt(state, t);
	}

	/** Форма поверхности вдоль спуска: точки (путь от 0 до 1, высота), между ними прямые. По ней рисуется клин. */
	public double[][] profile(BlockState state) {
		return new double[][] {{0, surfaceAt(state, 0)}, {1, surfaceAt(state, 1)}};
	}

	/** Самая высокая точка поверхности на участке пути от {@code t0} до {@code t1} внутри блока; NaN, если поверхности на нём нет. */
	protected double highestOn(BlockState state, double t0, double t1) {
		return surfaceAt(state, t0);
	}

	/** Самая высокая точка поверхности под прямоугольником в долях блока (может выходить за блок); NaN, если он блока не задевает или поверхности там нет. */
	public double highest(BlockState state, double x0, double x1, double z0, double z1) {
		if (x1 <= 0 || x0 >= 1 || z1 <= 0 || z0 >= 1) return Double.NaN;
		x0 = Math.max(x0, 0);
		x1 = Math.min(x1, 1);
		z0 = Math.max(z0, 0);
		z1 = Math.min(z1, 1);
		return switch (state.getValue(FACING)) {
			case EAST -> highestOn(state, x0, x1);
			case WEST -> highestOn(state, 1 - x1, 1 - x0);
			case SOUTH -> highestOn(state, z0, z1);
			default -> highestOn(state, 1 - z1, 1 - z0);
		};
	}
}
