package com.smeshariki.gameplay.lava;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Регистрация прилива лавы, см. {@link LavaTideEntity}. */
public final class LavaTides {
	public static EntityType<LavaTideEntity> TYPE;

	private LavaTides() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "lava_tide"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<LavaTideEntity>of(LavaTideEntity::new, MobCategory.MISC).sized(0.5f, 0.5f).clientTrackingRange(2).updateInterval(200).fireImmune().build(key));
	}
}
