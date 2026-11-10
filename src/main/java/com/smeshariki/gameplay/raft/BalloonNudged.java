package com.smeshariki.gameplay.raft;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Шару дали толчок рулём (сервер → клиент): клиент считает движение сам, поэтому получает тот же толчок. См. {@link BalloonEntity#nudge}. */
public record BalloonNudged(int entityId, int forward, int left) implements CustomPacketPayload {
	public static final Type<BalloonNudged> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "balloon_nudged"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BalloonNudged> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BalloonNudged::entityId, ByteBufCodecs.VAR_INT, BalloonNudged::forward, ByteBufCodecs.VAR_INT, BalloonNudged::left, BalloonNudged::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
