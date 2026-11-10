package com.smeshariki.gameplay.slide;

import com.smeshariki.gameplay.GameplayMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Игрок в творческом режиме нажал ПКМ пустой рукой по скату {@link FieldSlideBlock}: точка попадания в клетке и опускать ли угол (Ctrl). Ctrl знает только клиент, поэтому отдельный пакет. */
public record SlideAdjust(BlockPos pos, float fx, float fz, boolean lower) implements CustomPacketPayload {
	public static final Type<SlideAdjust> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "slide_adjust"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SlideAdjust> CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, SlideAdjust::pos, ByteBufCodecs.FLOAT, SlideAdjust::fx, ByteBufCodecs.FLOAT, SlideAdjust::fz, ByteBufCodecs.BOOL, SlideAdjust::lower, SlideAdjust::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
