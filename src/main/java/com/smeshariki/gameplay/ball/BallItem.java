package com.smeshariki.gameplay.ball;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Мяч в руке: ПКМ — бросить по взгляду. Локация мяча лежит в данных предмета ({@code Place}) и переходит в брошенный мяч. */
public class BallItem extends Item {
	/** Скорость броска, блоков за тик. */
	static final double THROW_SPEED = 0.27;
	static final double THROW_UP = 0.075;

	public BallItem(Properties properties) {
		super(properties);
	}

	public static String place(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? "" : data.copyTag().getStringOr("Place", "");
	}

	public static ItemStack withPlace(ItemStack stack, String place) {
		if (place == null || place.isEmpty()) {
			CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove("Place"));
		} else {
			CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString("Place", place));
		}
		return stack;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.5f, 0.6f);
		if (!level.isClientSide()) {
			BallEntity ball = new BallEntity(Balls.TYPE, level);
			Vec3 eye = player.getEyePosition();
			ball.setPos(eye.x, eye.y - ball.getBbHeight() / 2 - 0.1, eye.z);
			ball.setPlace(place(stack));
			ball.launch(player.getLookAngle().scale(THROW_SPEED).add(0, THROW_UP, 0).add(player.getKnownMovement().multiply(1, 0, 1)));
			level.addFreshEntity(ball);
		}
		if (!player.hasInfiniteMaterials()) stack.shrink(1);
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
		String place = place(stack);
		if (!place.isEmpty()) out.accept(Component.translatable("item.gameplaymod.ball.place", place).withStyle(ChatFormatting.GRAY));
	}

}
