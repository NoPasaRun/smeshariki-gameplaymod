package com.smeshariki.gameplay;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Игроки, которые сейчас проходят сквозь листву (автобег): их тело, своё или вселённого персонажа, листву не задевает, а по лианам
 * не лезет (иначе ваниль ограничивает скорость, как на лестнице; {@code LivingEntityVineMixin}). Включает
 * клиент пакетом, потому что сервер сверяет позицию игрока со своими столкновениями и иначе возвращал бы его назад.
 */
public final class LeafPhase {
	private static final Set<UUID> PLAYERS = ConcurrentHashMap.newKeySet();

	private LeafPhase() {
	}

	public record Packet(boolean active) implements CustomPacketPayload {
		public static final Type<Packet> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "leaf_phase"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Packet> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Packet::active, Packet::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Packet.TYPE, Packet.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Packet.TYPE, (msg, ctx) -> set(ctx.player().getUUID(), msg.active()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PLAYERS.remove(handler.getPlayer().getUUID()));
	}

	public static void set(UUID player, boolean active) {
		if (active) PLAYERS.add(player);
		else PLAYERS.remove(player);
	}

	/** Вызывается на каждый блок при столкновениях: пока никто не проходит сквозь листву, проверка сразу заканчивается. */
	public static boolean passes(Entity entity, BlockState state) {
		if (PLAYERS.isEmpty() || entity == null || !state.is(BlockTags.LEAVES)) return false;
		return active(entity);
	}

	/** Лиана в клетке тела не лазаемая для того, кто проходит сквозь листву. */
	public static boolean passesVine(Entity entity, BlockState state) {
		if (PLAYERS.isEmpty() || entity == null || !isVine(state)) return false;
		return active(entity);
	}

	/** Лианы: обычные, пещерные, плакучие и вьющиеся. */
	public static boolean isVine(BlockState state) {
		return state.is(Blocks.VINE) || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT) || state.is(Blocks.WEEPING_VINES)
				|| state.is(Blocks.WEEPING_VINES_PLANT) || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT);
	}

	/** Тело (игрок или персонаж, которым он правит) принадлежит игроку из списка. */
	private static boolean active(Entity entity) {
		Player driver = entity instanceof Player player ? player : entity.getControllingPassenger() instanceof Player rider ? rider : null;
		return driver != null && PLAYERS.contains(driver.getUUID());
	}

	/** Тело стоит внутри листвы: если сделать её твёрдой сейчас, оно застрянет между блоками. */
	public static boolean insideLeaves(Entity entity) {
		AABB box = entity.getBoundingBox().deflate(1.0e-4);
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
			if (entity.level().getBlockState(pos).is(BlockTags.LEAVES)) return true;
		return false;
	}
}
