package com.smeshariki.gameplay.raft;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Добавка к скорости плота от столкновения с телом (в обе стороны): плот считают все стороны, поэтому толчок получают все. См. {@link RaftEntity#bump}. */
public record RaftPush(int entityId, float dx, float dz) implements CustomPacketPayload {
	public static final Type<RaftPush> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "raft_push"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RaftPush> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RaftPush::entityId, ByteBufCodecs.FLOAT, RaftPush::dx, ByteBufCodecs.FLOAT, RaftPush::dz, RaftPush::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
