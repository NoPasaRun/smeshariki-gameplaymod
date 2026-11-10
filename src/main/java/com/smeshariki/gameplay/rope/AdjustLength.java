package com.smeshariki.gameplay.rope;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Клиент просит изменить длину верёвки, конец которой у него в руках. */
public record AdjustLength(float delta) implements CustomPacketPayload {
	public static final Type<AdjustLength> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "adjust_length"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AdjustLength> CODEC = StreamCodec.composite(ByteBufCodecs.FLOAT, AdjustLength::delta, AdjustLength::new);
	/** Больше за один пакет не принимаем. */
	public static final float MAX_STEP = 0.5f;

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
