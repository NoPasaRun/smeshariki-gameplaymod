package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Совунья: фиолетовый шар-тело, большие глаза с тяжёлыми веками, оранжевый клюв, острые ушки с кисточками, вязаная шапка с помпоном;
 * крылья и лапки общие с Карычем ({@link BirdLimbs}). Размеры сняты с картинки (0,00514 блока на пиксель, радиус шара 73 пикселя).
 * Текстура 256×256: лицо (0,0)–(128,128) — кусок картинки (глаза с веками и клюв — отдельные тела, красятся той же проекцией лица, поэтому
 * выпирают), спина (128,0) — фиолетовая с контуром, градиент крыльев (0,128)–(64,192), метки: лапки и кисточки (72,136), шапка и помпон (88,136),
 * полоса шапки (88,152).
 */
final class SovunyaModel implements CharacterModel {
	private static final float BODY_Y = RoundBody.Y + 0.05f;
	private static final float[] BEAK_AT = { 0, 0, 0.35f }, EYE_L = { -0.121f, 0.108f, 0.30f }, EYE_R = { 0.121f, 0.108f, 0.30f };
	/** Центр шапки (купол) и помпона относительно центра тела. */
	private static final float CAP_X = -0.05f, CAP_Y = 0.33f, POM_X = -0.075f, POM_Y = 0.545f;

	private static final Voxels.Mesh BODY = RoundBody.MESH;
	private static final Voxels.Mesh BEAK = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.067f, 0.082f, 0.07f }, { 0, -0.015f, 0.05f, 0.045f, 0.06f, 0.07f } });
	private static final Voxels.Mesh EYE = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.123f, 0.139f, 0.085f } });
	private static final Voxels.Mesh CAP = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.14f, 0.165f, 0.13f } });
	private static final Voxels.Mesh POMPOM = pompom();
	private static final Voxels.Mesh EAR_L = ear(-1, false), EAR_R = ear(1, false), TUFT_L = ear(-1, true), TUFT_R = ear(1, true);

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint BEAK_PAINT = RoundBody.shifted(BEAK_AT), EYE_L_PAINT = RoundBody.shifted(EYE_L), EYE_R_PAINT = RoundBody.shifted(EYE_R);
	private static final Voxels.Paint MAROON = Voxels.solid(88, 136), ORANGE = Voxels.solid(88, 152), DARK = Voxels.solid(72, 136);
	/** Шапка: бордовая с оранжевой полосой на трети высоты (координата y — от центра купола). */
	private static final Voxels.Paint CAP_PAINT = (x, y, z, front, out) -> (y > 0.055f && y < 0.12f ? ORANGE : MAROON).uv(x, y, z, front, out);

	/** Помпон: шар и пушистая оторочка из шаров поменьше. */
	private static Voxels.Mesh pompom() {
		float[][] segs = new float[8][];
		segs[0] = new float[] { 0, 0, 0, 0, 0, 0, 0.06f };
		for (int k = 0; k < 7; k++) {
			double a = Math.toRadians(360.0 * k / 7);
			float x = (float) Math.cos(a) * 0.045f, y = (float) Math.sin(a) * 0.045f;
			segs[k + 1] = new float[] { x, y, 0, x, y, 0, 0.04f };
		}
		return Voxels.capsules(PIXEL / 2, 0.5f, segs);
	}

	/** Острое ушко (конус из капсул) и кисточка на кончике (два тонких волоска); {@code side} — −1 слева, 1 справа. */
	private static Voxels.Mesh ear(int side, boolean tuft) {
		float[][] segs = tuft
				? new float[][] { { -0.37f, 0.42f, 0, -0.44f, 0.43f, 0, 0.012f }, { -0.37f, 0.42f, 0, -0.38f, 0.5f, 0, 0.012f } }
				: new float[][] { { -0.25f, 0.22f, 0, -0.33f, 0.31f, 0, 0.055f }, { -0.33f, 0.31f, 0, -0.37f, 0.42f, 0, 0.03f } };
		if (side > 0) for (float[] s : segs) {
			s[0] = -s[0];
			s[3] = -s[3];
		}
		return Voxels.capsules(PIXEL / 2, 0.5f, segs);
	}

	@Override
	public float glassesY() {
		return 0.1f;
	}

	@Override
	public float glassesPush() {
		return 0.06f;
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
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_L[0], EYE_L[1], EYE_L[2]), EYE_L_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_R[0], EYE_R[1], EYE_R[2]), EYE_R_PAINT, light, overlay);

		// ушки-«рожки» на макушке по бокам, кисточки на кончиках
		Voxels.draw(c, p, EAR_L, body, HoofLimbs.BODY_PAINT, light, overlay);
		Voxels.draw(c, p, EAR_R, body, HoofLimbs.BODY_PAINT, light, overlay);
		Voxels.draw(c, p, TUFT_L, body, DARK, light, overlay);
		Voxels.draw(c, p, TUFT_R, body, DARK, light, overlay);

		// шапка с помпоном
		Voxels.draw(c, p, CAP, new Matrix4f(body).translate(CAP_X, CAP_Y, 0), CAP_PAINT, light, overlay);
		Voxels.draw(c, p, POMPOM, new Matrix4f(body).translate(POM_X, POM_Y, 0), MAROON, light, overlay);

		BirdLimbs.wing(c, p, head, BODY_Y, -1, 10f, -swing * 0.6f, pose, light, overlay);
		BirdLimbs.wing(c, p, head, BODY_Y, 1, -10f, swing * 0.6f, pose, light, overlay);
		BirdLimbs.leg(c, p, feet, BODY_Y, -0.1f, swing * 0.15f, pose, light, overlay);
		BirdLimbs.leg(c, p, feet, BODY_Y, 0.1f, -swing * 0.15f, pose, light, overlay);
	}

	@Override
	public void renderHand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		BirdLimbs.hand(c, p, m, light, overlay);
	}
}
