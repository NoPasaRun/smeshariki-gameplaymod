package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.rope.RopeAction;
import com.smeshariki.gameplay.rope.RopeEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Выбор верёвки под прицелом: берётся первая клетка воздуха на луче взгляда (до стены и не дальше досягаемости), в которой
 * есть участок какой-нибудь висящей или зацепленной верёвки не дальше {@link #MAX_MISS} от луча; из таких — ближайший к лучу.
 */
final class RopePicker {
	/** Как далеко дотягивается рука, блоков. */
	private static final double REACH = 4.0;
	/** Попадание ближе этого к креплению считается ударом по самому креплению. */
	private static final double ANCHOR_RADIUS = 0.5;
	/** Насколько далеко от луча может быть участок верёвки в той же клетке, блоков. */
	private static final double MAX_MISS = 0.75;
	/** Шаг по лучу и число проб верёвки на блок её длины. */
	private static final double RAY_STEP = 0.05;
	private static final int SAMPLES_PER_BLOCK = 6;

	private RopePicker() {
	}

	/** Что и где под прицелом: действие ({@code GRAB} или {@code BREAK}) и номер сущности; null — ничего. */
	record Pick(byte action, int entityId) {
	}

	private record Part(RopeEntity rope, Vec3 point) {
	}

	static Pick pick(Minecraft mc, LocalPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		var level = player.level();
		BlockHitResult wall = level.clip(new ClipContext(eye, eye.add(look.scale(REACH)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double maxDist = wall.getType() == HitResult.Type.MISS ? REACH : wall.getLocation().distanceTo(eye) - 0.001;

		Map<Long, List<Part>> cells = new HashMap<>();
		for (RopeEntity rope : level.getEntitiesOfClass(RopeEntity.class, player.getBoundingBox().inflate(RopeEntity.LONGEST + REACH + 2), r -> r.mode() != RopeEntity.HELD)) {
			Vec3 a = rope.anchor(), b = rope.position();
			double sag = RopeEntity.sagOf(a, b, rope.length());
			int n = (int) (rope.length() * SAMPLES_PER_BLOCK) + 8;
			for (int i = 0; i <= n; i++) {
				Vec3 point = RopeEntity.curvePoint(a, b, sag, (double) i / n);
				cells.computeIfAbsent(BlockPos.containing(point).asLong(), k -> new ArrayList<>()).add(new Part(rope, point));
			}
		}
		if (cells.isEmpty()) return null;

		for (double s = RAY_STEP; s <= maxDist; s += RAY_STEP) {
			List<Part> here = cells.get(BlockPos.containing(eye.add(look.scale(s))).asLong());
			if (here == null) continue;
			Part best = null;
			double bestPerp = MAX_MISS;
			for (Part part : here) {
				Vec3 rel = part.point().subtract(eye);
				double perp = rel.subtract(look.scale(rel.dot(look))).length();
				if (perp < bestPerp) {
					bestPerp = perp;
					best = part;
				}
			}
			if (best == null) continue;
			return new Pick(best.point().distanceTo(best.rope().anchor()) < ANCHOR_RADIUS ? RopeAction.BREAK : RopeAction.GRAB, best.rope().getId());
		}
		return null;
	}
}
