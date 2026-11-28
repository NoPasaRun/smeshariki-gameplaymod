package com.smeshariki.gameplay.mob;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Яйцо Бутерброда: обычное яйцо призыва (ПКМ по блоку ставит Бутерброда; он в мире один, прежний исчезает) и еда для заражённого мороком
 * ({@link SandwichSpell}): с мороком зажатый ПКМ — съесть, как обычную еду, морок и головокружение сняты. Без морока оно не естся, а с мороком
 * не ставится: иначе заражённый, целясь в землю, поставил бы Бутерброда вместо еды.
 */
public class SandwichEggItem extends SpawnEggItem {
	public SandwichEggItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player != null && player.hasEffect(Mobs.SPELL)) return InteractionResult.PASS;
		return super.useOn(context);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		Consumable consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable != null && player.hasEffect(Mobs.SPELL)) return consumable.startConsuming(player, stack, hand);
		return super.use(level, player, hand);
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		if (!level.isClientSide()) {
			entity.removeEffect(Mobs.SPELL);
			entity.removeEffect(MobEffects.NAUSEA);
		}
		return super.finishUsingItem(stack, level, entity);
	}
}
