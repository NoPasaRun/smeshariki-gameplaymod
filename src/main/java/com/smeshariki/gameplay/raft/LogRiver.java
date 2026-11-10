package com.smeshariki.gameplay.raft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.current.CurrentField;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Поток брёвен по рекам мира: по каждому маршруту из {@link LogRoutes} брёвна появляются в его начале, пока рядом с рекой есть игрок.
 * Работает только там, где в начале маршрута есть вода с течением: если реку перестроили, поток встанет.
 */
public final class LogRiver {
	public static EntityType<LogRaftEntity> TYPE;
	/** На таком расстоянии от реки игрок запускает поток, блоков. */
	private static final double ACTIVE_RADIUS = 160;
	/** Чанки реки держатся радиусом билета: при 3 сущности живут в самом чанке и его соседях. */
	private static final int TICKET_RADIUS = 3, TICKET_REFRESH = 20;
	private static final long TICKET_TIMEOUT = 60;

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

	private static TicketType ticket;

	/** Река: её маршрут, чанки вдоль него и когда на каждой траектории следующее бревно. */
	private record River(int index, LogRoute route, List<ChunkPos> chunks, long[] next) {
	}

	private static volatile List<River> rivers = List.of();

	private LogRiver() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "log_raft"));
		// дальность как у маркеров течения; позиционные пакеты клиенту не нужны, он считает путь сам
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<LogRaftEntity>of(LogRaftEntity::new, MobCategory.MISC)
				.sized(0.5f, 0.5f).clientTrackingRange(8).updateInterval(20).fireImmune().noSummon().noSave().build(key));
		ticket = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "log_river"),
				new TicketType(TICKET_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION));
		LogRoutes.init();
		ServerTickEvents.END_LEVEL_TICK.register(LogRiver::tick);
	}

	/** Маршруты мира загружены (при запуске сервера). */
	static void rebuild(List<LogRoute> routes) {
		List<River> list = new ArrayList<>();
		for (int r = 0; r < routes.size(); r++) {
			LogRoute route = routes.get(r);
			if (route.nodes() < 2) continue;
			Set<ChunkPos> along = new LinkedHashSet<>();
			for (int i = 0; i < route.nodes(); i++) along.add(new ChunkPos((int) Math.floor(route.x(i)) >> 4, (int) Math.floor(route.z(i)) >> 4));
			list.add(new River(r, route, new ArrayList<>(along), new long[route.flow().lanes().size()]));
		}
		rivers = List.copyOf(list);
	}

	private static void tick(ServerLevel level) {
		if (level.dimension() != Level.OVERWORLD || CurrentField.of(level) == null) return;
		long time = level.getGameTime();
		for (River river : rivers) tick(level, river, time);
	}

	private static void tick(ServerLevel level, River river, long time) {
		boolean refresh = time % TICKET_REFRESH == 0, spawn = false;
		for (long next : river.next()) spawn |= next <= time;
		if (!refresh && !spawn || !playerNear(level, river.route())) return;
		// игрок может быть в сотнях блоков от начала реки, а поток должен до него дойти
		if (refresh) for (ChunkPos pos : river.chunks()) level.getChunkSource().addTicketWithRadius(ticket, pos, TICKET_RADIUS);
		long[] next = river.next();
		LogRoute.Flow flow = river.route().flow();
		for (int path = 0; path < next.length; path++) {
			// другой мир со своим счётчиком времени: срок из прежнего мира не считается
			if (next[path] > time + flow.gapMax()) next[path] = time;
			if (next[path] > time) continue;
			next[path] = time + flow.gapMin() + level.getRandom().nextInt(flow.gapMax() - flow.gapMin() + 1);
			LogRoute.Flow.Lane lane = flow.lanes().get(path);
			spawn(level, river, lane.center(), lane.phase(), lane.shift(), lane.sway());
		}
	}

	private static boolean playerNear(ServerLevel level, LogRoute route) {
		double r2 = ACTIVE_RADIUS * ACTIVE_RADIUS;
		for (ServerPlayer p : level.players()) {
			for (int i = 0; i < route.nodes(); i += 8) {
				double dx = p.getX() - route.x(i), dz = p.getZ() - route.z(i);
				if (dx * dx + dz * dz < r2) return true;
			}
		}
		return false;
	}

	private static void spawn(ServerLevel level, River river, float center, float phase, float shift, float sway) {
		RandomSource random = level.getRandom();
		LogRoute route = river.route();
		// начало маршрута должно быть на воде с течением: иначе реку перестроили
		BlockPos start = BlockPos.containing(route.x(0), route.y(0) - 0.5, route.z(0));
		CurrentField field = CurrentField.of(level);
		if (!level.isPositionEntityTicking(start) || !level.getFluidState(start).is(FluidTags.WATER) || field.flowAt(start) == null) return;
		LogRaftEntity log = new LogRaftEntity(TYPE, level);
		setCells(log, SHAPES[random.nextInt(SHAPES.length)]);
		log.launch(river.index(), center, sway, river.route().flow().wavelength(), phase, shift);
		level.addFreshEntity(log);
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
