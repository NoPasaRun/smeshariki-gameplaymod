package com.smeshariki.gameplay.current;

import com.smeshariki.gameplay.GameplayMod;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/** Регистрация течений рек: сущность-маркер, два предмета и толкание сущностей течением на сервере. */
public final class Currents {
	public static EntityType<CurrentMarkerEntity> TYPE;
	public static Item ITEM;
	/** Скорость течения: 10 блоков в секунду. */
	public static final double SPEED = 10.0 / 20;
	/** Цель разгона чуть выше {@link #SPEED}: вода гасит скорость, поэтому до нужной доходим не сразу. */
	private static final double AIM = SPEED * 1.2;
	/** Держит ли игрок маркер: задаёт клиент, на сервере всегда «да». */
	public static volatile java.util.function.BooleanSupplier holdsMarkerOnClient = () -> true;

	private Currents() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "current_marker"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<CurrentMarkerEntity>of(CurrentMarkerEntity::new, MobCategory.MISC)
				.sized(0.5f, 0.5f).clientTrackingRange(8).updateInterval(100).fireImmune().noSummon().build(typeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "current_marker"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new CurrentMarkerItem(new Item.Properties().setId(itemKey)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));

		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof CurrentMarkerEntity m) CurrentField.add(m);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CurrentMarkerEntity m) CurrentField.remove(m);
		});
		ServerTickEvents.END_LEVEL_TICK.register(Currents::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CurrentField.clear());
	}

	/** Направление течения в точке тела (середина по высоте) или null. */
	public static Vec3 flowAt(CurrentField field, Entity e) {
		return field.flowAt(BlockPos.containing(e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ()));
	}

	/** Разгоняет тело вдоль течения до {@link #SPEED}; поперёк течения скорость не трогает. */
	public static void push(Entity e, Vec3 dir) {
		Vec3 v = e.getDeltaMovement();
		double along = v.dot(dir);
		double next = Math.min(SPEED, along + (AIM - along) * 0.5);
		if (next > along) e.setDeltaMovement(v.add(dir.scale(next - along)));
	}

	/** Игроков и вселённых персонажей двигают клиенты игроков (см. клиентскую часть), остальных — сервер. */
	private static void tick(ServerLevel level) {
		CurrentField field = CurrentField.of(level);
		if (field == null) return;
		field.tick(level);
		if (field.isEmpty()) return;
		for (Entity e : level.getAllEntities()) {
			if (e instanceof Player || e.getControllingPassenger() instanceof Player || e instanceof CurrentMarkerEntity || e.noPhysics || e.isPassenger() || !e.isInWater()) continue;
			Vec3 dir = flowAt(field, e);
			if (dir != null) push(e, dir);
		}
	}
}
