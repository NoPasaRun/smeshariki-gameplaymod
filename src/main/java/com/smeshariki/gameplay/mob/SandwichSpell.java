package com.smeshariki.gameplay.mob;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Морок Бутерброда ({@code gameplaymod:sandwich_spell}, накладывает {@link SandwichEntity}): головокружение (ванильная тошнота, держится, пока
 * идёт морок), раз в {@link #DRAIN_PERIOD} тиков — {@link #DRAIN} урона (у вселённого игрока — его персонажу), а клиент заражённого рисует
 * всех живых существ бутербродами ({@code client/SandwichRenderer}). Снимается только съеденным настоящим Бутербродом
 * ({@link SandwichEntity#mobInteract}): молоко и прочая очистка всех эффектов его не трогают ({@code mixin/LivingEntitySandwichMixin}),
 * гибель снимает, как любые эффекты.
 */
public class SandwichSpell extends MobEffect {
	/** Длительность: 4 минуты 45 секунд. */
	public static final int TICKS = (4 * 60 + 45) * 20;
	/** Полсердца раз в полминуты. */
	public static final int DRAIN_PERIOD = 30 * 20;
	public static final float DRAIN = 1f;

	SandwichSpell() {
		super(MobEffectCategory.HARMFUL, 0xE4C660);
	}

	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		return true;
	}

	@Override
	public boolean applyEffectTick(ServerLevel level, LivingEntity entity, int amplifier) {
		MobEffectInstance self = entity.getEffect(Mobs.SPELL);
		int left = self == null ? 0 : self.getDuration();
		if (left <= 0) return true;
		if (!entity.hasEffect(MobEffects.NAUSEA)) entity.addEffect(new MobEffectInstance(MobEffects.NAUSEA, left, 0, false, false, false));
		// отсчёт от начала: первый урон через полминуты, а не сразу
		if (left != TICKS && Math.floorMod(TICKS - left, DRAIN_PERIOD) == 0) {
			Entity body = entity instanceof Player player ? CharacterEntity.body(player) : entity;
			if (body instanceof LivingEntity living) living.hurtServer(level, level.damageSources().magic(), DRAIN);
		}
		return true;
	}
}
