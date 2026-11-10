package com.smeshariki.gameplay.barrage;

import java.util.List;
import java.util.function.Function;

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

/** Маркер обстрела, мёртвой зоны или жерла: ПКМ ставит его у блока под прицелом или в 3 блоках по взгляду; если там уже есть маркер, настраивает его. */
public class FireMarkerItem extends Item {
	private static final double AIR_DISTANCE = 3.0;
	private final Function<Level, FireMarkerEntity> factory;

	public FireMarkerItem(Properties properties, Function<Level, FireMarkerEntity> factory) {
		super(properties);
		this.factory = factory;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
		BlockPos pos = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection())
				: BlockPos.containing(player.getEyePosition().add(player.getLookAngle().scale(AIR_DISTANCE)));
		if (!level.isClientSide()) {
			List<FireMarkerEntity> here = level.getEntitiesOfClass(FireMarkerEntity.class, new AABB(pos));
			if (!here.isEmpty()) {
				here.get(0).click(player);
			} else {
				FireMarkerEntity marker = this.factory.apply(level);
				marker.placedBy(player, pos);
				level.addFreshEntity(marker);
				marker.announce(player);
				if (!player.hasInfiniteMaterials()) player.getItemInHand(hand).shrink(1);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
