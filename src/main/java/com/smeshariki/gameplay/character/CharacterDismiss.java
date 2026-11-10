package com.smeshariki.gameplay.character;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок в творческом режиме нажал Esc: выйти из персонажа, персонаж при этом погибает. */
public record CharacterDismiss() implements CustomPacketPayload {
	public static final Type<CharacterDismiss> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "character_dismiss"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CharacterDismiss> CODEC = StreamCodec.unit(new CharacterDismiss());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
