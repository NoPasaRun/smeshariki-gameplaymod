package com.smeshariki.gameplay.lava;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Из чего волна ({@link LavaWaveEntity}): её задаёт блок под маркером. Волна идёт только по верху блоков своего вида и, следуя им, поднимается и
 * опускается ({@link LavaWaveEntity#column}); в другом материале её нет.
 * <ul>
 * <li>{@link #LAVA} — поджигает, обрушение поднимает лаву озера на {@code Rise} слоёв.</li>
 * <li>{@link #WATER} — несёт тела по курсу без урона, обрушение поднимает воду озера на {@code Rise} слоёв.</li>
 * <li>{@link #SNOW} (лавина; снег, снежный блок, рыхлый снег) — несёт тела и слегка ранит, обрушение кладёт {@code Rise} слоёв снега там, где прошёл гребень.</li>
 * </ul>
 */
public enum WaveKind {
	LAVA("lava"), WATER("water"), SNOW("snow");

	/** Верх жидкости-источника над низом клетки. */
	public static final double FLUID_TOP = 8.0 / 9.0;

	public final String id;

	WaveKind(String id) {
		this.id = id;
	}

	public static WaveKind byId(String id) {
		for (WaveKind k : values()) if (k.id.equals(id)) return k;
		return LAVA;
	}

	/** Жидкость (лава, вода): у неё есть озеро, которое обрушение поднимает слоем. */
	public boolean fluid() {
		return this != SNOW;
	}

	/** Клетка — материал этой волны. */
	public boolean matches(BlockState state) {
		return switch (this) {
			case LAVA -> state.getFluidState().is(FluidTags.LAVA);
			case WATER -> state.getFluidState().is(FluidTags.WATER);
			case SNOW -> state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW);
		};
	}

	/** Какой волне годится клетка; null — никакой. */
	public static WaveKind of(BlockState state) {
		for (WaveKind k : values()) if (k.matches(state)) return k;
		return null;
	}

	/** Высота поверхности материала в клетке {@code pos} (клетка — этот материал). */
	public double surface(BlockGetter level, BlockPos pos, BlockState state) {
		if (fluid()) return pos.getY() + FLUID_TOP;
		if (state.is(Blocks.SNOW)) return pos.getY() + state.getValue(SnowLayerBlock.LAYERS) / 8.0;
		return pos.getY() + 1.0;
	}

	/** Блок, которым обрушение поднимает озеро слоем (только у жидкостей). */
	public BlockState source() {
		return this == WATER ? Blocks.WATER.defaultBlockState() : Blocks.LAVA.defaultBlockState();
	}
}
