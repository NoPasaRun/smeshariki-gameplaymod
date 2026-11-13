package com.smeshariki.gameplay.character;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Вселённый игрок применил умение персонажа (клавиша G): {@code arg} пуст (Совунья, Бараш), {@code scheme:<имя>} (Лосяш рисует схему) или
 * {@code call:<тип>|<действие>|<вариант>} (Нюша зовёт смешарика). Умение решает сервер.
 */
public record SkillUse(String arg) implements CustomPacketPayload {
	public static final Type<SkillUse> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "skill_use"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SkillUse> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SkillUse::arg, SkillUse::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
