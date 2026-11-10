package com.smeshariki.gameplay.current;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Маркер течения: ПКМ по воде ставит его в клетку воды по взгляду; по уже стоящему маркеру — поворачивает его. */
public class CurrentMarkerItem extends Item {
	public CurrentMarkerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
		if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
		BlockPos pos = hit.getBlockPos();
		if (!level.getFluidState(pos).is(FluidTags.WATER)) return InteractionResult.PASS;
		if (!level.isClientSide()) {
			List<CurrentMarkerEntity> here = level.getEntitiesOfClass(CurrentMarkerEntity.class, new AABB(pos));
			if (!here.isEmpty()) {
				here.get(0).turn(player);
			} else {
				CurrentMarkerEntity marker = new CurrentMarkerEntity(Currents.TYPE, level);
				marker.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
				marker.setYaw(player.getYRot());
				level.addFreshEntity(marker);
				if (!player.hasInfiniteMaterials()) player.getItemInHand(hand).shrink(1);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
