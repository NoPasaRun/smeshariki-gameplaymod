package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Карыч: синий шар-тело, розовые веки сверху, оранжевый клюв, бабочка, крылья на общем плече (как руки у остальных) и розовые лапки (крылья и лапки общие с Совуньей, BirdLimbs). Размеры сняты с
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

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint BEAK_PAINT = shifted(BEAK_AT), LID_L_PAINT = shifted(LID_L), LID_R_PAINT = shifted(LID_R);

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
		BirdLimbs.wing(c, p, head, BODY_Y, -1, 10f + (-85f - 10f) * spread + flap, -swing * 0.6f * (1 - spread), pose, light, overlay);
		BirdLimbs.wing(c, p, head, BODY_Y, 1, -10f + (85f + 10f) * spread - flap, swing * 0.6f * (1 - spread), pose, light, overlay);

		BirdLimbs.leg(c, p, feet, BODY_Y, -0.075f, swing * 0.15f, pose, light, overlay);
		BirdLimbs.leg(c, p, feet, BODY_Y, 0.075f, -swing * 0.15f, pose, light, overlay);
	}

	@Override
	public void renderHand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		BirdLimbs.hand(c, p, m, light, overlay);
	}
}
