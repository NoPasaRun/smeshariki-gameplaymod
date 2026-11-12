package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Нюша: розовый шар-тело, рыжая чёлка, большие глаза, круглый пятачок, свисающие ушки-лопухи, хвост-«пучок» с белым цветком и рыжим кончиком;
 * руки и ноги с копытами общие с Барашем и Лосяшем ({@link HoofLimbs}). Размеры сняты с картинки (0,00493 блока на пиксель, радиус шара 76 пикселей).
 * Текстура 256×256: лицо (0,0)–(128,128) — кусок картинки (руки на ней стёрты; пятачок и глаза — отдельные тела, красятся той же проекцией
 * лица, поэтому выпирают), спина (128,0) — розовая с рыжей чёлкой, градиент рук (0,128)–(64,192), метки: копыто (72,136), волосы (88,136),
 * белый цветок (72,152), его кайма (88,152).
 */
final class NyushaModel implements CharacterModel {
	private static final float BODY_Y = RoundBody.Y + 0.07f;
	/** Центры пятачка и глаз относительно центра тела (лицо красится проекцией от центра тела). */
	private static final float[] SNOUT_AT = { -0.01f, -0.069f, 0.33f }, EYE_L = { -0.104f, 0.044f, 0.31f }, EYE_R = { 0.104f, 0.044f, 0.31f };
	/** Цветок: центр над головой и расстояние лепестков от центра. */
	private static final float[] FLOWER_AT = { 0.044f, 0.523f, 0f }, TIP_AT = { 0.12f, 0.54f, 0f };

	private static final Voxels.Mesh BODY = RoundBody.MESH;
	private static final Voxels.Mesh SNOUT = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.089f, 0.079f, 0.085f } });
	private static final Voxels.Mesh EYE = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.099f, 0.084f, 0.085f } });
	private static final Voxels.Mesh EAR = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.05f, 0.095f, 0.03f } });
	private static final Voxels.Mesh BRAID = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.05f, 0.075f, 0.05f } });
	private static final Voxels.Mesh FLOWER = flower(0.06f), RIM = flower(0.073f);
	/** Рыжий кончик хвоста: изогнутая лента и два зубца. */
	private static final Voxels.Mesh TIP = Voxels.capsules(PIXEL / 2, 0.5f, new float[][] {
			{ 0, 0, 0, 0.09f, 0.03f, 0, 0.022f }, { 0.09f, 0.03f, 0, 0.14f, 0.075f, 0, 0.016f }, { 0.09f, 0.03f, 0, 0.15f, 0.015f, 0, 0.015f } });

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint SNOUT_PAINT = RoundBody.shifted(SNOUT_AT), EYE_L_PAINT = RoundBody.shifted(EYE_L), EYE_R_PAINT = RoundBody.shifted(EYE_R);
	private static final Voxels.Paint HAIR = Voxels.solid(88, 136), WHITE = Voxels.solid(72, 152), PINK = Voxels.solid(88, 152);

	/** Цветок из пяти лепестков-шаров вокруг сердцевины (плоский, смотрит вперёд). */
	private static Voxels.Mesh flower(float r) {
		float[][] segs = new float[6][];
		segs[0] = new float[] { 0, 0, 0, 0, 0, 0, r };
		for (int k = 0; k < 5; k++) {
			double a = Math.toRadians(90 + 72 * k);
			float x = (float) Math.cos(a) * 0.055f, y = (float) Math.sin(a) * 0.055f;
			segs[k + 1] = new float[] { x, y, 0, x, y, 0, r };
		}
		return Voxels.capsules(PIXEL / 2, 0.5f, segs);
	}

	@Override
	public float glassesY() {
		return 0.04f;
	}

	@Override
	public float glassesPush() {
		return 0.05f;
	}

	@Override
	public float centerY() {
		return BODY_Y;
	}

	@Override
	public void render(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float swing, float sway, float spread, CharacterPose pose, int light, int overlay) {
		Matrix4f body = new Matrix4f(head).translate(0, BODY_Y, 0);
		Voxels.draw(c, p, BODY, body, FACE, light, overlay);
		Voxels.draw(c, p, SNOUT, new Matrix4f(body).translate(SNOUT_AT[0], SNOUT_AT[1], SNOUT_AT[2]), SNOUT_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_L[0], EYE_L[1], EYE_L[2]), EYE_L_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_R[0], EYE_R[1], EYE_R[2]), EYE_R_PAINT, light, overlay);

		// ушки-лопухи свисают по бокам сверху, кончиками наружу
		Voxels.draw(c, p, EAR, new Matrix4f(body).translate(-0.335f, 0.19f, -0.02f).rotateZ((float) Math.toRadians(25)), HoofLimbs.BODY_PAINT, light, overlay);
		Voxels.draw(c, p, EAR, new Matrix4f(body).translate(0.335f, 0.19f, -0.02f).rotateZ((float) Math.toRadians(-25)), HoofLimbs.BODY_PAINT, light, overlay);

		// хвост-пучок на макушке: косичка, цветок с каймой и рыжий кончик
		Voxels.draw(c, p, BRAID, new Matrix4f(body).translate(-0.005f, 0.41f, 0), HAIR, light, overlay);
		Voxels.draw(c, p, RIM, new Matrix4f(body).translate(FLOWER_AT[0], FLOWER_AT[1], FLOWER_AT[2] - 0.02f), PINK, light, overlay);
		Voxels.draw(c, p, FLOWER, new Matrix4f(body).translate(FLOWER_AT[0], FLOWER_AT[1], FLOWER_AT[2] + 0.02f), WHITE, light, overlay);
		Voxels.draw(c, p, TIP, new Matrix4f(body).translate(TIP_AT[0], TIP_AT[1], TIP_AT[2]), HAIR, light, overlay);

		HoofLimbs.draw(c, p, head, feet, BODY_Y, 0.09f, swing, pose, light, overlay);
	}

	@Override
	public void renderHand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		HoofLimbs.hand(c, p, m, light, overlay);
	}
}
