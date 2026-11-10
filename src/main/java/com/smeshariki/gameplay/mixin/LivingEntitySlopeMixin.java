package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.slide.SlideRider;
import com.smeshariki.gameplay.slide.SlopeSurface;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** На скате тело скользит, а не ходит: своё движение, нет прыжка, ноги не шагают. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySlopeMixin implements SlideRider {
	@Unique
	private boolean gameplaymod$launched;
	@Unique
	private int gameplaymod$offSlope = 1000;
	@Unique
	private boolean gameplaymod$walkingOut, gameplaymod$landedTick;

	@Unique
	private Vec3 gameplaymod$walkOutFrom;

	@Override
	public Vec3 gameplaymod$walkOutFrom() {
		return gameplaymod$walkOutFrom;
	}

	@Override
	public void gameplaymod$setWalkOutFrom(Vec3 at) {
		gameplaymod$walkOutFrom = at;
	}

	@Override
	public boolean gameplaymod$walkingOut() {
		return gameplaymod$walkingOut;
	}

	@Override
	public void gameplaymod$setWalkingOut(boolean walkingOut) {
		gameplaymod$walkingOut = walkingOut;
	}

	@Override
	public boolean gameplaymod$landedTick() {
		return gameplaymod$landedTick;
	}

	@Override
	public void gameplaymod$setLandedTick(boolean landed) {
		gameplaymod$landedTick = landed;
	}

	@Override
	public int gameplaymod$offSlope() {
		return gameplaymod$offSlope;
	}

	@Override
	public void gameplaymod$setOffSlope(int ticks) {
		gameplaymod$offSlope = ticks;
	}

	@Override
	public boolean gameplaymod$launched() {
		return gameplaymod$launched;
	}

	@Override
	public void gameplaymod$setLaunched(boolean launched) {
		gameplaymod$launched = launched;
	}

	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$slideInstead(Vec3 input, CallbackInfo ci) {
		if (SlopeSurface.travel((LivingEntity) (Object) this)) ci.cancel();
	}

	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$noJumpOnSlope(CallbackInfo ci) {
		if (SlopeSurface.isSliding((LivingEntity) (Object) this)) ci.cancel();
	}

	@ModifyVariable(method = "updateWalkAnimation", at = @At("HEAD"), argsOnly = true)
	private float gameplaymod$stillLegs(float distance) {
		return SlopeSurface.isSliding((LivingEntity) (Object) this) ? 0 : distance;
	}
}
