package com.smeshariki.gameplay.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Мост между механиками и тем, кто ими управляет (LevelMod), чтобы управляющий не знал ни одной механики по имени класса. Механика
 * регистрирует здесь под своим идентификатором:
 * <ul>
 * <li>{@link Action} — разовое действие с параметрами из файла уровня ({@code {"action":"gameplaymod:lava_wave","group":"a"}});</li>
 * <li>{@link Toggle} — состояние на игрока, которое держит владелец (прогон уровня), например автобег; ключ — значение одного параметра
 * (группа) или пустая строка;</li>
 * <li>{@link Snapshot} — постройки, которые можно запомнить в области и вернуть (шары);</li>
 * <li>{@link Condition} — проверка состояния игрока ({@code {"check":"gameplaymod:holding_rope","kind":"lift"}}): из них уровни собирают задачи и наблюдателей;</li>
 * </ul>
 * а свои события отдаёт в {@link #EVENTS} строкой {@code "пространство:имя[:уточнение]"}, например {@code "gameplaymod:lava_wave_finished:a"}.
 * Всё здесь только регистрация: состояние хранит владелец.
 */
public final class Mechanics {
	/** Параметры действия: поля объекта действия из файла уровня. Число и логическое значение читаются терпимо (после NBT логическое — число). */
	public interface Params {
		String string(String name, String fallback);

		boolean bool(String name, boolean fallback);

		double number(String name, double fallback);

		/** Область из параметра ({@code "@имя"} в файле уровня), {@code null} — нет или не область. */
		@Nullable Area area(String name);
	}

	/** Область мира: контуры в плоскости XZ, по высоте весь мир. */
	public interface Area {
		ResourceKey<Level> dimension();

		boolean contains(Vec3 pos);

		/** Наименьшие и наибольшие x, z контуров: {@code {minX, minZ, maxX, maxZ}}. */
		double[] boundsXZ();
	}

	/** Разовое действие механики. */
	public interface Action {
		/** @param participants участники в сети, для кого действие (механики мира могут их не использовать) */
		void run(MinecraftServer server, List<ServerPlayer> participants, Params params);

		/** Ошибки параметров: уровень с ними не загрузится. */
		default List<String> problems(Params params) {
			return List.of();
		}

		/** Какие свои события действие породит и через сколько тиков в среднем: для оценки длительности уровня. */
		default Map<String, Long> expectedEvents(Params params) {
			return Map.of();
		}
	}

	/**
	 * Состояние на игрока, пока его держит владелец. {@code keyParam} — имя параметра, чьё значение становится ключом (группа), {@code null} —
	 * ключа нет, тогда он пустая строка. Включают и выключают его параметром {@code on} (по умолчанию включает).
	 */
	public interface Toggle {
		@Nullable String keyParam();

		/** Кому что включено сейчас; вызывается каждый тик, в том числе с пустой картой, когда всё выключено. */
		void sync(MinecraftServer server, Map<UUID, Set<String>> keys);
	}

	/** Постройки, которые можно запомнить и вернуть. */
	public interface Snapshot {
		CompoundTag take(ServerLevel level, Area area);

		void restore(ServerLevel level, CompoundTag snapshot);
	}

	/** Проверка состояния игрока (или мира вокруг него). Регистрировать может любой мод, в том числе сам управляющий. */
	public interface Condition {
		boolean test(ServerPlayer player, Params params);

		/** Ошибки параметров: уровень с ними не загрузится. */
		default List<String> problems(Params params) {
			return List.of();
		}

		/** Подсказка о параметрах для окна редактора: {@code "kind=lift|helm, group=…"}. */
		default String hint() {
			return "";
		}
	}

	@FunctionalInterface
	public interface Emitted {
		void onEvent(MinecraftServer server, String event);
	}

	/** События механик: {@code "пространство:имя"} или {@code "пространство:имя:уточнение"}. */
	public static final Event<Emitted> EVENTS = EventFactory.createArrayBacked(Emitted.class, listeners -> (server, event) -> {
		for (Emitted l : listeners) l.onEvent(server, event);
	});

	private static final Map<Identifier, Action> ACTIONS = new LinkedHashMap<>();
	private static final Map<Identifier, Toggle> TOGGLES = new LinkedHashMap<>();
	private static final Map<Identifier, Snapshot> SNAPSHOTS = new LinkedHashMap<>();
	private static final Map<Identifier, Condition> CONDITIONS = new LinkedHashMap<>();
	/** Имена событий без уточнения и подсказка, что в уточнении: для окон редактора. */
	private static final Map<String, String> EVENT_NAMES = new LinkedHashMap<>();

	private Mechanics() {
	}

	public static void registerAction(Identifier id, Action action) {
		ACTIONS.put(id, action);
	}

	public static void registerToggle(Identifier id, Toggle toggle) {
		TOGGLES.put(id, toggle);
	}

	public static void registerSnapshot(Identifier id, Snapshot snapshot) {
		SNAPSHOTS.put(id, snapshot);
	}

	public static void registerCondition(Identifier id, Condition condition) {
		CONDITIONS.put(id, condition);
	}

	public static @Nullable Condition condition(Identifier id) {
		return CONDITIONS.get(id);
	}

	public static Map<Identifier, Condition> conditions() {
		return CONDITIONS;
	}

	/**
	 * Предмет из строки параметра: {@code "minecraft:apple"} или тег {@code "#minecraft:shovels"}. {@code null} — такого предмета или тега нет
	 * (тег проверяется по реестру предметов, поэтому вызывать после загрузки данных).
	 */
	public static @Nullable Predicate<ItemStack> item(String text) {
		if (text.startsWith("#")) {
			Identifier tag = Identifier.tryParse(text.substring(1));
			if (tag == null) return null;
			TagKey<Item> key = TagKey.create(Registries.ITEM, tag);
			return stack -> stack.is(key);
		}
		Identifier id = Identifier.tryParse(text);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
		Item item = BuiltInRegistries.ITEM.getValue(id);
		return stack -> stack.is(item);
	}

	/** @param subject что в уточнении события ({@code "группа"}), {@code null} — уточнения нет */
	public static void describeEvent(String name, @Nullable String subject) {
		EVENT_NAMES.put(name, subject == null ? "" : subject);
	}

	public static @Nullable Action action(Identifier id) {
		return ACTIONS.get(id);
	}

	public static @Nullable Toggle toggle(Identifier id) {
		return TOGGLES.get(id);
	}

	public static Map<Identifier, Action> actions() {
		return ACTIONS;
	}

	public static Map<Identifier, Toggle> toggles() {
		return TOGGLES;
	}

	public static Map<Identifier, Snapshot> snapshots() {
		return SNAPSHOTS;
	}

	public static Map<String, String> eventNames() {
		return EVENT_NAMES;
	}

	public static void emit(MinecraftServer server, String event) {
		EVENTS.invoker().onEvent(server, event);
	}
}
