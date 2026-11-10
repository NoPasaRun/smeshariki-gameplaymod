package com.smeshariki.gameplay.lava;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Слой лавы озера: клетки одного уровня, соединённые с затравкой, заливаются или снимаются понемногу за тик. Общее у прилива ({@link LavaTideEntity}) и волны
 * ({@link LavaWaveEntity}). Если слой больше {@link #LIMIT}, озеро не закрыто: слой не трогается, а в лог пишется ошибка.
 */
final class LavaLayer {
	/** Больше клеток в слое быть не должно: озеро протекло. */
	static final int LIMIT = 6000;
	/** Сколько клеток слоя меняется за тик: слой идёт волной, а сервер не проседает. */
	static final int CELLS_PER_TICK = 40;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private final Deque<BlockPos> queue = new ArrayDeque<>();
	private BlockState queued;

	boolean busy() {
		return !this.queue.isEmpty();
	}

	/**
	 * Ставит в очередь слой y от столбца (x, z): при подъёме воздух и текущая лава становятся источником, при спуске лава становится воздухом.
	 * Возвращает false, если озеро протекло (тогда ничего не меняется).
	 */
	boolean plan(ServerLevel level, int x, int y, int z, boolean rising, Object owner) {
		List<BlockPos> order = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> front = new ArrayDeque<>();
		BlockPos seed = new BlockPos(x, y, z);
		front.add(seed);
		seen.add(seed);
		while (!front.isEmpty()) {
			BlockPos at = front.poll();
			BlockState state = level.getBlockState(at);
			boolean lava = state.getFluidState().is(FluidTags.LAVA);
			if (!(lava || rising && state.isAir())) continue;
			if (!rising || state.isAir() || !state.getFluidState().isSource()) order.add(at);
			if (seen.size() > LIMIT) {
				LogUtils.getLogger().error("{}: слой лавы y={} больше {} клеток, озеро не закрыто", owner, y, LIMIT);
				return false;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) if (seen.add(at.relative(d))) front.add(at.relative(d));
		}
		this.queue.addAll(order);
		this.queued = rising ? Blocks.LAVA.defaultBlockState() : Blocks.AIR.defaultBlockState();
		return true;
	}

	/** Меняет очередную порцию клеток; true, когда слой готов. */
	boolean pour(ServerLevel level) {
		for (int i = 0; i < CELLS_PER_TICK && !this.queue.isEmpty(); i++) level.setBlock(this.queue.poll(), this.queued, FLAGS);
		return this.queue.isEmpty();
	}

	void clear() {
		this.queue.clear();
	}
}
