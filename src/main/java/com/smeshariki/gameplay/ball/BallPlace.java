package com.smeshariki.gameplay.ball;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** ЛКМ мячом по блоку: поставить мяч в точку прицела {@code point} на грани {@code face} ({@link net.minecraft.core.Direction#get3DDataValue}). */
public record BallPlace(Vec3 point, int face) implements CustomPacketPayload {
	public static final Type<BallPlace> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "ball_place"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BallPlace> CODEC = StreamCodec.composite(Vec3.STREAM_CODEC, BallPlace::point, ByteBufCodecs.VAR_INT, BallPlace::face, BallPlace::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
