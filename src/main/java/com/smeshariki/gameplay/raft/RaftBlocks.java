package com.smeshariki.gameplay.raft;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Блоки плота (сервер → клиент): размеры, палитра номеров состояний и серии (номер в палитре, сколько подряд). */
public record RaftBlocks(int entityId, int sx, int sy, int sz, List<Integer> palette, List<int[]> runs) implements CustomPacketPayload {
	public static final Type<RaftBlocks> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "raft_blocks"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RaftBlocks> CODEC = StreamCodec.ofMember(
			(RaftBlocks m, RegistryFriendlyByteBuf buf) -> {
				buf.writeVarInt(m.entityId);
				buf.writeVarInt(m.sx);
				buf.writeVarInt(m.sy);
				buf.writeVarInt(m.sz);
				buf.writeVarInt(m.palette.size());
				for (int id : m.palette) buf.writeVarInt(id);
				buf.writeVarInt(m.runs.size());
				for (int[] run : m.runs) {
					buf.writeVarInt(run[0]);
					buf.writeVarInt(run[1]);
				}
			}, buf -> {
				int entityId = buf.readVarInt(), sx = buf.readVarInt(), sy = buf.readVarInt(), sz = buf.readVarInt();
				int n = buf.readVarInt();
				List<Integer> palette = new ArrayList<>();
				for (int i = 0; i < n; i++) palette.add(buf.readVarInt());
				int r = buf.readVarInt();
				List<int[]> runs = new ArrayList<>();
				for (int i = 0; i < r; i++) runs.add(new int[] { buf.readVarInt(), buf.readVarInt() });
				return new RaftBlocks(entityId, sx, sy, sz, palette, runs);
			});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
