package com.smeshariki.gameplay.slide;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Скат с наклоном в любую сторону: поверхность задана высотами четырёх углов клетки (билинейно), в 1/16 блока. Соседние клетки делят углы, поэтому трасса
 * любой кривизны идёт без стыков и ступеней. Строится инструментом ({@code tools/tunnel_field.py} в SmesharikiWorldMod): рукой ставится ровной.
 * Свойства (у зеркального {@link WestFieldSlideBlock} отражены по x: подъём к западу): {@code part} — низший угол над низом блока, {@code dx}, {@code dz} — подъём к востоку и югу от северо-западного угла, {@code twist} — поправка юго-восточного угла.
 */
public class FieldSlideBlock extends SlideBlock {
	public static final MapCodec<FieldSlideBlock> CODEC = simpleCodec(FieldSlideBlock::new);
	private static final int DX_MAX = 12, DZ_MIN = -4, DZ_MAX = 12, TWIST_MIN = -1;
	public static final IntegerProperty PART = IntegerProperty.create("part", 0, 15);
	public static final IntegerProperty DX = IntegerProperty.create("dx", 0, DX_MAX);
	public static final IntegerProperty DZ = IntegerProperty.create("dz", 0, DZ_MAX - DZ_MIN);
	public static final IntegerProperty TWIST = IntegerProperty.create("twist", 0, 2);
	/** Самый крутой наклон формы: сколько блоков вниз на блок пути. */
	private static final double MAX_TANGENT = 0.8;
	/** Опорные наклоны: тангенс, прибавка скорости, предел скорости. Между ними — по прямой; 11,25° и 22,5° те же, что у обычных скатов. */
	private static final double[][] SPEEDS = {{0, 0, 0.96}, {0.19891237, 0.075, 0.96}, {0.41421356, 0.105, 1.2}, {1, 0.18, 1.65}};
	private static final Map<BlockState, VoxelShape> SHAPES = new ConcurrentHashMap<>();

