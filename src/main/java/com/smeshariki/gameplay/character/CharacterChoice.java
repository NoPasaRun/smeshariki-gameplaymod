package com.smeshariki.gameplay.character;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок выбрал персонажа: номер в {@link Possession#choices()}. */
public record CharacterChoice(int index) implements CustomPacketPayload {
	public static final Type<CharacterChoice> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "character_choice"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CharacterChoice> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, CharacterChoice::index, CharacterChoice::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
