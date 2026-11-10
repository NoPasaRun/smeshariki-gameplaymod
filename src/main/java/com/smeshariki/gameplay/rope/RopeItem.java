package com.smeshariki.gameplay.rope;

import net.minecraft.world.level.Level;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** Тарзанка в инвентаре: ПКМ по блоку делает на нём постоянное крепление, а второй конец остаётся в руке. */
public class RopeItem extends Item {
	private final java.util.function.Function<Level, RopeEntity> factory;

	public RopeItem(Properties properties, java.util.function.Function<Level, RopeEntity> factory) {
		super(properties);
		this.factory = factory;
	}

	public RopeEntity create(Level level) {
		return this.factory.apply(level);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) return InteractionResult.PASS;
		if (!context.getLevel().isClientSide()) {
			Vec3 anchor = context.getClickLocation().add(Vec3.atLowerCornerOf(context.getClickedFace().getUnitVec3i()).scale(0.05));
			RopeEntity rope = create(context.getLevel());
			rope.setupAnchor(context.getClickedPos(), anchor, player);
			context.getLevel().addFreshEntity(rope);
			if (!player.hasInfiniteMaterials()) context.getItemInHand().shrink(1);
		}
		return InteractionResult.SUCCESS;
	}
}
