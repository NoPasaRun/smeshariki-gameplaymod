package com.smeshariki.gameplay.slide;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.Block;

/**
 * Скат трассы, отражённый по x: те же состояния и правила, но он поднимается к западу (и спускается к востоку), а не наоборот. Нужен там, где обычный
 * {@link FieldSlideBlock} не подходит: тот не может подняться к западу. Рядом с обычным соседи делят углы клетки так же.
 */
public class WestFieldSlideBlock extends FieldSlideBlock {
	public static final MapCodec<WestFieldSlideBlock> CODEC = simpleCodec(WestFieldSlideBlock::new);

	public WestFieldSlideBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected boolean mirrored() {
		return true;
	}
}
