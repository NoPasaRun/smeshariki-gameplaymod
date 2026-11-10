package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.slide.SlopeSurface;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/** Вселённый игрок: своя скорость ломания блоков, а в режиме выживания Shift не выводит его из персонажа. */
@Mixin(Player.class)
public abstract class PlayerMixin {
	/** Сколько тиков после ската тело ещё лежит. */
	@org.spongepowered.asm.mixin.Unique
	private static final int LIE_HOLD = 4;

	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void gameplaymod$possessedDestroySpeed(BlockState state, CallbackInfoReturnable<Float> cir) {
		Player self = (Player) (Object) this;
		if (!(self.getVehicle() instanceof CharacterEntity character)) return;
		float speed = cir.getReturnValue();
		// ваниль делит скорость на 5 в воздухе, а пассажир на земле не стоит: убираем этот штраф, если на земле персонаж
		if (!self.onGround() && character.onGround()) speed *= 5;
		cir.setReturnValue(speed * CharacterEntity.BREAK_SPEED_FACTOR);
	}

	/**
	 * Вселённому любимая еда персонажа (яблоко Ёжику и т. д.) доступна, пока здоровье не полное: ваниль не даёт есть при полной сытости игрока, а лечится-то персонаж.
	 * Сытость самого игрока тут ни при чём.
	 */
	@Inject(method = "canEat", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$possessedEats(boolean canAlwaysEat, CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (!(self.getVehicle() instanceof CharacterEntity character) || character.getHealth() >= character.getMaxHealth()) return;
		if (self.getMainHandItem().is(character.favoriteFood()) || self.getOffhandItem().is(character.favoriteFood())) cir.setReturnValue(true);
	}

	/** Shift не считает скат обрывом, иначе на нём нельзя и шагнуть. */
	@Inject(method = "canFallAtLeast", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$slopeIsNotEdge(double dx, double dz, double depth, CallbackInfoReturnable<Boolean> cir) {
		if (SlopeSurface.supportsWithin((Player) (Object) this, dx, dz, depth)) cir.setReturnValue(false);
	}

	/** Скользящий лежит: коробка низкая, глаза у самой поверхности. */
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$lieOnSlope(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		com.smeshariki.gameplay.slide.SlideRider rider = (com.smeshariki.gameplay.slide.SlideRider) self;
		rider.gameplaymod$setOffSlope(SlopeSurface.isSliding(self) ? 0 : rider.gameplaymod$offSlope() + 1);
		if (rider.gameplaymod$offSlope() <= LIE_HOLD && !self.isPassenger() && !self.isFallFlying()) {
			self.setPose(net.minecraft.world.entity.Pose.SLIDING);
			ci.cancel();
		}
	}

	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$stayPossessed(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof ServerPlayer player && CharacterEntity.mustStayPossessed(player)) cir.setReturnValue(false);
		// на верёвке Shift спускает по ней, а не выводит из персонажа
		Player self = (Player) (Object) this;
		if (self.isShiftKeyDown() && com.smeshariki.gameplay.rope.RopeEntity.heldBy(self) != null) cir.setReturnValue(false);
	}
}
