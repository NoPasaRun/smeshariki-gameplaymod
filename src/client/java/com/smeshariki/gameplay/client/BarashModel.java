package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Бараш: сиреневый шар-тело, большие глаза, два закрученных тёмно-фиолетовых рога, свисающие ушки; руки и ноги с копытами общие с Нюшей и
 * Лосяшем ({@link HoofLimbs}). Размеры сняты с картинки (0,00481 блока на пиксель, радиус шара 78 пикселей). Текстура 256×256: лицо (0,0)–(128,128) —
 * кусок картинки (глаза — отдельные тела, красятся той же проекцией лица, поэтому выпирают), спина (128,0) — сиреневая с контуром, градиент
 * рук (0,128)–(64,192), метки: копыто (72,136), рог (88,136).
 */
final class BarashModel implements CharacterModel {
	private static final float BODY_Y = RoundBody.Y + 0.07f;
	private static final float[] EYE_L = { -0.105f, 0.077f, 0.31f }, EYE_R = { 0.105f, 0.077f, 0.31f };
	/** Центр завитка левого рога относительно центра тела (z — глубина плоскости рога). */
	private static final float HORN_X = 0.2115f, HORN_Y = 0.2837f, HORN_Z = 0.05f;

	private static final Voxels.Mesh BODY = RoundBody.MESH;
	private static final Voxels.Mesh EYE = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.105f, 0.10f, 0.085f } });
	private static final Voxels.Mesh EAR = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, 0.05f, 0.09f, 0.035f } });
	private static final Voxels.Mesh HORN_L = horn(-1), HORN_R = horn(1);

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint EYE_L_PAINT = RoundBody.shifted(EYE_L), EYE_R_PAINT = RoundBody.shifted(EYE_R);
	private static final Voxels.Paint HORN = Voxels.solid(88, 136);

	/** Рог бараньей спиралью: из макушки наружу, вверх, вокруг и внутрь, трубка к концу тоньше; в плоскости лица. Начало — центр завитка. */
	private static Voxels.Mesh horn(int side) {
		int n = 30;
		float s = 0.375f / 78f;
		float[][] segs = new float[n][];
		float[] prev = null;
		for (int i = 0; i <= n; i++) {
			float t = (float) i / n;
			double a = Math.toRadians(-20 - 540 * t);
			float r = 27 - 21 * t, tube = (9 - 4 * t) * s;
			float x = (float) Math.cos(a) * r * s, y = -(float) Math.sin(a) * r * s;
			x *= side * -1;
			float[] cur = { x, y, tube };
			if (prev != null) segs[i - 1] = new float[] { prev[0], prev[1], 0, cur[0], cur[1], 0, prev[2] };
			prev = cur;
		}
		return Voxels.capsules(PIXEL / 2, 0.5f, segs);
	}

	@Override
	public float glassesY() {
		return 0.075f;
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
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_L[0], EYE_L[1], EYE_L[2]), EYE_L_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(body).translate(EYE_R[0], EYE_R[1], EYE_R[2]), EYE_R_PAINT, light, overlay);

		Voxels.draw(c, p, HORN_L, new Matrix4f(body).translate(-HORN_X, HORN_Y, HORN_Z), HORN, light, overlay);
		Voxels.draw(c, p, HORN_R, new Matrix4f(body).translate(HORN_X, HORN_Y, HORN_Z), HORN, light, overlay);

		// ушки свисают по бокам, кончиками вниз и наружу
		Voxels.draw(c, p, EAR, new Matrix4f(body).translate(-0.34f, 0.125f, -0.02f).rotateZ((float) Math.toRadians(-20)), HoofLimbs.BODY_PAINT, light, overlay);
		Voxels.draw(c, p, EAR, new Matrix4f(body).translate(0.34f, 0.125f, -0.02f).rotateZ((float) Math.toRadians(20)), HoofLimbs.BODY_PAINT, light, overlay);

		HoofLimbs.draw(c, p, head, feet, BODY_Y, 0.085f, swing, pose, light, overlay);
	}

	@Override
	public void renderHand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		HoofLimbs.hand(c, p, m, light, overlay);
	}
}
