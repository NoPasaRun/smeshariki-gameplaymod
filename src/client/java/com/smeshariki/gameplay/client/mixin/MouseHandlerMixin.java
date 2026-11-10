package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.client.GameplayModClient;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

/** Пока Ёжик катится клубком, мышь камеру не поворачивает. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
	/** Пока игрок держит стропу шара, колесо управляет шаром, а не панелью предметов. */
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$balloonRope(long window, double x, double y, CallbackInfo ci) {
		if (GameplayModClient.scrollBalloonRope(y)) ci.cancel();
	}

	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void gameplaymod$lockCamera(LocalPlayer player, double x, double y) {
		if (!GameplayModClient.cameraLocked(player)) player.turn(x, y);
	}
}
