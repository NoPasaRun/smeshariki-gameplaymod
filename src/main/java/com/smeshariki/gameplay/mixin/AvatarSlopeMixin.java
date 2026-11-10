package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;

/** Коробка и глаза лежащего на скате игрока. */
@Mixin(Avatar.class)
public abstract class AvatarSlopeMixin {
	@Unique
	private static final EntityDimensions gameplaymod$LYING = EntityDimensions.scalable(0.6f, 0.6f).withEyeHeight(0.4f);

	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$lyingSize(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if (pose == Pose.SLIDING) cir.setReturnValue(gameplaymod$LYING);
	}
}
