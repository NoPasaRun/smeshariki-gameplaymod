package com.smeshariki.gameplay.raft;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.current.CurrentField;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Бревно на реке как механика: тип сущности {@code gameplaymod:log_raft}, формы брёвен и создание бревна на маршруте. Реку (маршруты, когда и где пускать поток)
 * ведёт WorldMod: он сообщает пути через {@link #setPaths} и зовёт {@link #spawn}. Бревно само идёт по пути с постоянной скоростью ({@link LogRaftEntity}).
 */
public final class Logs {
	public static EntityType<LogRaftEntity> TYPE;

	/** Маршрут реки: длина вдоль линии и точка по пройденному пути. */
	public interface Path {
		double length();

		/** {@code out} получает x, y, z центра основания; {@code lane} от −1 до 1 поперёк, {@code shift} — сдвиг поперёк в блоках. */
		void at(double s, double lane, double shift, double[] out);
	}

	/** Пути по номеру маршрута на этой стороне (сервер или клиент); {@code null} — маршрута нет или он ещё не пришёл. */
	@FunctionalInterface
	public interface Paths {
		@Nullable Path get(Level level, int index);
	}

	private static volatile Paths paths = (level, index) -> null;

	/** Формы брёвен, не длиннее 4 блоков по x и z: 4 в начале реки как раз помещаются, русло там в 4–5 блоков. */
	private static final int[][][] SHAPES = {
			{ { 0, 0 }, { 1, 0 }, { 2, 0 }, { 3, 0 } },
			{ { 0, 0 }, { 0, 1 }, { 0, 2 }, { 0, 3 } },
			// лесенка 45° в обе стороны
			{ { 0, 0 }, { 1, 0 }, { 1, 1 }, { 2, 1 }, { 2, 2 }, { 3, 2 }, { 3, 3 } },
			{ { 0, 3 }, { 1, 3 }, { 1, 2 }, { 2, 2 }, { 2, 1 }, { 3, 1 }, { 3, 0 } },
			// лесенка под пологим углом к x и к z
			{ { 0, 0 }, { 1, 0 }, { 2, 0 }, { 2, 1 }, { 3, 1 } },
			{ { 0, 1 }, { 1, 1 }, { 2, 1 }, { 2, 0 }, { 3, 0 } },
			{ { 0, 0 }, { 0, 1 }, { 1, 1 }, { 1, 2 }, { 1, 3 } },
			{ { 1, 0 }, { 1, 1 }, { 0, 1 }, { 0, 2 }, { 0, 3 } },
	};

	private Logs() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "log_raft"));
		// дальность как у маркеров течения; позиционные пакеты клиенту не нужны, он считает путь сам
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<LogRaftEntity>of(LogRaftEntity::new, MobCategory.MISC)
				.sized(0.5f, 0.5f).clientTrackingRange(8).updateInterval(20).fireImmune().noSummon().noSave().build(key));
	}

	public static void setPaths(Paths provider) {
		paths = provider;
	}

	static @Nullable Path path(Level level, int index) {
		return paths.get(level, index);
	}

	/**
	 * Пустить бревно в начало маршрута. Начало должно быть на воде с течением (иначе реку перестроили) и в чанке, где обрабатываются сущности.
	 *
	 * @param start начало маршрута (x, y основания, z)
	 * @return создано ли бревно
	 */
	public static boolean spawn(ServerLevel level, int route, double[] start, float center, float phase, float shift, float sway, float wavelength) {
		BlockPos pos = BlockPos.containing(start[0], start[1] - 0.5, start[2]);
		CurrentField field = CurrentField.of(level);
		if (field == null || !level.isPositionEntityTicking(pos) || !level.getFluidState(pos).is(FluidTags.WATER) || field.flowAt(pos) == null) return false;
		RandomSource random = level.getRandom();
		LogRaftEntity log = new LogRaftEntity(TYPE, level);
		setCells(log, SHAPES[random.nextInt(SHAPES.length)]);
		log.launch(route, center, sway, wavelength, phase, shift);
		return level.addFreshEntity(log);
	}

	private static void setCells(RaftEntity log, int[][] shape) {
		int sx = 0, sz = 0;
		for (int[] c : shape) {
			sx = Math.max(sx, c[0] + 1);
			sz = Math.max(sz, c[1] + 1);
		}
		BlockState[] cells = new BlockState[sx * sz];
		java.util.Arrays.fill(cells, Blocks.AIR.defaultBlockState());
		for (int[] c : shape) {
			Direction.Axis axis = sz == 1 || has(shape, c[0] - 1, c[1]) || has(shape, c[0] + 1, c[1]) ? Direction.Axis.X : Direction.Axis.Z;
			cells[c[0] + sx * c[1]] = Blocks.JUNGLE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
		}
		log.setCells(sx, 1, sz, cells);
	}

	private static boolean has(int[][] shape, int x, int z) {
		for (int[] c : shape) if (c[0] == x && c[1] == z) return true;
		return false;
	}
}
