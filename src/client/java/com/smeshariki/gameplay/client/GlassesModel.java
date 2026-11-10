package com.smeshariki.gameplay.client;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Очки на лице персонажа: две оправы-кольца, переносица и дужки, облегающие шар-тело (воксельная оболочка вокруг центра тела,
 * вырезанная по плоскому рисунку очков). Красятся чёрно-фиолетовой меткой в текстуре персонажа (пиксель (98, 130)).
 */
final class GlassesModel {
	private static final float R = 0.375f, CELL = 1f / 32;
	/** Расстояние глаза от оси, внутренний и внешний радиус оправы, толщина оболочки над телом. */
	private static final float EYE_X = 0.12f, RING_IN = 0.105f, RING_OUT = 0.14f, SHELL_IN = 0.0f, SHELL_OUT = 0.06f;
	private static final Voxels.Paint FRAME = Voxels.solid(98, 130);
	/** Оболочки для разной высоты глаз (по ключу — высота над центром тела). */
	private static final Map<Float, Voxels.Mesh> MESHES = new HashMap<>();

	private GlassesModel() {
	}

	/** Оболочка очков для глаз на высоте {@code dy} над центром тела; рисунок задан от центра глаз. */
	private static Voxels.Mesh mesh(float dy) {
		return MESHES.computeIfAbsent(dy, k -> Voxels.Mesh.of(CELL, 0.5f, R + 0.12f, 0.17f, R + 0.12f, (x, y, z) -> {
			float r = (float) Math.sqrt(x * x + (y + dy) * (y + dy) + z * z);
			if (r < R + SHELL_IN || r > R + SHELL_OUT) return false;
			float ex = Math.abs(x) - EYE_X, ring = (float) Math.sqrt(ex * ex + y * y);
			boolean frame = z > 0 && ring >= RING_IN && ring <= RING_OUT;
			boolean bridge = z > 0 && Math.abs(x) < EYE_X - RING_IN + 0.01f && Math.abs(y - 0.02f) < 0.014f;
			boolean temple = z > -0.12f && Math.abs(x) > EYE_X + RING_OUT - 0.01f && Math.abs(y) < 0.012f;
			return frame || bridge || temple;
		}));
	}

	/** {@code head} — матрица корпуса; {@code eyeY} — высота глаз над ногами, {@code dy} — над центром тела, {@code push} — вынос лица вперёд. */
	static void draw(VertexConsumer c, PoseStack.Pose p, Matrix4f head, float eyeY, float dy, float push, int light, int overlay) {
		Voxels.draw(c, p, mesh(dy), new Matrix4f(head).translate(0, eyeY, push), FRAME, light, overlay);
	}
}
