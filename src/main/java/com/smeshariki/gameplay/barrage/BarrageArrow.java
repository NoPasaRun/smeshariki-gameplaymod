package com.smeshariki.gameplay.barrage;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Стрела обстрела и мёртвой зоны: листву пролетает, как и тело на автобеге. Свой тип нужен, потому что движение стрелы считает и клиент. В файл мира не сохраняется. */
public class BarrageArrow extends Arrow {
	public BarrageArrow(EntityType<? extends BarrageArrow> type, Level level) {
		super(type, level);
	}

	/** Эту ли форму пролетает стрела. */
	public static boolean passes(BlockState state) {
		return state.is(BlockTags.LEAVES);
	}
}
