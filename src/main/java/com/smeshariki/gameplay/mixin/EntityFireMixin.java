package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Вселённый игрок не горит: он сидит ниже персонажа (на плоту над лавой его коробка в лаве) и горел бы без конца, а урона ему всё равно нет.
 * Горит и получает урон персонаж.
 */
@Mixin(Entity.class)
public abstract class EntityFireMixin {
	@Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$possessedFireImmune(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player player && player.getVehicle() instanceof CharacterEntity) cir.setReturnValue(true);
	}
}
