package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.smeshariki.gameplay.barrage.BarrageArrow;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Каждый тик стрела проверяет, не внутри ли она блока, и тогда втыкается: стрела обстрела в листве не втыкается. */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
	@Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape gameplaymod$notStuckInLeaves(BlockState state, BlockGetter level, BlockPos pos) {
		if ((Object) this instanceof BarrageArrow && BarrageArrow.passes(state)) return Shapes.empty();
		return state.getCollisionShape(level, pos);
	}
}
