package com.smeshariki.gameplay.lava;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.barrage.FireMarkerItem;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/** Регистрация волны лавы, запуск по группе и событие её конца, см. {@link LavaWaveEntity}. */
public final class LavaWaves {
	/** Волна маркера кончилась: гребень обрушился и слой лавы залит. {@code group} — его {@code Group} (пустая у маркера без группы). Только сервер. */
	@FunctionalInterface
	public interface Finished {
		void finished(LavaWaveEntity wave, String group);
	}

	public static final Event<Finished> FINISHED = EventFactory.createArrayBacked(Finished.class, listeners -> (wave, group) -> {
		for (Finished l : listeners) l.finished(wave, group);
	});

	/** Сколько тиков ждёт заказ волны группы, у которой не нашлось загруженных маркеров: игрока как раз переносят к пещере, и её чанки грузятся. */
	private static final int PENDING_TICKS = 600;
	/** Заказы без загруженных маркеров: группа → до какого тика сервера ждать. Только в памяти: это секунды. */
	private static final java.util.Map<String, Integer> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Пустить волну у всех загруженных маркеров группы; у занятого маркера она пойдёт после его волны. Если загруженных нет (чанки пещеры ещё грузятся),
	 * заказ ждёт {@link #PENDING_TICKS} и достаётся первому загрузившемуся маркеру группы. Возвращает, сколько маркеров нашлось сразу.
	 */
	public static int launch(MinecraftServer server, String group) {
		if (group.isEmpty()) return 0;
		int n = 0;
		for (LavaWaveEntity w : LavaWaveEntity.ALL) {
			// в одиночной игре список общий с клиентом
			if (w.level().isClientSide() || w.isRemoved() || w.level().getServer() != server || !group.equals(w.group())) continue;
			w.requestLaunch();
			n++;
		}
		if (n == 0) PENDING.put(group, server.getTickCount() + PENDING_TICKS);
		else PENDING.remove(group);
		return n;
	}

	/** Маркер загрузился: если его группе заказана волна, а маркеров не было, пускаем. */
	private static void onLoad(LavaWaveEntity w) {
		if (PENDING.isEmpty() || w.group().isEmpty() || !(w.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
		Integer until = PENDING.get(w.group());
		if (until == null) return;
		if (until >= level.getServer().getTickCount()) w.requestLaunch();
		PENDING.remove(w.group());
	}

	public static EntityType<LavaWaveEntity> TYPE;
	public static Item ITEM;

	private LavaWaves() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "lava_wave"));
		// данные (начало волны) должны доходить сразу, а не раз в несколько секунд
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<LavaWaveEntity>of(LavaWaveEntity::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(10).updateInterval(1).fireImmune().build(typeKey));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof LavaWaveEntity w) {
				LavaWaveEntity.ALL.add(w);
				onLoad(w);
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof LavaWaveEntity w) LavaWaveEntity.ALL.remove(w);
		});
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "lava_wave_marker"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new FireMarkerItem(new Item.Properties().setId(itemKey), level -> new LavaWaveEntity(TYPE, level)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));
	}
}
