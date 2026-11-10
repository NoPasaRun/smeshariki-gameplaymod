package com.smeshariki.gameplay.barrage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Колесо мыши с маркером мёртвой зоны: щелчки {@code steps} меняют её размер по горизонтали. {@code entityId} — зона под прицелом, или
 * −1: тогда меняется размер, с которым игрок поставит следующую зону ({@link #placeHalf}).
 */
public record DeadzoneScroll(int entityId, int steps) implements CustomPacketPayload {
	public static final Type<DeadzoneScroll> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "deadzone_scroll"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DeadzoneScroll> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DeadzoneScroll::entityId, ByteBufCodecs.VAR_INT, DeadzoneScroll::steps, DeadzoneScroll::new);
	/** Половина стороны по горизонтали: по умолчанию, пределы, шаг одного щелчка (сторона меняется на 2 блока). */
	public static final float DEFAULT_HALF = 8f, MIN_HALF = 1f, MAX_HALF = 32f, STEP = 1f;
	/** Дальше этого от глаз зону колесом не меняем (с запасом на рассинхрон). */
	private static final double REACH = 8.0;

	/** Размер следующей зоны у каждого игрока; только кэш, сбрасывается при выходе. Клиент держит такой же. */
	private static final Map<UUID, Float> PLACE_HALF = new ConcurrentHashMap<>();

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static float step(float half, int steps) {
		return Math.clamp(half + steps * STEP, MIN_HALF, MAX_HALF);
	}

	public static float placeHalf(UUID player) {
		return PLACE_HALF.getOrDefault(player, DEFAULT_HALF);
	}

	static void init() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (msg, ctx) -> {
			ServerPlayer player = ctx.player();
			if (!player.getMainHandItem().is(Barrages.DEADZONE_ITEM) || msg.steps() == 0 || Math.abs(msg.steps()) > 8) return;
			if (msg.entityId() < 0) {
				float half = step(placeHalf(player.getUUID()), msg.steps());
				PLACE_HALF.put(player.getUUID(), half);
				player.sendOverlayMessage(Component.translatable("message.gameplaymod.deadzone_place", Math.round(half * 2), Math.round(DEFAULT_HALF * 2), Math.round(half * 2)));
				return;
			}
			if (!(player.level().getEntity(msg.entityId()) instanceof DeadzoneEntity zone) || zone.position().distanceTo(player.getEyePosition()) > REACH) return;
			float half = step(zone.halfX(), msg.steps());
			zone.setHalf(half, zone.halfY(), half);
			zone.announce(player);
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PLACE_HALF.remove(handler.getPlayer().getUUID()));
	}
}
