package com.smeshariki.gameplay.character;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Вселённый игрок включил или выключил особенность персонажа (Ёжик катится клубком, Карыч планирует). */
public record CharacterAbility(boolean active) implements CustomPacketPayload {
	public static final Type<CharacterAbility> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "character_ability"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CharacterAbility> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, CharacterAbility::active, CharacterAbility::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
