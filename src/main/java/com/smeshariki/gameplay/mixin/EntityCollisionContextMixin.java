package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.LeafPhase;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Форма блока для столкновений тела: листва пропускает тех, кто в {@link LeafPhase}. */
@Mixin(EntityCollisionContext.class)
public abstract class EntityCollisionContextMixin {
	@Shadow
	public abstract Entity getEntity();

	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$leafPhase(BlockState state, CollisionGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
		if (LeafPhase.passes(getEntity(), state)) cir.setReturnValue(Shapes.empty());
	}
}
