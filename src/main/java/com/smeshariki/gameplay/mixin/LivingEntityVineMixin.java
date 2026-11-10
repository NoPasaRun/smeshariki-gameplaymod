package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.LeafPhase;

import net.minecraft.world.entity.LivingEntity;

/** На автобеге лианы не лазаемые: тело не цепляется за них и не теряет скорость ({@link LeafPhase#passesVine}). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityVineMixin {
	@Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$vinePhase(CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (LeafPhase.passesVine(self, self.getInBlockState())) cir.setReturnValue(false);
	}
}
