package com.smeshariki.gameplay.rope;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Аркан (синяя верёвка): закреплённый на сущности — любой: живое, предмет на земле, стрела, мяч, плот, шар ({@link Ropes#lasso}) — тянет не
 * держащего к креплению, а её к держащему ({@link RopeEntity#pullsAnchor}): отошёл дальше длины или укоротил верёвку, и сущность
 * подтягивается к руке. Держащего при этом ничто не держит. Короткая, как верёвка для подтягивания (от {@link PullRopeEntity#SHORTEST}).
 * Только на блоке ведёт себя как обычная верёвка: блок не сдвинуть.
 */
public class TowRopeEntity extends RopeEntity {
	public TowRopeEntity(EntityType<? extends TowRopeEntity> type, Level level) {
		super(type, level);
	}

	@Override
	public float minLength() {
		return PullRopeEntity.SHORTEST;
	}

	@Override
	protected boolean pullsAnchor() {
		return true;
	}

	@Override
	protected ItemStack dropStack() {
		return new ItemStack(Ropes.TOW_ITEM);
	}
}
