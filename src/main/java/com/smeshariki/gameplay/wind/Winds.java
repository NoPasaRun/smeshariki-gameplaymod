package com.smeshariki.gameplay.wind;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ветер: маркеры и поиск ветра. Зона маркера — шар радиусом {@link WindMarkerEntity#RADIUS}, сила к краю падает
 * ({@link WindMarkerEntity#strength}); шар получает ветер, пока задевает зону, а потом идёт по инерции. Ветер действует только на воздушные шары.
 */
public final class Winds {
	public static EntityType<WindMarkerEntity> TYPE;
	public static Item ITEM;
	/** Держит ли игрок маркер: задаёт клиент, на сервере всегда «да». */
	public static volatile java.util.function.BooleanSupplier holdsMarkerOnClient = () -> true;

	private Winds() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "wind_marker"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<WindMarkerEntity>of(WindMarkerEntity::new, MobCategory.MISC)
				.sized(1f, 1f).clientTrackingRange(8).updateInterval(100).fireImmune().noSummon().build(typeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "wind_marker"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new WindMarkerItem(new Item.Properties().setId(itemKey)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof WindMarkerEntity m) WindMarkerEntity.SERVER_MARKERS.add(m);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof WindMarkerEntity m) WindMarkerEntity.SERVER_MARKERS.remove(m);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> WindMarkerEntity.SERVER_MARKERS.clear());
	}

	/**
	 * Ветер для ящика или null в штиль: направление — сумма направлений задевших его маркеров с весом по силе, длина — сила
	 * самого сильного (доля от полной, {@link WindMarkerEntity#strength}). Только для сервера.
	 */
	public static Vec3 at(Level level, AABB box) {
		Vec3 sum = Vec3.ZERO;
		double max = 0;
		for (WindMarkerEntity m : WindMarkerEntity.SERVER_MARKERS) {
			if (m.level() != level || m.isRemoved()) continue;
			double k = m.strength(box);
			if (k <= 0) continue;
			sum = sum.add(m.direction().scale(k));
			max = Math.max(max, k);
		}
		return sum.lengthSqr() < 1.0e-6 ? null : sum.normalize().scale(max);
	}
}
