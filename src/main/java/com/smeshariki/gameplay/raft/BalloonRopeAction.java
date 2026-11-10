package com.smeshariki.gameplay.raft;

import com.smeshariki.gameplay.GameplayMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Щелчок колеса мыши, пока игрок держит стропу. */
public record BalloonRopeAction(int action) implements CustomPacketPayload {
	public static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, FORWARD = 4, BACK = 5;
	public static final Type<BalloonRopeAction> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "balloon_rope_action"));
	public static final StreamCodec<ByteBuf, BalloonRopeAction> CODEC = ByteBufCodecs.VAR_INT.map(BalloonRopeAction::new, BalloonRopeAction::action);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
