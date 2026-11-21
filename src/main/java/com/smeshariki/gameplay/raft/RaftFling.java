package com.smeshariki.gameplay.raft;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Плот подбросило (сервер → клиент): клиент считает движение сам, поэтому получает ту же скорость. См. {@link RaftEntity#fling}. */
public record RaftFling(int entityId, float vx, float vy, float vz) implements CustomPacketPayload {
	public static final Type<RaftFling> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "raft_fling"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RaftFling> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RaftFling::entityId, ByteBufCodecs.FLOAT, RaftFling::vx, ByteBufCodecs.FLOAT, RaftFling::vy, ByteBufCodecs.FLOAT, RaftFling::vz, RaftFling::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
