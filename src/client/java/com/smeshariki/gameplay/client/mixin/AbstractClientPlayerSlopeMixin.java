package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.slide.SlopeSurface;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

/** Скользящий не шагает: камера не качается, как при ходьбе. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerSlopeMixin {
	@Inject(method = "updateBob", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$noBob(CallbackInfo ci) {
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		Entity mover = self.getVehicle() != null ? self.getVehicle() : self;
		if (SlopeSurface.isSliding(mover)) {
			self.avatarState().updateBob(0);
			ci.cancel();
		}
	}
}
