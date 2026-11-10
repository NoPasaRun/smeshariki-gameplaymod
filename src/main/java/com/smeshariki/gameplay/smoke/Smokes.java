package com.smeshariki.gameplay.smoke;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.barrage.FireMarkerItem;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/** Регистрация дыма, см. {@link SmokeEntity}: сущность, маркер и частица клуба (рисует {@code client/SmokeParticle}). */
public final class Smokes {
	public static EntityType<SmokeEntity> TYPE;
	public static Item ITEM;
	public static final SimpleParticleType PARTICLE = FabricParticleTypes.simple(true);

	private Smokes() {
	}

	public static void init() {
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "thick_smoke"), PARTICLE);
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "smoke"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<SmokeEntity>of(SmokeEntity::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(8).updateInterval(100).fireImmune().build(typeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "smoke_marker"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new FireMarkerItem(new Item.Properties().setId(itemKey), level -> new SmokeEntity(TYPE, level)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));
	}
}
