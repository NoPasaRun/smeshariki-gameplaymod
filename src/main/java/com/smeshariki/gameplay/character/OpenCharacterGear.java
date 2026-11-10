package com.smeshariki.gameplay.character;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Вселённый игрок нажал клавишу инвентаря: открыть окно с одеждой персонажа. */
public record OpenCharacterGear() implements CustomPacketPayload {
	public static final Type<OpenCharacterGear> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "open_character_gear"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenCharacterGear> CODEC = StreamCodec.unit(new OpenCharacterGear());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
