package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.character.TimeSlow;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/** Замедление времени Бараша на клиенте: снаряды и предметы, которые клиент двигает сам, тикают реже ({@link TimeSlow#skips}). */
@Mixin(ClientLevel.class)
public abstract class ClientLevelSlowMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$slowTime(Entity entity, CallbackInfo ci) {
		if (TimeSlow.skips(entity)) ci.cancel();
	}
}
