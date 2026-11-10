package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.smeshariki.gameplay.client.SlideLying;
import com.smeshariki.gameplay.client.SlideVisualAccess;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

/** Игрок на скате лежит на спине ногами по ходу движения. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererSlopeMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void gameplaymod$extractLying(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		float[] lie = SlideVisualAccess.lying(entity, partialTick);
		if (lie == null) {
			((SlideLying) state).gameplaymod$set(Float.NaN, 0);
			return;
		}
		((SlideLying) state).gameplaymod$set(lie[1], lie[2]);
		state.bodyRot = lie[0];
		state.yRot = 0;
		state.xRot = 0;
		state.walkAnimationSpeed = 0;
	}

	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("TAIL"))
	private void gameplaymod$lie(AvatarRenderState state, PoseStack pose, float bodyRot, float scale, CallbackInfo ci) {
		float tilt = ((SlideLying) state).gameplaymod$tilt();
		if (Float.isNaN(tilt)) return;
		// локально вперёд — это −Z: ноги вперёд, голова назад и выше на уклон; низ спины на поверхности, середина тела над точкой опоры
		double t = Math.toRadians(tilt);
		pose.translate(0, 0.2 - ((SlideLying) state).gameplaymod$drop() - 0.9 * Math.sin(t), -0.9 * Math.cos(t));
		pose.mulPose(Axis.XP.rotationDegrees(90 - tilt));
	}
}
