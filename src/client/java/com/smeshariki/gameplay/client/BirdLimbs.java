package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Общие крылья и лапки Карыча и Совуньи: крыло на общем плече (как руки у остальных), ножка-палочка и плоская лапа с тремя пальцами
 * вперёд. Крыло красится градиентом (0,128)–(64,192), ножка и лапа — пикселем (72, 136). Ножка тянется от бедра до лапы, поэтому тело может
 * стоять выше общего на {@code bodyY}.
 */
final class BirdLimbs {
	private static final Voxels.Mesh WING = Voxels.Mesh.ellipsoids(PIXEL, 0.5f, new float[][] { { 0, 0, 0, 0.085f, 0.21f, 0.06f } });
	/** Лапка: пятка и три пальца веером вперёд. */
	private static final Voxels.Mesh FOOT = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0.02f, 0.07f, 0.03f, 0.07f }, { 0, 0, 0.13f, 0.03f, 0.025f, 0.09f },
			{ -0.075f, 0, 0.1f, 0.028f, 0.025f, 0.08f }, { 0.075f, 0, 0.1f, 0.028f, 0.025f, 0.08f } });
	static final Voxels.Paint WING_PAINT = Voxels.gradient(0.21f), LEG_PAINT = Voxels.solid(72, 136);

	private BirdLimbs() {
	}

	/** Ножка: квадратная палочка от лапы (низ 0,04) до бедра и чуть выше, внутрь тела; центр — на высоте {@link #legCenter}. */
	private static Voxels.Mesh legMesh(float bodyY) {
		float half = legHalf(bodyY);
		return Voxels.Mesh.of(PIXEL / 2, 0.5f, 0.05f, half + 0.02f, 0.05f, (x, y, z) -> Math.abs(x) < 0.04f && Math.abs(z) < 0.035f && Math.abs(y) < half);
	}

	private static float legTop(float bodyY) {
		return Limbs.hip(bodyY) + 0.1f;
	}

	private static float legCenter(float bodyY) {
		return (legTop(bodyY) + 0.04f) / 2;
	}

	private static float legHalf(float bodyY) {
		return (legTop(bodyY) - 0.04f) / 2;
	}

	private static final java.util.Map<Float, Voxels.Mesh> LEGS = new java.util.HashMap<>();

	/** Крыло от первого лица. */
	static void hand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		Voxels.draw(c, p, WING, m, WING_PAINT, light, overlay);
	}

	/** Крыло растёт из общего плеча и качается вокруг него; {@code tiltDeg} — наклон низа наружу (при планировании раскрывается). */
	static void wing(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float bodyY, int side, float tiltDeg, float swing, CharacterPose pose, int light, int overlay) {
		Matrix4f m = Limbs.arm(root, bodyY, side, tiltDeg, swing, 0.2f, pose);
		Voxels.draw(c, p, WING, m, WING_PAINT, light, overlay);
	}

	/** Ножка-палочка и плоская лапа с носком вперёд; {@code x} — расстояние от оси. */
	static void leg(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float bodyY, float x, float stepZ, CharacterPose pose, int light, int overlay) {
		Matrix4f base = Limbs.leg(root, x, Limbs.hip(bodyY), stepZ, pose);
		Voxels.draw(c, p, LEGS.computeIfAbsent(bodyY, BirdLimbs::legMesh), new Matrix4f(base).translate(0, legCenter(bodyY), 0), LEG_PAINT, light, overlay);
		Voxels.draw(c, p, FOOT, new Matrix4f(base).translate(0, 0.03f, 0.05f), LEG_PAINT, light, overlay);
	}
}
