package com.smeshariki.gameplay.wind;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Маркер ветра: ПКМ ставит его в воздух, в клетку у блока под прицелом или в {@link #AIR_DISTANCE} блоках по взгляду.
 * Если в этой клетке уже есть маркер, поворачивает его.
 */
public class WindMarkerItem extends Item {
	private static final double AIR_DISTANCE = 3.0;

	public WindMarkerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		BlockPos pos = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection())
				: BlockPos.containing(player.getEyePosition().add(player.getLookAngle().scale(AIR_DISTANCE)));
		if (!level.isClientSide()) {
			List<WindMarkerEntity> here = level.getEntitiesOfClass(WindMarkerEntity.class, new AABB(pos));
			if (!here.isEmpty()) {
				here.get(0).turn(player);
			} else {
				WindMarkerEntity marker = new WindMarkerEntity(Winds.TYPE, level);
				marker.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
				marker.setYaw(player.getYRot());
				level.addFreshEntity(marker);
				marker.announce(player);
				if (!player.hasInfiniteMaterials()) player.getItemInHand(hand).shrink(1);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
