package com.smeshariki.gameplay.character;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Автобег по команде сервера, для других модов (LevelMod): {@link #sync} получает, кому он сейчас нужен, и шлёт только изменения. Это как нажатие клавиши: дальше
 * игрок сам может его выключить, и новый приказ придёт, только если заказ снимут и дадут заново или игрок возродится. Кэш в памяти: хранит заказ тот, кто вызывает.
 */
public final class AutorunControl {
	private static final Set<UUID> WANTED = new HashSet<>();

	private AutorunControl() {
	}

	public record Command(boolean on) implements CustomPacketPayload {
		public static final Type<Command> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "autorun_command"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Command::on, Command::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Command.TYPE, Command.CODEC);
		// смерть сбрасывает автобег на клиенте, а заказ остался
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (WANTED.contains(newPlayer.getUUID())) ServerPlayNetworking.send(newPlayer, new Command(true));
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WANTED.remove(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> WANTED.clear());
	}

	/** Кому автобег нужен сейчас; вызывать можно каждый тик. Вошедший в сеть получит приказ при следующем вызове. */
	public static void sync(MinecraftServer server, Set<UUID> wanted) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			boolean want = wanted.contains(id);
			if (want == WANTED.contains(id)) continue;
			if (want) WANTED.add(id);
			else WANTED.remove(id);
			ServerPlayNetworking.send(player, new Command(want));
		}
	}
}
