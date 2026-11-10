package com.smeshariki.gameplay.client.mixin;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.client.GameplayModClient;

import net.minecraft.client.Camera;

/** Кувыркает камеру вперёд (вокруг поперечной оси, вдоль движения), пока вселённый Ёжик катится клубком. */
@Mixin(Camera.class)
public class CameraMixin {
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards, up, left;

	@Inject(method = "setRotation", at = @At("RETURN"))
	private void gameplaymod$roll(float yRot, float xRot, CallbackInfo ci) {
		float roll = GameplayModClient.cameraRoll();
		if (roll == 0f) return;
		// поворот вокруг оси «влево-вправо» камеры: взгляд уходит вниз, как у шара, катящегося вперёд
		this.rotation.rotateX(-roll);
		this.forwards.rotateAxis(-roll, this.left.x, this.left.y, this.left.z);
		this.up.rotateAxis(-roll, this.left.x, this.left.y, this.left.z);
	}
}
