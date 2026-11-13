package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.character.TimeSlow;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Замедление времени Бараша: замедленные существа делают тик не каждый раз ({@link TimeSlow}). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSlowMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$slowTime(Entity entity, CallbackInfo ci) {
		if (TimeSlow.skips(entity)) ci.cancel();
	}
}
