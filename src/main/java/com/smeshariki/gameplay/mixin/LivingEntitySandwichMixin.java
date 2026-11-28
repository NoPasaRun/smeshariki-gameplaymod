package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.mob.Mobs;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/** Очистка всех эффектов (молоко, тотем, {@code /effect clear} без названия) морок Бутерброда не снимает: он возвращается с прежним остатком. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySandwichMixin {
	@Unique
	private MobEffectInstance gameplaymod$spell;

	@Inject(method = "removeAllEffects", at = @At("HEAD"))
	private void gameplaymod$keepSpell(CallbackInfoReturnable<Boolean> cir) {
		MobEffectInstance spell = Mobs.SPELL == null ? null : ((LivingEntity) (Object) this).getEffect(Mobs.SPELL);
		this.gameplaymod$spell = spell == null ? null : new MobEffectInstance(spell);
	}

	@Inject(method = "removeAllEffects", at = @At("RETURN"))
	private void gameplaymod$restoreSpell(CallbackInfoReturnable<Boolean> cir) {
		MobEffectInstance spell = this.gameplaymod$spell;
		this.gameplaymod$spell = null;
		LivingEntity self = (LivingEntity) (Object) this;
		if (spell != null && !self.level().isClientSide() && !self.hasEffect(Mobs.SPELL)) self.addEffect(spell);
	}
}
