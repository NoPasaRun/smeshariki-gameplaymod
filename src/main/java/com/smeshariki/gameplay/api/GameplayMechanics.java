package com.smeshariki.gameplay.api;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.barrage.Barrages;
import com.smeshariki.gameplay.character.AutorunControl;
import com.smeshariki.gameplay.lava.LavaWaves;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.raft.BalloonEntity;
import com.smeshariki.gameplay.raft.BalloonRopeEntity;
import com.smeshariki.gameplay.raft.BalloonSnapshots;
import com.smeshariki.gameplay.rope.RopeEntity;
import com.smeshariki.gameplay.raft.Balloons;
import com.smeshariki.gameplay.raft.RaftEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Механики этого мода в {@link Mechanics}. Новая механика, которой управляют уровни, — запись здесь. */
public final class GameplayMechanics {
	/** Сколько в среднем идёт волна лавы: путь 32 блока по 4 блока/с и обрушение. */
	private static final long LAVA_WAVE_TICKS = 180;
	public static final String LAVA_WAVE_FINISHED = "gameplaymod:lava_wave_finished";

	private GameplayMechanics() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, path);
	}

	public static void init() {
		// автобег как нажатие клавиши: игрок может выключить его сам (O, S)
		Mechanics.registerToggle(id("autorun"), new Mechanics.Toggle() {
			@Override
			public @Nullable String keyParam() {
				return null;
			}

			@Override
			public void sync(MinecraftServer server, Map<java.util.UUID, java.util.Set<String>> keys) {
				AutorunControl.sync(server, keys.keySet());
			}
		});
		// обстрел и мёртвые зоны группы (поле Group маркера); маркер без группы стреляет всегда
		Mechanics.registerToggle(id("arrows"), new Mechanics.Toggle() {
			@Override
			public String keyParam() {
				return "group";
			}

			@Override
			public void sync(MinecraftServer server, Map<java.util.UUID, java.util.Set<String>> keys) {
				Barrages.setActiveGroups(keys);
			}
		});
		// волна у маркеров группы; конец каждой волны — событие lava_wave_finished:группа
		Mechanics.registerAction(id("lava_wave"), new Mechanics.Action() {
			@Override
			public void run(MinecraftServer server, List<ServerPlayer> participants, Mechanics.Params params) {
				LavaWaves.launch(server, params.string("group", ""));
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				return params.string("group", "").isEmpty() ? List.of("волне лавы не указана группа (group)") : List.of();
			}

			@Override
			public Map<String, Long> expectedEvents(Mechanics.Params params) {
				return Map.of(LAVA_WAVE_FINISHED + ":" + params.string("group", ""), LAVA_WAVE_TICKS);
			}
		});
		Mechanics.describeEvent(LAVA_WAVE_FINISHED, "группа");
		LavaWaves.FINISHED.register((wave, group) -> {
			if (!group.isEmpty() && wave.level() instanceof ServerLevel level) Mechanics.emit(level.getServer(), LAVA_WAVE_FINISHED + ":" + group);
		});
		// разрушить плоты, брёвна и шары в области (area) или шары группы (group) где угодно, пока они загружены; с обоими — шары группы в области
		Mechanics.registerAction(id("destroy"), new Mechanics.Action() {
			@Override
			public void run(MinecraftServer server, List<ServerPlayer> participants, Mechanics.Params params) {
				Mechanics.Area area = params.area("area");
				String group = params.string("group", "");
				if (group.isEmpty()) {
					ServerLevel level = area == null ? null : server.getLevel(area.dimension());
					if (level != null) RaftEntity.destroyIn(level, box(level, area), area::contains);
					return;
				}
				for (RaftEntity raft : List.copyOf(RaftEntity.ALL)) {
					if (!(raft instanceof BalloonEntity balloon) || balloon.level().isClientSide() || balloon.isRemoved() || balloon.destroyed() || !group.equals(balloon.group())) continue;
					if (area != null && (!balloon.level().dimension().equals(area.dimension()) || !area.contains(balloon.position()))) continue;
					balloon.destroy();
				}
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				return params.area("area") == null && params.string("group", "").isEmpty() ? List.of("разрушению не указаны ни область (area), ни группа шара (group)") : List.of();
			}
		});
		// шары с верёвками и топливом: запомнить в области и вернуть
		// плоты и шары с верёвками и топливом
		Mechanics.registerSnapshot(id("rafts"), new Mechanics.Snapshot() {
			@Override
			public CompoundTag take(ServerLevel level, Mechanics.Area area) {
				return BalloonSnapshots.take(level, box(level, area), area::contains);
			}

			@Override
			public void restore(ServerLevel level, CompoundTag snapshot) {
				BalloonSnapshots.restore(level, snapshot);
			}
		});
		// общий снимок: всё остальное, что в области можно вернуть (верёвки на блоках, рамки, лодки, маркеры…); фильтр — в BalloonSnapshots.remembered
		Mechanics.registerSnapshot(id("entities"), new Mechanics.Snapshot() {
			@Override
			public CompoundTag take(ServerLevel level, Mechanics.Area area) {
				return BalloonSnapshots.takeEntities(level, box(level, area), area::contains);
			}

			@Override
			public void restore(ServerLevel level, CompoundTag snapshot) {
				BalloonSnapshots.restore(level, snapshot);
			}
		});
		// озеро лавы: уровень лавы и маркеры волн (волны поднимают лаву, маркеры поднимаются с ней)
		Mechanics.registerSnapshot(id("lava"), new Mechanics.Snapshot() {
			@Override
			public CompoundTag take(ServerLevel level, Mechanics.Area area) {
				return com.smeshariki.gameplay.lava.LavaSnapshots.take(level, box(level, area), area::contains);
			}

			@Override
			public void restore(ServerLevel level, CompoundTag snapshot) {
				com.smeshariki.gameplay.lava.LavaSnapshots.restore(level, snapshot);
			}
		});
		Mechanics.describeEvent(Balloons.EVENT_STRANDED, "группа шара");
		Mechanics.describeEvent(Balloons.EVENT_LIFTED, "группа шара");
		conditions();
	}

	/** Проверки состояния для задач и наблюдателей уровней. Персонажа проверяют по его телу: вселённый игрок сидит на нём. */
	private static void conditions() {
		// в воде: по умолчанию с головой (submerged=false — хватит ног)
		Mechanics.registerCondition(id("in_water"), new Mechanics.Condition() {
			@Override
			public boolean test(ServerPlayer player, Mechanics.Params params) {
				Entity body = CharacterEntity.body(player);
				return params.bool("submerged", true) ? body.isUnderWater() : body.isInWater();
			}

			@Override
			public String hint() {
				return "submerged=true|false";
			}
		});
		// надето на персонажа (очки, шапка): предмет или тег
		Mechanics.registerCondition(id("wearing"), new Mechanics.Condition() {
			@Override
			public boolean test(ServerPlayer player, Mechanics.Params params) {
				Predicate<ItemStack> match = Mechanics.item(params.string("item", ""));
				if (match == null || !(player.getVehicle() instanceof CharacterEntity character)) return false;
				for (int i = 0; i < character.gear.getContainerSize(); i++) if (match.test(character.gear.getItem(i))) return true;
				return false;
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				return Mechanics.item(params.string("item", "")) == null ? List.of("нет такого предмета: " + params.string("item", "")) : List.of();
			}

			@Override
			public String hint() {
				return "item=gameplaymod:glasses";
			}
		});
		// стоит на шаре (своей группы, если она указана)
		Mechanics.registerCondition(id("on_balloon"), new Mechanics.Condition() {
			@Override
			public boolean test(ServerPlayer player, Mechanics.Params params) {
				return RaftEntity.under(CharacterEntity.body(player)) instanceof BalloonEntity balloon && groupMatches(balloon, params);
			}

			@Override
			public String hint() {
				return "group=…";
			}
		});
		// держит стропу шара: kind=lift (высота), helm (руль), any
		Mechanics.registerCondition(id("holding_rope"), new Mechanics.Condition() {
			@Override
			public boolean test(ServerPlayer player, Mechanics.Params params) {
				if (!(RopeEntity.heldBy(player) instanceof BalloonRopeEntity rope)) return false;
				String kind = params.string("kind", "any");
				if (!kind.equals("any") && !rope.kind().name().equalsIgnoreCase(kind)) return false;
				BalloonEntity balloon = rope.balloon();
				return params.string("group", "").isEmpty() || balloon != null && groupMatches(balloon, params);
			}

			@Override
			public List<String> problems(Mechanics.Params params) {
				String kind = params.string("kind", "any");
				return kind.equals("any") || kind.equals("lift") || kind.equals("helm") ? List.of() : List.of("kind: lift, helm или any, а не " + kind);
			}

			@Override
			public String hint() {
				return "kind=lift|helm|any, group=…";
			}
		});
		// шар группы стоит на блоках (сел): проверяется у загруженных шаров в 64 блоках от игрока
		Mechanics.registerCondition(id("balloon_landed"), new Mechanics.Condition() {
			@Override
			public boolean test(ServerPlayer player, Mechanics.Params params) {
				for (BalloonEntity balloon : player.level().getEntitiesOfClass(BalloonEntity.class, player.getBoundingBox().inflate(64), b -> groupMatches(b, params))) {
					if (balloon.resting()) return true;
				}
				return false;
			}

			@Override
			public String hint() {
				return "group=…";
			}
		});
	}

	private static boolean groupMatches(BalloonEntity balloon, Mechanics.Params params) {
		String group = params.string("group", "");
		return group.isEmpty() || group.equals(balloon.group());
	}

	/** Ящик вокруг контуров области на всю высоту мира. */
	public static AABB box(ServerLevel level, Mechanics.Area area) {
		double[] b = area.boundsXZ();
		return new AABB(b[0], level.getMinY(), b[1], b[2], level.getMaxY(), b[3]);
	}
}
