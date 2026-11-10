package com.smeshariki.gameplay.slide;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Невидимый блок без столкновения. Небесный свет в открытом столбце с глубиной не слабеет; ниже этого слоя он уже не прямой и убывает на 1 за блок: плавное затемнение на 14 блоков. */
public class DarkVeilBlock extends Block {
	public static final MapCodec<DarkVeilBlock> CODEC = simpleCodec(DarkVeilBlock::new);

	public DarkVeilBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected int getLightDampening(BlockState state) {
		return 1;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return false;
	}
}
