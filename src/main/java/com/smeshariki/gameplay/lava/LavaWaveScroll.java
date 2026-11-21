package com.smeshariki.gameplay.lava;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Колесо мыши с маркером волны лавы в руке, наведённым на маркер волны: щелчки {@code steps} меняют полуширину волны ({@code HalfWidth}) на блок. */
public record LavaWaveScroll(int entityId, int steps) implements CustomPacketPayload {
	public static final Type<LavaWaveScroll> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "lava_wave_scroll"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LavaWaveScroll> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, LavaWaveScroll::entityId, ByteBufCodecs.VAR_INT, LavaWaveScroll::steps, LavaWaveScroll::new);
	public static final float STEP = 1f;
	/** Дальше этого от глаз маркер колесом не меняем (с запасом на рассинхрон). */
	private static final double REACH = 8.0;

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	static void init() {
		PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TYPE, (msg, ctx) -> {
			ServerPlayer player = ctx.player();
			if (!player.getMainHandItem().is(LavaWaves.ITEM) || msg.steps() == 0 || Math.abs(msg.steps()) > 8) return;
			if (!(player.level().getEntity(msg.entityId()) instanceof LavaWaveEntity wave) || wave.position().distanceTo(player.getEyePosition()) > REACH) return;
			wave.setHalfWidth(wave.halfWidth() + msg.steps() * STEP);
			wave.announceWidth(player);
		});
	}
}
