package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.slide.SlopeSurface;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/** Наклонные столкновения скатов: см. {@link SlopeSurface}. */
@Mixin(Entity.class)
public abstract class EntitySlopeMixin {
	@Inject(method = "collide", at = @At("RETURN"), cancellable = true)
	private void gameplaymod$slopeCollide(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		cir.setReturnValue(SlopeSurface.collide((Entity) (Object) this, movement, cir.getReturnValue()));
	}

	@Inject(method = "move", at = @At("TAIL"))
	private void gameplaymod$slopeSlide(MoverType type, Vec3 movement, CallbackInfo ci) {
		if (type == MoverType.SELF) SlopeSurface.slideItem((Entity) (Object) this);
	}

	/** Снаряд попадает во вселённого только через персонажа: игрок внутри него своего урона не получает. */
	@Inject(method = "canBeHitByProjectile", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$possessedNotHit(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof net.minecraft.world.entity.player.Player player && com.smeshariki.gameplay.character.CharacterEntity.isPossessing(player)) cir.setReturnValue(false);
	}

	/** Скользящий не шагает: ни звука шагов, ни вибраций. */
	@Inject(method = "applyMovementEmissionAndPlaySound", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$quietSlide(CallbackInfo ci) {
		if (SlopeSurface.isSliding((Entity) (Object) this)) ci.cancel();
	}

	/** Трение и скорость берутся от ската, а не от блока под ним. */
	@Inject(method = "getBlockPosBelowThatAffectsMyMovement", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$slopeFriction(CallbackInfoReturnable<BlockPos> cir) {
		BlockPos slope = SlopeSurface.supportingSlope((Entity) (Object) this);
		if (slope != null) cir.setReturnValue(slope);
	}
}
