package com.smeshariki.gameplay.ball;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Удар по мячу при отпускании ЛКМ: направление {@code dx, dy, dz} и сила (доля {@link BallEntity#KICK}). */
public record BallKick(int ball, float dx, float dy, float dz, float power) implements CustomPacketPayload {
	public static final Type<BallKick> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "ball_kick"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BallKick> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, BallKick::ball, ByteBufCodecs.FLOAT, BallKick::dx, ByteBufCodecs.FLOAT, BallKick::dy, ByteBufCodecs.FLOAT, BallKick::dz, ByteBufCodecs.FLOAT, BallKick::power, BallKick::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
