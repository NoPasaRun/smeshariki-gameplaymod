package com.smeshariki.gameplay.character;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Всем игрокам: Бараш замедляет время (owner — номер его сущности, radius — радиус области вокруг него в блоках) или перестал (owner = −1). */
public record TimeSlowState(int owner, float radius) implements CustomPacketPayload {
	public static final Type<TimeSlowState> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "time_slow"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TimeSlowState> CODEC = StreamCodec.composite(ByteBufCodecs.INT, TimeSlowState::owner, ByteBufCodecs.FLOAT, TimeSlowState::radius, TimeSlowState::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
