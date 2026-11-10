package com.smeshariki.gameplay.rope;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** ЛКМ игрока по верёвке: схватить её в месте взгляда, отпустить или (удар по креплению) сломать. */
public record RopeAction(byte action, int entityId) implements CustomPacketPayload {
	public static final byte GRAB = 0, RELEASE = 1, BREAK = 2, ATTACH_RAFT = 3;
	public static final Type<RopeAction> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "rope_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RopeAction> CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE, RopeAction::action, ByteBufCodecs.VAR_INT, RopeAction::entityId, RopeAction::new);
	/** Дальше этого от глаз до верёвки сервер действие не принимает (с запасом на рассинхрон). */
	public static final double MAX_DISTANCE = 6.5;

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
