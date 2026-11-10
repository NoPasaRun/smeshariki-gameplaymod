package com.smeshariki.gameplay.barrage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.character.CharacterEntity;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Стрелы, которые гонят игрока по маршруту: летят сбоку и сзади почти горизонтально. Обстрел ({@link BarrageEntity}) целит туда, где игрок сейчас и стреляет, только пока
 * игрок тормозит или уходит с курса ({@link #provoked}): бегущего по маршруту не трогает. Мёртвая зона ({@link DeadzoneEntity}) целит с упреждением. Считает только сервер. Каждый тик у каждого игрока выбирается один источник: зона,
 * в которой он стоит, иначе ближайший обстрел в пределах его радиуса. Игрок с маркером в руке не обстреливается: иначе нельзя настроить.
 */
public final class Barrages {
	public static EntityType<BarrageEntity> BARRAGE;
	public static EntityType<DeadzoneEntity> DEADZONE;
	public static EntityType<BarrageArrow> ARROW;
	public static Item BARRAGE_ITEM, DEADZONE_ITEM;
	/** Держит ли игрок маркер: задаёт клиент, на сервере всегда «да». */
	public static volatile java.util.function.BooleanSupplier holdsMarkerOnClient = () -> true;

	/** На сколько блоков выше цели появляется стрела: иначе она сразу цепляет неровности земли. */
	private static final double RISE = 0.6;
	/** Стрела ближе этого к цели не появляется: стена рядом, стрела бы не успела лететь. */
	private static final double MIN_FLIGHT = 2.5;
	/** Сопротивление и тяжесть стрелы за тик. */
	private static final double DRAG = 0.99, GRAVITY = 0.05;
	/** Живых стрел не больше стольких, через столько тиков стрела убирается: воткнутые иначе копились бы по 1200 тиков. */
	private static final int MAX_ARROWS = 256, ARROW_TICKS = 100;
	/** Сглаживание скорости игрока и её предел: выше — телепорт, а не бег. */
	private static final double VELOCITY_SMOOTH = 0.5, MAX_VELOCITY = 2;

	private static final List<BarrageEntity> BARRAGES = new CopyOnWriteArrayList<>();
	private static final List<DeadzoneEntity> DEADZONES = new CopyOnWriteArrayList<>();
	private static final Deque<Arrow> ARROWS = new ArrayDeque<>();
	/** Позиция и скорость тела игрока: сервер скорости игрока не знает, движение считает клиент. */
	private static final Map<UUID, Vec3> LAST_POS = new HashMap<>(), VELOCITY = new HashMap<>();
	/** Сколько тиков подряд игрок идёт медленнее порога обстрела; стрелы начинаются, когда счётчик дорос до отсрочки маркера. */
	private static final Map<UUID, Integer> SLOW = new HashMap<>();
	/** Счётчик растёт на 1 за медленный тик, не больше отсрочки плюс запас, и убывает на 3 за быстрый: возвращённого на курс перестают обстреливать за пару тиков. */
	private static final int SLOW_MARGIN = 10, SLOW_RECOVERY = 3;

	/** Какие группы маркеров включены у каждого игрока. Только кэш: хранит и каждый тик присылает тот, кто ими управляет. */
	private static volatile Map<UUID, Set<String>> activeGroups = Map.of();

	private Barrages() {
	}

	public static void init() {
		BARRAGE = type("barrage", BarrageEntity::new);
		DEADZONE = type("deadzone", DeadzoneEntity::new);
		ResourceKey<EntityType<?>> arrowKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "barrage_arrow"));
		ARROW = Registry.register(BuiltInRegistries.ENTITY_TYPE, arrowKey, EntityType.Builder.<BarrageArrow>of(BarrageArrow::new, MobCategory.MISC).noLootTable().sized(0.5f, 0.5f).clientTrackingRange(4).updateInterval(20).noSave().noSummon().build(arrowKey));
		BARRAGE_ITEM = item("barrage_marker", level -> new BarrageEntity(BARRAGE, level));
		DEADZONE_ITEM = item("deadzone_marker", level -> new DeadzoneEntity(DEADZONE, level));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> {
			output.accept(BARRAGE_ITEM);
			output.accept(DEADZONE_ITEM);
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof BarrageEntity b) BARRAGES.add(b);
			if (entity instanceof DeadzoneEntity d) DEADZONES.add(d);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof BarrageEntity b) BARRAGES.remove(b);
			if (entity instanceof DeadzoneEntity d) DEADZONES.remove(d);
		});
		DeadzoneScroll.init();
		ServerTickEvents.END_LEVEL_TICK.register(Barrages::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BARRAGES.clear();
			DEADZONES.clear();
			ARROWS.clear();
			LAST_POS.clear();
			VELOCITY.clear();
			SLOW.clear();
			activeGroups = Map.of();
		});
	}

	private static <T extends FireMarkerEntity> EntityType<T> type(String name, EntityType.EntityFactory<T> factory) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.of(factory, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(8).updateInterval(100).fireImmune().build(key));
	}

	private static Item item(String name, Function<Level, FireMarkerEntity> factory) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, name));
		return Registry.register(BuiltInRegistries.ITEM, key, new FireMarkerItem(new Item.Properties().setId(key), factory));
	}

	/** Заменяет включённые группы; у игрока, которого нет в списке, не включено ничего. Маркеры без группы не зависят от этого. */
	public static void setActiveGroups(Map<UUID, Set<String>> groups) {
		activeGroups = groups;
	}

	private static boolean fires(FireMarkerEntity marker, ServerPlayer player) {
		return groupActive(marker.group(), player);
	}

	/** Включена ли у игрока группа маркеров; пустая группа включена всегда. */
	public static boolean groupActive(String group, ServerPlayer player) {
		return group.isEmpty() || activeGroups.getOrDefault(player.getUUID(), Set.of()).contains(group);
	}

	public static boolean holdsMarker(Player player) {
		return player.getMainHandItem().getItem() instanceof FireMarkerItem;
	}

	/** Зоны гибели: живое в ящике гибнет (вселённого убивает его персонаж), брёвна реки исчезают. Креатив и наблюдатели не в счёт. */
	private static void killInZones(ServerLevel level) {
		for (DeadzoneEntity zone : DEADZONES) {
			if (!zone.isKill() || zone.level() != level || zone.isRemoved()) continue;
			var box = zone.box();
			for (var log : level.getEntitiesOfClass(com.smeshariki.gameplay.raft.LogRaftEntity.class, box)) log.discard();
			for (var living : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box, e -> e.isAlive() && !(e instanceof ServerPlayer))) living.kill(level);
			for (ServerPlayer player : level.players()) {
				if (!player.isAlive() || player.isSpectator() || player.isCreative() || !CharacterEntity.body(player).getBoundingBox().intersects(box)) continue;
				// вселённого убивает его персонаж, и игроку выбирают нового; без персонажа гибнет сам игрок
				if (player.getVehicle() instanceof CharacterEntity character) {
					if (character.isAlive()) character.kill(level);
				} else {
					player.kill(level);
				}
			}
		}
	}

	private static void tick(ServerLevel level) {
		killInZones(level);
		dropOldArrows(level);
		List<ServerPlayer> players = level.players();
		for (ServerPlayer player : players) trackVelocity(player);
		if (BARRAGES.isEmpty() && DEADZONES.isEmpty()) return;
		List<ServerPlayer> targets = new ArrayList<>();
		for (ServerPlayer player : players) if (isTarget(player)) targets.add(player);
		if (targets.isEmpty()) return;
		Map<DeadzoneEntity, List<ServerPlayer>> zones = new HashMap<>();
		for (ServerPlayer player : targets) {
			DeadzoneEntity zone = zoneOf(level, player);
			if (zone != null) {
				zones.computeIfAbsent(zone, z -> new ArrayList<>()).add(player);
				continue;
			}
			BarrageEntity barrage = nearestBarrage(level, player);
			if (barrage == null) {
				SLOW.remove(player.getUUID());
				continue;
			}
			if (provoked(barrage, player)) fireRing(level, barrage, player, targets);
		}
		for (ServerPlayer player : players) if (!targets.contains(player)) SLOW.remove(player.getUUID());
		zones.forEach((zone, inside) -> fireAimed(level, zone, inside));
	}

	/** В выживании без персонажа игрок выбирает нового в окне: иначе смерть персонажа стоила бы ему и своей жизни, а её быть не должно. */
	private static boolean isTarget(ServerPlayer player) {
		if (!player.isAlive() || player.isSpectator() || holdsMarker(player)) return false;
		return player.gameMode() != GameType.SURVIVAL || CharacterEntity.isPossessing(player);
	}

	private static void trackVelocity(ServerPlayer player) {
		Vec3 now = CharacterEntity.body(player).position();
		Vec3 last = LAST_POS.put(player.getUUID(), now);
		Vec3 step = last == null ? Vec3.ZERO : now.subtract(last);
		if (step.length() > MAX_VELOCITY) step = Vec3.ZERO;
		Vec3 old = VELOCITY.getOrDefault(player.getUUID(), Vec3.ZERO);
		VELOCITY.put(player.getUUID(), old.lerp(step, VELOCITY_SMOOTH));
	}

	/**
	 * Наказан ли игрок: его скорость вдоль курса маркера ниже порога дольше отсрочки. Бегущий по курсу не наказывается, стоящий, идущий назад или уходящий
	 * вбок круче, чем позволяет порог, — да. Вызывается раз в тик для обстреливающего его маркера.
	 */
	private static boolean provoked(BarrageEntity barrage, ServerPlayer player) {
		if (barrage.minSpeed() <= 0) return true;
		Vec3 v = VELOCITY.getOrDefault(player.getUUID(), Vec3.ZERO), course = barrage.direction(0);
		double along = (v.x * course.x + v.z * course.z) * 20;
		int slow = SLOW.getOrDefault(player.getUUID(), 0);
		slow = along < barrage.minSpeed() ? Math.min(slow + 1, barrage.grace() + SLOW_MARGIN) : Math.max(0, slow - SLOW_RECOVERY);
		SLOW.put(player.getUUID(), slow);
		return slow >= barrage.grace();
	}

	private static DeadzoneEntity zoneOf(ServerLevel level, ServerPlayer player) {
		var box = CharacterEntity.body(player).getBoundingBox();
		for (DeadzoneEntity zone : DEADZONES) if (zone.level() == level && !zone.isRemoved() && !zone.isKill() && fires(zone, player) && zone.contains(box)) return zone;
		return null;
	}

	private static BarrageEntity nearestBarrage(ServerLevel level, ServerPlayer player) {
		BarrageEntity best = null;
		double bestDist = Double.MAX_VALUE;
		Vec3 at = CharacterEntity.body(player).position();
		for (BarrageEntity barrage : BARRAGES) {
			if (barrage.level() != level || barrage.isRemoved() || !fires(barrage, player)) continue;
			double dist = barrage.position().distanceToSqr(at);
			if (dist <= barrage.radius() * barrage.radius() && dist < bestDist) {
				best = barrage;
				bestDist = dist;
			}
		}
		return best;
	}

	/** Стрелы этого тика: появляются вокруг игрока по секторам и летят туда, где он сейчас. Точка появления не ближе дозволенного и к другим игрокам. */
	private static void fireRing(ServerLevel level, BarrageEntity barrage, ServerPlayer player, List<ServerPlayer> everyone) {
		List<Integer> sectors = barrage.dueSectors(player.getUUID());
		if (sectors.isEmpty()) return;
		RandomSource random = level.getRandom();
		Vec3 center = center(player);
		double width = 360.0 / barrage.density().length;
		double safe = Math.max(MIN_FLIGHT, barrage.distance() - barrage.spread());
		for (int sector : sectors) {
			Vec3 out = barrage.direction((sector + random.nextDouble() - 0.5) * width);
			double dist = Math.max(MIN_FLIGHT, barrage.distance() + (random.nextDouble() * 2 - 1) * barrage.spread());
			if (closeToOther(center.add(out.scale(dist)), player, everyone, safe)) continue;
			shoot(level, center.add(miss(random, barrage.miss())), out, dist, barrage.speed(), barrage.damage());
		}
	}

	private static boolean closeToOther(Vec3 point, ServerPlayer shooter, List<ServerPlayer> everyone, double safe) {
		for (ServerPlayer other : everyone) {
			if (other == shooter) continue;
			Vec3 at = center(other);
			if (Mth.lengthSquared(at.x - point.x, at.z - point.z) < safe * safe) return true;
		}
		return false;
	}

	private static void fireAimed(ServerLevel level, DeadzoneEntity zone, List<ServerPlayer> inside) {
		int shots = zone.dueShots(inside.size());
		RandomSource random = level.getRandom();
		for (int i = 0; i < shots; i++) {
			ServerPlayer player = inside.get(i % inside.size());
			double angle = random.nextDouble() * Math.PI * 2;
			Vec3 out = new Vec3(Math.cos(angle), 0, Math.sin(angle));
			Vec3 lead = VELOCITY.getOrDefault(player.getUUID(), Vec3.ZERO).multiply(1, 0, 1).scale(zone.distance() / zone.speed());
			shoot(level, center(player).add(lead).add(miss(random, zone.spread())), out, zone.distance(), zone.speed(), zone.damage());
		}
	}

	/** Середина тела игрока или его персонажа. */
	private static Vec3 center(ServerPlayer player) {
		Entity body = CharacterEntity.body(player);
		return body.position().add(0, body.getBbHeight() * 0.5, 0);
	}

	private static Vec3 miss(RandomSource random, double radius) {
		double angle = random.nextDouble() * Math.PI * 2, size = random.nextDouble() * radius;
		return new Vec3(Math.cos(angle) * size, (random.nextDouble() - 0.5) * size, Math.sin(angle) * size);
	}

	/** Стрела со стороны {@code out} на расстоянии {@code dist}, рассчитанная так, чтобы прийти в {@code target}. Если там блоки, вылетает прямо перед ними. */
	private static boolean shoot(ServerLevel level, Vec3 target, Vec3 out, double dist, double speed, double damage) {
		if (ARROWS.size() >= MAX_ARROWS) return false;
		Vec3 wanted = target.add(out.scale(dist)).add(0, RISE, 0);
		BlockHitResult hit = level.clip(new ClipContext(target, wanted, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()) {
			/** Листву стрела пролетит, поэтому и начинаться может за ней. */
			@Override
			public VoxelShape getBlockShape(BlockState state, BlockGetter getter, BlockPos pos) {
				return BarrageArrow.passes(state) ? Shapes.empty() : super.getBlockShape(state, getter, pos);
			}
		});
		Vec3 start = hit.getType() == HitResult.Type.MISS ? wanted : hit.getLocation().add(target.subtract(wanted).normalize().scale(0.5));
		Vec3 path = target.subtract(start);
		if (path.length() < MIN_FLIGHT) return false;
		int flight = Math.max(2, Mth.ceil(path.length() / speed));
		// смещение за n тиков: v·A − g·(n − A)/(1 − d), где A = (1 − dⁿ)/(1 − d)
		double a = (1 - Math.pow(DRAG, flight)) / (1 - DRAG);
		double fall = GRAVITY * (flight - a) / (1 - DRAG);
		Vec3 velocity = new Vec3(path.x / a, (path.y + fall) / a, path.z / a);
		BarrageArrow arrow = new BarrageArrow(ARROW, level);
		arrow.setPos(start);
		arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		arrow.setBaseDamage(damage);
		arrow.shoot(velocity.x, velocity.y, velocity.z, (float) velocity.length(), 0f);
		level.addFreshEntity(arrow);
		ARROWS.addLast(arrow);
		level.playSound(null, start.x, start.y, start.z, SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1f, 1f + level.getRandom().nextFloat() * 0.4f);
		return true;
	}

	private static void dropOldArrows(ServerLevel level) {
		for (var it = ARROWS.iterator(); it.hasNext();) {
			Arrow arrow = it.next();
			if (arrow.isRemoved()) it.remove();
			else if (arrow.level() == level && arrow.tickCount >= ARROW_TICKS) {
				arrow.discard();
				it.remove();
			}
		}
	}
}
