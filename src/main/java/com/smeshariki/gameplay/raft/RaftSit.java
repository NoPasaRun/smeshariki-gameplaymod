package com.smeshariki.gameplay.raft;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Сесть на плот, шар или бревно в точку палубы (клиент → сервер): ПКМ пустой рукой по их блокам. Блоки плота не настоящие, и пустой
 * клик по воздуху на сервер не уходит, поэтому точку под прицелом находит клиент своим лучом, а сервер проверяет.
 */
public record RaftSit(int raftId, float x, float y, float z) implements CustomPacketPayload {
	public static final Type<RaftSit> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "raft_sit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RaftSit> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RaftSit::raftId, ByteBufCodecs.FLOAT, RaftSit::x, ByteBufCodecs.FLOAT, RaftSit::y, ByteBufCodecs.FLOAT, RaftSit::z, RaftSit::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
