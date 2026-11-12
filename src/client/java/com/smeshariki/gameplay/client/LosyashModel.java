package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Лосяш: оранжевый шар-тело, большой рыжий нос, тяжёлые веки, два ветвистых тёмно-красных рога, ушки, руки-копыта и ноги-копыта. Размеры
 * сняты с картинки (0,00134 блока на пиксель). Текстура 256×256: лицо (0,0)–(128,128) — кусок картинки с глазами, бровями и
 * носом (нос и глаза — отдельные тела, красятся той же проекцией лица, поэтому выпирают из шара), спина (128,0) — цвет тела,
 * градиент рук (0,128)–(64,192), цвет рогов (64,128), цвет ноздрей (80,128).
 */
final class LosyashModel implements CharacterModel {
	private static final float BODY_R = RoundBody.R, BODY_Y = RoundBody.Y + 0.05f;
	private static final float FACE_HALF = RoundBody.FACE_HALF;
	/** Центры носа и глаз относительно центра тела (лицо красится проекцией от центра тела). */
	private static final float[] NOSE_AT = { 0, -0.04f, 0.31f }, EYE_L = { -0.114f, 0.147f, 0.30f }, EYE_R = { 0.114f, 0.147f, 0.30f };
	/** Основание рога относительно центра тела и его части: отрезки (x1, y1, x2, y2, радиус) в блоках от основания, для левого рога. */
	private static final float[] ANTLER_AT = { 0.08f, 0.38f, 0 }, ANTLER_Z = { 0.05f };
	private static final float[][] ANTLER_PARTS = {
			{ 0.0f, 0.0f, -0.28f, 0.08f, 0.075f }, // ладонь
			{ -0.28f, 0.08f, -0.28f, 0.27f, 0.045f }, // зубец у края
			{ -0.18f, 0.06f, -0.20f, 0.20f, 0.04f }, // средний
			{ -0.07f, 0.02f, -0.06f, 0.15f, 0.038f }, // ближний к голове
	};

	private static final Voxels.Mesh BODY = RoundBody.MESH;
	private static final Voxels.Mesh NOSE = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.15f, 0.10f, 0.11f } });
	private static final Voxels.Mesh EYE = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.10f, 0.105f, 0.08f } });
	private static final Voxels.Mesh EAR = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.07f, 0.05f, 0.04f } });
	private static final Voxels.Mesh ANTLER_L = antler(false), ANTLER_R = antler(true);

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint NOSE_PAINT = shifted(NOSE_AT), EYE_L_PAINT = shifted(EYE_L), EYE_R_PAINT = shifted(EYE_R);
	private static final Voxels.Paint ANTLER_PAINT = HoofLimbs.HOOF_PAINT, BODY_PAINT = HoofLimbs.BODY_PAINT;

	/** Рог плоский: ладонь и три зубца — отрезки со скруглённым сечением, правый рог зеркален левому. */
	private static Voxels.Mesh antler(boolean mirror) {
		float hz = ANTLER_Z[0];
		return Voxels.Mesh.of(PIXEL / 2, 0.5f, 0.4f, 0.35f, hz + 0.02f, (x, y, z) -> {
			float px = mirror ? -x : x;
			if (Math.abs(z) >= hz) return false;
			float round = (float) Math.sqrt(1 - (z / hz) * (z / hz));
			for (float[] s : ANTLER_PARTS) {
				if (distance(px, y, s[0], s[1], s[2], s[3]) <= s[4] * round) return true;
			}
			return false;
		});
	}

	private static float distance(float x, float y, float x1, float y1, float x2, float y2) {
		float dx = x2 - x1, dy = y2 - y1;
		float t = Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)));
		return (float) Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy));
	}

	/** Лицо для тела, смещённого от центра шара: координаты переводятся в систему шара. */
	private static Voxels.Paint shifted(float[] at) {
		return (x, y, z, front, out) -> FACE.uv(x + at[0], y + at[1], z + at[2], true, out);
	}

	@Override
	public float glassesY() {
		return 0.11f;
	}

	@Override
	public float glassesPush() {
		return 0.07f;
	}

	@Override
	public float centerY() {
		return BODY_Y;
	}

	@Override
	public void render(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float swing, float sway, float spread, CharacterPose pose, int light, int overlay) {
		Matrix4f body = new Matrix4f(head).translate(0, BODY_Y, 0);
		Voxels.draw(c, p, BODY, body, FACE, light, overlay);
		Voxels.draw(c, p, NOSE, new Matrix4f(body).translate(NOSE_AT[0], NOSE_AT[1], NOSE_AT[2]), NOSE_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_L[0], EYE_L[1], EYE_L[2]), EYE_L_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_R[0], EYE_R[1], EYE_R[2]), EYE_R_PAINT, light, overlay);

		// рога растут из макушки в стороны и вверх
		Voxels.draw(c, p, ANTLER_L, new Matrix4f(body).translate(-ANTLER_AT[0], ANTLER_AT[1], ANTLER_AT[2]), ANTLER_PAINT, light, overlay);
		Voxels.draw(c, p, ANTLER_R, new Matrix4f(body).translate(ANTLER_AT[0], ANTLER_AT[1], ANTLER_AT[2]), ANTLER_PAINT, light, overlay);

		// ушки на краю головы, кончиками наружу
		Voxels.draw(c, p, EAR, new Matrix4f(body).translate(-0.29f, 0.25f, 0).rotateZ((float) Math.toRadians(-20)), BODY_PAINT, light, overlay);
		Voxels.draw(c, p, EAR, new Matrix4f(body).translate(0.29f, 0.25f, 0).rotateZ((float) Math.toRadians(20)), BODY_PAINT, light, overlay);

		// ручки и ноги с копытами — общие у Нюши, Бараша и Лосяша (HoofLimbs)
		HoofLimbs.draw(c, p, head, feet, BODY_Y, 0.12f, swing, pose, light, overlay);
	}

	@Override
	public void renderHand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		HoofLimbs.hand(c, p, m, light, overlay);
	}
}
