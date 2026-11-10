package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Крош: шар-тело, длинные уши, ручки и ножки (общие с Ёжиком, см. {@link Limbs}) и белый цветок-хвост (серединка и пять лепестков). Текстура 256×256: лицо тела
 * (0,0)–(128,128) (круг тела занимает 0,8125 от половины квадрата, вокруг — цвет тела для выступающих углов кубов), спина
 * (128,0)–(256,128), градиент для рук и ног (0,128)–(64,192), цвет ушей — (64,128), белый — (80,128).
 */
final class KroshModel implements CharacterModel {
	private static final float BODY_R = RoundBody.R, BODY_Y = RoundBody.Y;
	/** Сколько блоков в половине квадрата лица в текстуре: круг тела занимает 0,8125 от неё. */
	private static final float FACE_HALF = RoundBody.FACE_HALF;

	private static final Voxels.Mesh BODY = RoundBody.MESH;
	private static final Voxels.Mesh EAR = Voxels.Mesh.ellipsoids(PIXEL, 0.5f, new float[][] { { 0, 0, 0, 0.125f, 0.36f, 0.065f } });
	private static final Voxels.Mesh FLOWER = flower();

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint EAR_PAINT = Voxels.solid(72, 136), WHITE = Voxels.solid(88, 136);

	/** Цветок втрое мельче основной сетки: круглая серединка и пять круглых лепестков по кругу, плоский (один слой кубов). */
	private static Voxels.Mesh flower() {
		float[][] e = new float[6][];
		e[0] = new float[] { 0, 0, 0, 0.045f, 0.045f, 0.01f };
		for (int i = 0; i < 5; i++) {
			double a = Math.PI / 2 + 2 * Math.PI * i / 5;
			e[i + 1] = new float[] { (float) (Math.cos(a) * 0.085), (float) (Math.sin(a) * 0.085), 0, 0.045f, 0.045f, 0.01f };
		}
		return Voxels.Mesh.ellipsoids(PIXEL / 3, 0f, e);
	}

	@Override
	public float centerY() {
		return BODY_Y;
	}

	@Override
	public void render(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float swing, float sway, float spread, CharacterPose pose, int light, int overlay) {
		Voxels.draw(c, p, BODY, new Matrix4f(head).translate(0, BODY_Y, 0), FACE, light, overlay);

		// уши: основание на макушке, наклон наружу и лёгкое покачивание
		ear(c, p, head, -0.09f, 10.5f, sway, light, overlay);
		ear(c, p, head, 0.084f, -12f, -sway, light, overlay);

		// ручки и ножки общие с Ёжиком
		Limbs.draw(c, p, head, feet, BODY_Y, swing, pose, light, overlay);

		// цветок-хвост на спине внизу: лежит на шаре и повёрнут по его нормали
		Voxels.draw(c, p, FLOWER, new Matrix4f(head).translate(0, BODY_Y - 0.2f, -0.325f).rotateX((float) Math.toRadians(148)), WHITE, light, overlay);
	}

	private static void ear(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float baseX, float tiltDeg, float sway, int light, int overlay) {
		Matrix4f m = new Matrix4f(root).translate(baseX, BODY_Y + BODY_R - 0.015f, 0).rotateZ((float) Math.toRadians(tiltDeg)).rotateX(sway).translate(0, 0.34f, 0);
		Voxels.draw(c, p, EAR, m, EAR_PAINT, light, overlay);
	}
}