	public FieldSlideBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(PART, 0).setValue(DX, 0).setValue(DZ, -DZ_MIN).setValue(TWIST, -TWIST_MIN));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	/** Стороны спуска здесь нет, она следует из углов. */
	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(PART, DX, DZ, TWIST);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState();
	}

	/** Результат настройки угла: новое состояние, сдвиг блока по высоте и углы в 1/16 блока от низа новой клетки (СЗ, СВ, ЮЗ, ЮВ). */
	public record Adjusted(BlockState state, int shift, int[] corners) {
	}

	/**
	 * Двигает ближайший к точке (fx, fz) угол на 1/16 блока. Состояния хранят закрутку не больше 1/16 и подъём в ограниченных пределах ({@code dx} только вверх к востоку),
	 * поэтому дальше угол тянет за собой соседний: ближний к точке, а вне пределов — и остальные.
	 *
	 * @param lower опустить, иначе поднять
	 * @return null, если двигать некуда
	 */
	public static Adjusted adjusted(BlockState state, double fx, double fz, boolean lower) {
		// зеркальный блок считается как обычный, отражённый по x: точка и результат переводятся туда и обратно
		boolean mirrored = isMirrored(state);
		if (mirrored) fx = 1 - fx;
		double[] h = baseCorners(state);
		int[] c = new int[4];
		for (int k = 0; k < 4; k++) c[k] = (int) Math.round(h[k] * 16);
		int[] before = c.clone();
		int i = (fx >= 0.5 ? 1 : 0) + (fz >= 0.5 ? 2 : 0), d = lower ? -1 : 1;
		// соседний угол, ближний к точке: по x это i^1, по z это i^2
		int along = Math.abs(fx - (1 - (i & 1))) <= Math.abs(fz - (1 - (i >> 1))) ? i ^ 1 : i ^ 2;
		c[i] += d;
		if (Math.abs(c[3] - c[1] - c[2] + c[0]) > 1) c[along] += d;
		// пределы подъёма: двигается тот угол, который не выбран
		int dx = c[1] - c[0];
		if (dx < 0) { if (d > 0) c[1] = c[0]; else c[0] = c[1]; }
		if (dx > DX_MAX) { if (d > 0) c[0] = c[1] - DX_MAX; else c[1] = c[0] + DX_MAX; }
		int dz = c[2] - c[0];
		if (dz < DZ_MIN) { if (d > 0) c[2] = c[0] + DZ_MIN; else c[0] = c[2] - DZ_MIN; }
		if (dz > DZ_MAX) { if (d > 0) c[0] = c[2] - DZ_MAX; else c[2] = c[0] + DZ_MAX; }
		// закрутку добирает юго-восточный угол, он единственный от неё зависит
		int tw = c[3] - c[1] - c[2] + c[0];
		if (tw > 1) c[3] -= tw - 1;
		if (tw < -1) c[3] -= tw + 1;
		if (java.util.Arrays.equals(c, before)) return null;
		dx = c[1] - c[0];
		dz = c[2] - c[0];
		if (dx < 0 || dx > DX_MAX || dz < DZ_MIN || dz > DZ_MAX) return null;
		tw = c[3] - c[1] - c[2] + c[0];
		int low = Math.min(Math.min(c[0], c[1]), Math.min(c[2], c[3]));
		int shift = Math.floorDiv(low, 16);
		for (int k = 0; k < 4; k++) c[k] -= shift * 16;
		BlockState next = state.setValue(DX, dx).setValue(DZ, dz - DZ_MIN).setValue(TWIST, tw - TWIST_MIN).setValue(PART, low - shift * 16);
		if (mirrored) c = new int[] {c[1], c[0], c[3], c[2]};
		return new Adjusted(next, shift, c);
	}

	/** Высоты углов над низом блока, в долях блока: северо-запад, северо-восток, юго-запад, юго-восток. */
	public static double[] corners(BlockState state) {
		double[] c = baseCorners(state);
		return isMirrored(state) ? new double[] {c[1], c[0], c[3], c[2]} : c;
	}

	/** Блок с подъёмом к западу вместо востока: состояния те же, но поверхность отражена по x. */
	protected boolean mirrored() {
		return false;
	}

	private static boolean isMirrored(BlockState state) {
		return state.getBlock() instanceof FieldSlideBlock block && block.mirrored();
	}

	/** Углы состояния в устройстве обычного блока (подъём к востоку), без отражения. */
	private static double[] baseCorners(BlockState state) {
		int dx = state.getValue(DX), dz = state.getValue(DZ) + DZ_MIN, se = dx + dz + state.getValue(TWIST) + TWIST_MIN;
		int low = Math.min(Math.min(0, dx), Math.min(dz, se)), part = state.getValue(PART);
		return new double[] {(part - low) / 16.0, (part + dx - low) / 16.0, (part + dz - low) / 16.0, (part + se - low) / 16.0};
	}

	private static double bilinear(double[] c, double fx, double fz) {
		return (c[0] * (1 - fx) + c[1] * fx) * (1 - fz) + (c[2] * (1 - fx) + c[3] * fx) * fz;
	}

	@Override
	public double surface(BlockState state, double fx, double fz) {
		return bilinear(corners(state), fx, fz);
	}

	@Override
	public double highest(BlockState state, double x0, double x1, double z0, double z1) {
		if (x1 <= 0 || x0 >= 1 || z1 <= 0 || z0 >= 1) return Double.NaN;
		x0 = Math.max(x0, 0);
		x1 = Math.min(x1, 1);
		z0 = Math.max(z0, 0);
		z1 = Math.min(z1, 1);
		double[] c = corners(state); // билинейная поверхность наибольшая в одном из углов прямоугольника
		return Math.max(Math.max(bilinear(c, x0, z0), bilinear(c, x1, z0)), Math.max(bilinear(c, x0, z1), bilinear(c, x1, z1)));
	}

	@Override
	public double slope(BlockState state) {
		return slope(corners(state));
	}

	/** Средний наклон клетки: тангенс. */
	private static double slope(double[] c) {
		return Math.hypot((c[1] + c[3] - c[0] - c[2]) / 2, (c[2] + c[3] - c[0] - c[1]) / 2);
	}

	private static double speedTable(double slope, int column) {
		for (int i = 1; i < SPEEDS.length; i++)
			if (slope <= SPEEDS[i][0]) {
				double k = (slope - SPEEDS[i - 1][0]) / (SPEEDS[i][0] - SPEEDS[i - 1][0]);
				return SPEEDS[i - 1][column] + k * (SPEEDS[i][column] - SPEEDS[i - 1][column]);
			}
		return SPEEDS[SPEEDS.length - 1][column];
	}

	@Override
	public double accel(BlockState state) {
		return speedTable(slope(corners(state)), 1);
	}

	@Override
	public double maxSpeed(BlockState state) {
		return speedTable(slope(corners(state)), 2);
	}

	@Override
	public Vec3 downhill(BlockState state) {
		double[] c = corners(state);
		double gx = (c[1] + c[3] - c[0] - c[2]) / 2, gz = (c[2] + c[3] - c[0] - c[1]) / 2, g = Math.hypot(gx, gz);
		return g < 1e-6 ? Vec3.ZERO : new Vec3(-gx / g, 0, -gz / g);
	}

	@Override
	public double tangent() {
		return MAX_TANGENT;
	}

	/** Форма для рамки выделения и камеры: столбики 4×4 под поверхностью; собирается при первом обращении, состояний много. */
	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.computeIfAbsent(state, s -> {
			double[] c = corners(s);
			VoxelShape shape = Shapes.empty();
			for (int i = 0; i < 4; i++)
				for (int j = 0; j < 4; j++)
					shape = Shapes.or(shape, Shapes.box(i / 4.0, 0, j / 4.0, (i + 1) / 4.0, Math.max(bilinear(c, (i + 0.5) / 4, (j + 0.5) / 4), 1 / 16.0), (j + 1) / 4.0));
			return shape;
		});
	}

	/** Основание под поверхностью, на случай тел, которые скаты не замечают; поверхность считает {@link SlopeSurface}. */
	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		int p = state.getValue(PART);
		return p == 0 ? Shapes.empty() : Shapes.box(0, 0, 0, 1, p / 16.0, 1);
	}
}
