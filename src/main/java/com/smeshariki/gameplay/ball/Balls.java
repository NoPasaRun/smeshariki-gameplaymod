package com.smeshariki.gameplay.ball;

import java.util.Collection;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.api.Mechanics;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Регистрация мяча ({@link BallEntity}, предмет {@link BallItem}) и команды {@code /ball}:
 * <ul>
 * <li>{@code /ball place <мячи> [локация]} — назначить мячам локацию-область LevelMod (без имени — снять границы);</li>
 * <li>{@code /ball hand [локация]} — то же для мяча в руке.</li>
 * </ul>
 */
public final class Balls {
	public static EntityType<BallEntity> TYPE;
	public static Item ITEM;

	private Balls() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "ball"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<BallEntity>of(BallEntity::new, MobCategory.MISC).sized(0.6f, 0.6f).clientTrackingRange(8).updateInterval(5).build(typeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "ball"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new BallItem(new Item.Properties().setId(itemKey).stacksTo(1)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(BallPlace.TYPE, BallPlace.CODEC);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(BallPlace.TYPE, (msg, ctx) -> place(ctx.player(), msg));
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(BallKick.TYPE, BallKick.CODEC);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(BallKick.TYPE, (msg, ctx) -> kick(ctx.player(), msg));
		CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(Commands.literal("ball")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("place")
						.then(Commands.argument("balls", EntityArgument.entities())
								.executes(c -> place(c, ""))
								.then(Commands.argument("place", StringArgumentType.greedyString()).suggests(Balls::suggestPlaces)
										.executes(c -> place(c, StringArgumentType.getString(c, "place"))))))
				.then(Commands.literal("hand")
						.executes(c -> hand(c, ""))
						.then(Commands.argument("place", StringArgumentType.greedyString()).suggests(Balls::suggestPlaces)
								.executes(c -> hand(c, StringArgumentType.getString(c, "place")))))));
	}

	private static void kick(ServerPlayer player, BallKick msg) {
		if (player.isSpectator() || !(player.level().getEntity(msg.ball()) instanceof BallEntity ball)) return;
		if (!ball.withinReach(player, 1.0)) return;
		if (!Float.isFinite(msg.dx()) || !Float.isFinite(msg.dy()) || !Float.isFinite(msg.dz()) || !Float.isFinite(msg.power())) return;
		ball.kick(new net.minecraft.world.phys.Vec3(msg.dx(), msg.dy(), msg.dz()), msg.power(), true);
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
	}

	/** ЛКМ мячом по блоку: мяч встаёт в точку прицела, касаясь грани (на верхней — лежит на ней, на боковой — прижат к ней и упадёт). */
	private static void place(ServerPlayer player, BallPlace msg) {
		ItemStack stack = player.getMainHandItem();
		net.minecraft.world.phys.Vec3 point = msg.point();
		if (!stack.is(ITEM) || player.isSpectator() || player.getEyePosition().distanceTo(point) > player.blockInteractionRange() + 1.0) return;
		net.minecraft.core.Direction face = net.minecraft.core.Direction.from3DDataValue(msg.face());
		double r = BallEntity.RADIUS;
		// точка — низ середины мяча; от грани отодвигаем на радиус, от нижней грани — на всю высоту
		net.minecraft.world.phys.Vec3 at = switch (face) {
			case UP -> point;
			case DOWN -> point.add(0, -2 * r, 0);
			default -> point.add(face.getStepX() * r, -r, face.getStepZ() * r);
		};
		net.minecraft.server.level.ServerLevel level = player.level();
		BallEntity ball = new BallEntity(TYPE, level);
		ball.setPos(at);
		if (!level.noCollision(ball, ball.getBoundingBox().deflate(1e-3))) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.ball.no_room"));
			return;
		}
		ball.setPlace(BallItem.place(stack));
		level.addFreshEntity(ball);
		ball.playSound(net.minecraft.sounds.SoundEvents.WOOL_PLACE, 0.6f, 1.2f);
		if (!player.hasInfiniteMaterials()) stack.shrink(1);
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlaces(CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(Mechanics.placeNames(c.getSource().getServer()), builder);
	}

	private static boolean unknown(CommandSourceStack source, String place) {
		if (place.isEmpty() || Mechanics.place(source.getServer(), place) != null) return false;
		source.sendFailure(Component.translatable("message.gameplaymod.ball.no_place", place));
		return true;
	}

	private static int place(CommandContext<CommandSourceStack> c, String place) throws CommandSyntaxException {
		CommandSourceStack source = c.getSource();
		if (unknown(source, place)) return 0;
		Collection<? extends Entity> targets = EntityArgument.getEntities(c, "balls");
		int count = 0;
		for (Entity e : targets) {
			if (e instanceof BallEntity ball) {
				ball.setPlace(place);
				count++;
			}
		}
		if (count == 0) {
			source.sendFailure(Component.translatable("message.gameplaymod.ball.none"));
			return 0;
		}
		int n = count;
		source.sendSuccess(() -> place.isEmpty() ? Component.translatable("message.gameplaymod.ball.cleared", n) : Component.translatable("message.gameplaymod.ball.placed", n, place), true);
		return count;
	}

	private static int hand(CommandContext<CommandSourceStack> c, String place) throws CommandSyntaxException {
		CommandSourceStack source = c.getSource();
		if (unknown(source, place)) return 0;
		ServerPlayer player = source.getPlayerOrException();
		ItemStack stack = player.getMainHandItem();
		if (!stack.is(ITEM)) {
			source.sendFailure(Component.translatable("message.gameplaymod.ball.not_in_hand"));
			return 0;
		}
		BallItem.withPlace(stack, place);
		source.sendSuccess(() -> place.isEmpty() ? Component.translatable("message.gameplaymod.ball.cleared", 1) : Component.translatable("message.gameplaymod.ball.placed", 1, place), false);
		return 1;
	}
}
