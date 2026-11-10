package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.barrage.BarrageArrow;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Луч движения стрелы обстрела не задевает листву. */
@Mixin(ClipContext.class)
public abstract class ClipContextMixin {
	@Shadow
	@Final
	private CollisionContext collisionContext;

	@Inject(method = "getBlockShape", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$arrowThroughLeaves(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
		if (this.collisionContext instanceof EntityCollisionContext context && context.getEntity() instanceof BarrageArrow && BarrageArrow.passes(state)) cir.setReturnValue(Shapes.empty());
	}
}
