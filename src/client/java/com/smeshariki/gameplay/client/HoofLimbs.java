package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Общие руки и ноги с копытами у Нюши, Бараша и Лосяша: рука и нога цвета тела, на конце копыто (шире ноги, с носком вперёд), на плече
 * мини-кубик. Красятся метками текстуры персонажа: тело — пиксель (200, 64), градиент руки — область (0,128)–(64,192), копыто — пиксель
 * (72, 136). Тело стоит выше общего на {@code bodyY} (ноги дотягиваются до земли).
 */
final class HoofLimbs {
	private static final Voxels.Mesh ARM = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.065f, 0.08f, 0.065f } });
	private static final Voxels.Mesh LEG = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.055f, 0.13f, 0.05f } });
	/** Мини-кубик на плече сверху руки (3×3×3 клетки по 1/32). */
	private static final Voxels.Mesh SHOULDER = Voxels.Mesh.of(PIXEL / 2, 0.5f, 0.06f, 0.06f, 0.06f, (x, y, z) -> Math.abs(x) < 0.05f && Math.abs(y) < 0.05f && Math.abs(z) < 0.05f);
	private static final Voxels.Mesh HOOF = Voxels.Mesh.of(PIXEL / 2, 0.5f, 0.12f, 0.08f, 0.14f, (x, y, z) -> {
		float dx = x / 0.075f, dy = y / 0.065f, dz = (z - 0.015f) / 0.095f;
		return y <= 0.03f && dx * dx + dy * dy + dz * dz <= 1f;
	});
	static final Voxels.Paint BODY_PAINT = Voxels.solid(200, 64), HOOF_PAINT = Voxels.solid(72, 136), ARM_PAINT = Voxels.gradient(0.08f);

	private HoofLimbs() {
	}

	/** Рука от первого лица: центр руки в начале, плечо сверху, копыто снизу. */
	static void hand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		Voxels.draw(c, p, ARM, m, ARM_PAINT, light, overlay);
		Voxels.draw(c, p, HOOF, new Matrix4f(m).translate(0, -0.1f, 0.01f), HOOF_PAINT, light, overlay);
		Voxels.draw(c, p, SHOULDER, new Matrix4f(m).translate(0, 0.08f, 0), BODY_PAINT, light, overlay);
	}

	/** Рука-копыто: цвета тела, на конце копыто, на плече мини-кубик; качается вокруг плеча. */
	static void arm(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float bodyY, int side, float swing, CharacterPose pose, int light, int overlay) {
		hand(c, p, Limbs.arm(root, bodyY, side, Limbs.tilt(side), swing, 0.08f, pose), light, overlay);
	}

	/** Нога-копыто: короткая нога цвета тела и копыто на земле; {@code x} — расстояние от оси. */
	static void leg(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float bodyY, float x, float stepZ, CharacterPose pose, int light, int overlay) {
		Matrix4f base = Limbs.leg(root, x, Limbs.hip(bodyY), stepZ, pose);
		Voxels.draw(c, p, LEG, new Matrix4f(base).translate(0, 0.2f, 0), BODY_PAINT, light, overlay);
		Voxels.draw(c, p, HOOF, new Matrix4f(base).translate(0, 0.065f, 0.02f), HOOF_PAINT, light, overlay);
	}

	/** Обе руки (в противофазе с ногами) и обе ноги при ходьбе. */
	static void draw(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float bodyY, float legX, float swing, CharacterPose pose, int light, int overlay) {
		arm(c, p, head, bodyY, -1, -swing * 0.6f, pose, light, overlay);
		arm(c, p, head, bodyY, 1, swing * 0.6f, pose, light, overlay);
		leg(c, p, feet, bodyY, -legX, swing * 0.15f, pose, light, overlay);
		leg(c, p, feet, bodyY, legX, -swing * 0.15f, pose, light, overlay);
	}
}
