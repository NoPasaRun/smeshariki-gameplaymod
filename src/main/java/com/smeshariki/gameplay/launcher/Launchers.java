package com.smeshariki.gameplay.launcher;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.barrage.FireMarkerItem;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/** Регистрация жерла, см. {@link LauncherEntity}. */
public final class Launchers {
	public static EntityType<LauncherEntity> TYPE;
	public static Item ITEM;

	private Launchers() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "launcher"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<LauncherEntity>of(LauncherEntity::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(8).updateInterval(100).fireImmune().build(typeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "launcher_marker"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new FireMarkerItem(new Item.Properties().setId(itemKey), level -> new LauncherEntity(TYPE, level)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));
	}
}
