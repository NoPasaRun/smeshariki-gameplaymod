package com.smeshariki.gameplay.rope;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Тарзанка без размаха: держащий не отходит от отвеса под креплением дальше {@link #LIMIT} блоков по горизонтали.
 * Длиннее обычной (до {@link RopeEntity#LONGEST}): в пещере с лавой висит со свода высоко над лавой последних волн. В остальном как обычная.
 */
public class CalmRopeEntity extends RopeEntity {
	public static final double LIMIT = 2.0;

	public CalmRopeEntity(EntityType<? extends CalmRopeEntity> type, Level level) {
		super(type, level);
	}

	@Override
	public float maxLength() {
		return LONGEST;
	}

	@Override
	protected double swingLimit() {
		return LIMIT;
	}

	@Override
	protected ItemStack dropStack() {
		return new ItemStack(Ropes.CALM_ITEM);
	}
}
