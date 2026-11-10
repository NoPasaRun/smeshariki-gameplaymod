package com.smeshariki.gameplay.character;

import java.util.List;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Сервер требует выбрать персонажа: игрок в режиме выживания не вселён. {@code allowed} — номера в {@link Possession#choices()}. */
public record ChooseCharacter(List<Integer> allowed) implements CustomPacketPayload {
	public static final Type<ChooseCharacter> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "choose_character"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ChooseCharacter> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), ChooseCharacter::allowed, ChooseCharacter::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
