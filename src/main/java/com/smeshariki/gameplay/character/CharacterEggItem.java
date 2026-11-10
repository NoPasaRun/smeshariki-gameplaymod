package com.smeshariki.gameplay.character;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Яйцо призыва смешарика: смешарик в мире один ({@link Singletons}), поэтому яйцо не создаёт нового, а переносит его в указанную точку
 * (нет в мире — появляется там). Занятого (в нём игрок или скрипт) не трогает. Яйцо тратится, как обычное, только если сработало.
 */
public class CharacterEggItem extends SpawnEggItem {
	private final EntityType<CharacterEntity> type;

	public CharacterEggItem(EntityType<CharacterEntity> type, Properties properties) {
		super(properties);
		this.type = type;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
		BlockPos clicked = context.getClickedPos();
		BlockPos at = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty() ? clicked : clicked.relative(context.getClickedFace());
		Player player = context.getPlayer();
		float yaw = player == null ? 0f : player.getYRot() + 180f;
		if (!Singletons.summonAt(server, this.type, Vec3.atBottomCenterOf(at), yaw)) {
			if (player != null) player.sendOverlayMessage(Component.translatable("message.gameplaymod.character_busy", this.type.getDescription()));
			return InteractionResult.FAIL;
		}
		if (player == null || !player.hasInfiniteMaterials()) context.getItemInHand().shrink(1);
		return InteractionResult.SUCCESS;
	}

	/** Ванильное яйцо по воде призывает ещё одного: у смешарика так нельзя, только по блоку. */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return InteractionResult.PASS;
	}
}
