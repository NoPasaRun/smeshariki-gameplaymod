package com.smeshariki.gameplay.current;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Поле течений мира (отдельное у каждой стороны — сервера и клиента: оба считают его одинаково по маркерам и воде).
 * Каждый маркер раздаёт своё направление по связной воде; клетке достаётся ближайший маркер (обход в ширину не
 * дальше {@link #MAX_RADIUS} клеток от него).
 */
public final class CurrentField {
	/** Насколько далеко по воде маркер раздаёт течение, клеток. */
	public static final int MAX_RADIUS = 32;
	/** Через сколько тиков поле пересчитывается, даже если маркеры не менялись (вода могла измениться). */
	private static final int REFRESH_TICKS = 100;
	private static final int MAX_CELLS = 40_000;
	/** На скольких последних клетках к краю поля рисунок течения тает. */
	private static final int FADE_CELLS = 6;

	private static final Map<Level, CurrentField> FIELDS = Collections.synchronizedMap(new WeakHashMap<>());

	private final List<CurrentMarkerEntity> markers = new CopyOnWriteArrayList<>();
	private volatile Map<Long, Vec3> flow = Map.of();
	private volatile Map<CurrentMarkerEntity, Surface> surfaces = Map.of();
	private int signature;
	private int age;

	public static void add(CurrentMarkerEntity marker) {
		FIELDS.computeIfAbsent(marker.level(), l -> new CurrentField()).markers.add(marker);
	}

	public static void remove(CurrentMarkerEntity marker) {
		CurrentField field = FIELDS.get(marker.level());
		if (field == null) return;
		field.markers.remove(marker);
		if (field.markers.isEmpty()) FIELDS.remove(marker.level());
	}

	/**
	 * Забывает все поля. Вызывается при остановке сервера и выходе клиента из мира: карта статическая, а мир, открытый
	 * заново в том же запуске (например, после отката версии), должен считать поле с нуля, не цепляясь за старые уровни.
	 */
	public static void clear() {
		FIELDS.clear();
	}

	/** Поле мира или null, если в нём нет маркеров. */
	public static CurrentField of(Level level) {
		return FIELDS.get(level);
	}

	public boolean isEmpty() {
		return flow.isEmpty();
	}

	/** Единичное направление течения в клетке или null, если там его нет. */
	public Vec3 flowAt(BlockPos pos) {
		return flow.get(pos.asLong());
	}

	/**
	 * Открытая поверхность воды, которой течение раздаёт маркер (клетки воды, над которыми не вода): по ней клиент рисует
	 * бурление. Для каждой клетки — x, z, высота поверхности, прозрачность к краю поля (0–1) и свет над водой. Только у клиента.
	 */
	public record Surface(int[] xs, int[] zs, float[] ys, float[] fade, int[] light) {
		public int size() {
			return xs.length;
		}
	}

	/** Поверхность течения маркера или null. */
	public Surface surfaceOf(CurrentMarkerEntity marker) {
		return surfaces.get(marker);
	}

	public void tick(Level level) {
		int sig = 1;
		for (CurrentMarkerEntity m : markers) sig = sig * 31 + Objects.hash(m.blockPosition(), m.yaw());
		if (sig != signature || ++age >= REFRESH_TICKS) {
			signature = sig;
			age = 0;
			rebuild(level);
		}
	}

	private record Node(BlockPos pos, int source, int dist) {
	}

	private void rebuild(Level level) {
		List<CurrentMarkerEntity> sources = markers.stream().filter(m -> !m.isRemoved()).toList();
		Map<Long, Integer> owner = new HashMap<>();
		Map<Long, Integer> distance = new HashMap<>();
		ArrayDeque<Node> queue = new ArrayDeque<>();
		for (int i = 0; i < sources.size(); i++) {
			BlockPos pos = sources.get(i).blockPosition();
			if (!isWater(level, pos) || owner.containsKey(pos.asLong())) continue;
			owner.put(pos.asLong(), i);
			distance.put(pos.asLong(), 0);
			queue.add(new Node(pos, i, 0));
		}
		while (!queue.isEmpty() && owner.size() < MAX_CELLS) {
			Node n = queue.poll();
			if (n.dist() >= MAX_RADIUS) continue;
			for (Direction d : Direction.values()) {
				BlockPos next = n.pos().relative(d);
				if (owner.containsKey(next.asLong()) || !isWater(level, next)) continue;
				owner.put(next.asLong(), n.source());
				distance.put(next.asLong(), n.dist() + 1);
				queue.add(new Node(next, n.source(), n.dist() + 1));
			}
		}
		Map<Long, Vec3> result = new HashMap<>();
		owner.forEach((key, source) -> result.put(key, sources.get(source).direction()));
		this.flow = result;
		if (level.isClientSide()) this.surfaces = surfaces(level, sources, owner, distance);
	}

	private static Map<CurrentMarkerEntity, Surface> surfaces(Level level, List<CurrentMarkerEntity> sources, Map<Long, Integer> owner, Map<Long, Integer> distance) {
		List<List<Long>> cells = new java.util.ArrayList<>();
		for (int i = 0; i < sources.size(); i++) cells.add(new java.util.ArrayList<>());
		BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
		owner.forEach((key, source) -> {
			BlockPos pos = BlockPos.of(key);
			if (!level.getFluidState(above.setWithOffset(pos, Direction.UP)).is(FluidTags.WATER)) cells.get(source).add(key);
		});
		Map<CurrentMarkerEntity, Surface> result = new HashMap<>();
		for (int i = 0; i < sources.size(); i++) {
			List<Long> list = cells.get(i);
			int n = list.size();
			int[] xs = new int[n], zs = new int[n], light = new int[n];
			float[] ys = new float[n], fade = new float[n];
			for (int k = 0; k < n; k++) {
				BlockPos pos = BlockPos.of(list.get(k));
				FluidState fluid = level.getFluidState(pos);
				xs[k] = pos.getX();
				zs[k] = pos.getZ();
				ys[k] = pos.getY() + fluid.getHeight(level, pos);
				fade[k] = Math.min(1f, (MAX_RADIUS - distance.getOrDefault(list.get(k), 0)) / (float) FADE_CELLS);
				above.setWithOffset(pos, Direction.UP);
				// упаковка как у карты света: блочный свет в битах 4–7, небесный в 20–23
				light[k] = level.getBrightness(LightLayer.BLOCK, above) << 4 | level.getBrightness(LightLayer.SKY, above) << 20;
			}
			result.put(sources.get(i), new Surface(xs, zs, ys, fade, light));
		}
		return result;
	}

	private static boolean isWater(Level level, BlockPos pos) {
		return level.isLoaded(pos) && level.getFluidState(pos).is(FluidTags.WATER);
	}
}
