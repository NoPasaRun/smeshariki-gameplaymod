package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Карыч: синий шар-тело, розовые веки сверху, оранжевый клюв, бабочка, крылья на общем плече (как руки у остальных) и розовые лапки. Размеры сняты с
 * картинки (0,00134 блока на пиксель). Текстура 256×256: лицо (0,0)–(128,128) — кусок картинки с глазами, клювом, веками и
 * бабочкой (клюв и веки — отдельные тела, красятся той же проекцией лица, поэтому выпирают из шара), спина (128,0) — цвет
 * тела, градиент крыльев (0,128)–(64,192), цвет лапок — (64,128).
 */
final class KarychModel implements CharacterModel {
	private static final float BODY_R = RoundBody.R, BODY_Y = RoundBody.Y;
	private static final float FACE_HALF = RoundBody.FACE_HALF;
	/** Центры клюва и век относительно центра тела (лицо красится проекцией от центра тела). */
	private static final float[] BEAK_AT = { 0, 0.08f, 0.29f }, LID_L = { -0.125f, 0.32f, 0.17f }, LID_R = { 0.125f, 0.32f, 0.17f };

	private static final Voxels.Mesh BODY = RoundBody.MESH;
	private static final Voxels.Mesh BEAK = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.27f, 0.14f, 0.10f }, { 0, 0.02f, 0.03f, 0.2f, 0.1f, 0.16f } });
	private static final Voxels.Mesh LID = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.13f, 0.10f, 0.13f } });
	private static final Voxels.Mesh WING = Voxels.Mesh.ellipsoids(PIXEL, 0.5f, new float[][] { { 0, 0, 0, 0.085f, 0.21f, 0.06f } });
	/** Лапка: пятка и три пальца веером вперёд. */
	private static final Voxels.Mesh FOOT = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0.02f, 0.07f, 0.03f, 0.07f }, { 0, 0, 0.13f, 0.03f, 0.025f, 0.09f },
			{ -0.075f, 0, 0.1f, 0.028f, 0.025f, 0.08f }, { 0.075f, 0, 0.1f, 0.028f, 0.025f, 0.08f } });
	private static final Voxels.Mesh LEG = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.04f, 0.1f, 0.03f } });

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint BEAK_PAINT = shifted(BEAK_AT), LID_L_PAINT = shifted(LID_L), LID_R_PAINT = shifted(LID_R);
	private static final Voxels.Paint WING_PAINT = Voxels.gradient(0.21f), PINK = Voxels.solid(72, 136);

	/** Лицо для тела, смещённого от центра шара: координаты переводятся в систему шара. */
	private static Voxels.Paint shifted(float[] at) {
		return (x, y, z, front, out) -> FACE.uv(x + at[0], y + at[1], z + at[2], true, out);
	}

	@Override
	public float glassesY() {
		return 0.24f;
	}

	@Override
	public float glassesPush() {
		return 0.12f;
	}

	@Override
	public float centerY() {
		return BODY_Y;
	}

	@Override
	public void render(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float swing, float sway, float spread, CharacterPose pose, int light, int overlay) {
		Matrix4f body = new Matrix4f(head).translate(0, BODY_Y, 0);
		Voxels.draw(c, p, BODY, body, FACE, light, overlay);
		Voxels.draw(c, p, BEAK, new Matrix4f(body).translate(BEAK_AT[0], BEAK_AT[1], BEAK_AT[2]), BEAK_PAINT, light, overlay);
		Voxels.draw(c, p, LID, new Matrix4f(body).translate(LID_L[0], LID_L[1], LID_L[2]), LID_L_PAINT, light, overlay);
		Voxels.draw(c, p, LID, new Matrix4f(body).translate(LID_R[0], LID_R[1], LID_R[2]), LID_R_PAINT, light, overlay);

		// крылья висят по бокам, низом наружу, и качаются вокруг плеча в противофазе с ногами
		// при планировании крылья раскрываются в стороны
		float flap = (float) Math.sin(System.nanoTime() / 1.2e8) * 6f * spread;
		wing(c, p, head, -1, 10f + (-85f - 10f) * spread + flap, -swing * 0.6f * (1 - spread), pose, light, overlay);
		wing(c, p, head, 1, -10f + (85f + 10f) * spread - flap, swing * 0.6f * (1 - spread), pose, light, overlay);

		leg(c, p, feet, -0.075f, swing * 0.15f, pose, light, overlay);
		leg(c, p, feet, 0.075f, -swing * 0.15f, pose, light, overlay);
	}

	@Override
	public void renderHand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		Voxels.draw(c, p, WING, m, WING_PAINT, light, overlay);
	}

	/** Крыло растёт из общего плеча (как руки у остальных) и качается вокруг него. */
	private static void wing(VertexConsumer c, PoseStack.Pose p, Matrix4f root, int side, float tiltDeg, float swing, CharacterPose pose, int light, int overlay) {
		Matrix4f m = Limbs.arm(root, BODY_Y, side, tiltDeg, swing, 0.2f, pose);
		Voxels.draw(c, p, WING, m, WING_PAINT, light, overlay);
	}

	/** Ножка-палочка и плоская лапа с носком вперёд. */
	private static void leg(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float x, float stepZ, CharacterPose pose, int light, int overlay) {
		Matrix4f base = Limbs.leg(root, x, Limbs.hip(BODY_Y), stepZ, pose);
		Voxels.draw(c, p, LEG, new Matrix4f(base).translate(0, 0.16f, 0), PINK, light, overlay);
		Voxels.draw(c, p, FOOT, new Matrix4f(base).translate(0, 0.03f, 0.05f), PINK, light, overlay);
	}
}
