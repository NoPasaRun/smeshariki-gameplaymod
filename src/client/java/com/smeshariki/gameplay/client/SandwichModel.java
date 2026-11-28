package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Бутерброд (моб, не смешарик; картинка {@code ~/Изображения/бутерброд.webp}): две половинки булки, между ними лист сыра с
 * загнутыми вверх боками и губы из сыра спереди, сверху спереди два глаза-оливки, усики из петрушки и крылья — листья салата по бокам. Размеры в блоках
 * без увеличения: начало — точка под серединой, перед — +z, ось x — влево от него.
 * <p>
 * Текстура 256×256 ({@code tools/sandwich_texture.py}): верх булки (0,0)–(128,128) и низ (128,0)–(256,128) — вид сверху, к краю
 * темнее (у верха кунжут); лист салата (0,128)–(64,192), черешок слева; глаз (64,128)–(96,160) — вид спереди; пятна 16×16:
 * оливка (96,128), петрушка (112,128), сыр (96,144), стебель петрушки (112,144), щель рта (96,160), губы (112,160).
 */
final class SandwichModel {
	/** Булка: полуоси верха и низа и высоты их центров; срез между ними — на высоте сыра. */
	private static final float BUN_RX = 0.38f, BUN_RZ = 0.34f, TOP_RY = 0.24f, TOP_Y = 0.30f, BOTTOM_RX = 0.36f, BOTTOM_RZ = 0.32f, BOTTOM_RY = 0.16f, BOTTOM_Y = 0.22f;
	/** Сыр: высота середины, полуразмеры, подъём боков и провис спереди. */
	private static final float CHEESE_Y = 0.25f, CHEESE_X = 0.46f, CHEESE_Z = 0.38f, CHEESE_CURL = 0.10f, CHEESE_SAG = 0.04f;
	/**
	 * Губы из сыра спереди между половинками: полуширина рта, провис середины и подъём уголков над ней (улыбка), толщина нижней и верхней
	 * губы в середине и у уголков, вынос вперёд и ширина дуги, по которой рот огибает бутерброд.
	 */
	private static final float LIP_HALF = 0.43f, LIP_DIP = 0.06f, LIP_SMILE = 0.17f, LIP_LOWER = 0.062f, LIP_UPPER = 0.026f, LIP_TIP = 0.012f, LIP_Z = 0.4f, LIP_WRAP = 0.5f;
	/** Глаза: радиус и центр правого (левый — зеркально по x). */
	private static final float EYE_R = 0.1f;
	private static final float[] EYE_AT = { 0.098f, 0.56f, 0.18f };
	/** Основание усика (левый — зеркально) и плечо крыла. */
	private static final float[] SPRIG_AT = { 0.13f, 0.50f, 0.06f }, WING_AT = { 0.34f, 0.25f, 0f };
	private static final float WING_LENGTH = 0.36f, WING_HALF = 0.14f;

	private static final Voxels.Mesh TOP = Voxels.Mesh.of(PIXEL / 2, 0.5f, BUN_RX, TOP_RY, BUN_RZ,
			(x, y, z) -> y >= -0.03f && sq(x / BUN_RX) + sq(y / TOP_RY) + sq(z / BUN_RZ) <= 1f);
	private static final Voxels.Mesh BOTTOM = Voxels.Mesh.of(PIXEL / 2, 0.5f, BOTTOM_RX, BOTTOM_RY, BOTTOM_RZ,
			(x, y, z) -> y <= 0.03f && sq(x / BOTTOM_RX) + sq(y / BOTTOM_RY) + sq(z / BOTTOM_RZ) <= 1f);
	private static final Voxels.Mesh CHEESE = Voxels.Mesh.of(PIXEL / 2, 0.5f, CHEESE_X, CHEESE_CURL + 0.06f, CHEESE_Z, (x, y, z) -> {
		if (sq(sq(x / CHEESE_X)) + sq(sq(z / CHEESE_Z)) > 1f) return false;
		float side = sq(x / CHEESE_X);
		float sheet = CHEESE_CURL * side - CHEESE_SAG * Math.max(z, 0f) / CHEESE_Z * (1f - side);
		return Math.abs(y - sheet) <= 0.02f;
	});
	/** Губы (верхняя и нижняя — сыр) и тёмная щель рта между ними; координаты — от середины сыра. */
	private static final Voxels.Mesh LIPS = lips(false), MOUTH = lips(true);
	private static final Voxels.Mesh EYE = Voxels.Mesh.ellipsoids(PIXEL / 2, 0.5f, new float[][] { { 0, 0, 0, EYE_R, EYE_R, EYE_R } });
	/** Усик: стебель дугой вверх и наружу (+x) и три листика на конце, свисающие вниз. */
	private static final Voxels.Mesh STEM_R = sprigStem(1), STEM_L = sprigStem(-1), LEAVES_R = sprigLeaves(1), LEAVES_L = sprigLeaves(-1);
	/** Крыло: плоский лист в плоскости xz, черешок в начале, растёт вбок (правое в +x, левое зеркально); край зубчатый. */
	private static final Voxels.Mesh WING_R = wing(1), WING_L = wing(-1);

	private static Voxels.Mesh wing(int s) {
		return Voxels.Mesh.of(PIXEL / 2, 0f, WING_LENGTH, PIXEL / 4, WING_HALF, (x, y, z) -> leaf(s * x, y, z));
	}

	private static boolean leaf(float x, float y, float z) {
		if (Math.abs(y) > PIXEL / 4 || x < 0 || x > WING_LENGTH) return false;
		float t = x / WING_LENGTH;
		float half = WING_HALF * (float) (Math.pow(t, 0.45) * Math.pow(1f - t, 0.4) * 1.75) * (0.88f + 0.12f * (float) Math.cos(t * 26f));
		return Math.abs(z) <= Math.max(half, t < 0.25f ? 0.02f : 0f);
	}

	private static final Voxels.Paint TOP_PAINT = (x, y, z, front, out) -> {
		out[0] = (64 + 62 * x / BUN_RX) / 256;
		out[1] = (64 - 62 * z / BUN_RZ) / 256;
	};
	private static final Voxels.Paint BOTTOM_PAINT = (x, y, z, front, out) -> {
		out[0] = (192 + 62 * x / BOTTOM_RX) / 256;
		out[1] = (64 - 62 * z / BOTTOM_RZ) / 256;
	};
	private static final Voxels.Paint WING_PAINT = (x, y, z, front, out) -> {
		out[0] = (1 + 62 * Math.abs(x) / WING_LENGTH) / 256;
		out[1] = (160 + 31 * z / WING_HALF) / 256;
	};
	/** Глаз: спереди — рисунок (правый и левый зеркальны: зрачки смотрят к середине), сзади — цвет оливки. */
	private static final Voxels.Paint EYE_R_PAINT = eye(1), EYE_L_PAINT = eye(-1);
	private static final Voxels.Paint OLIVE = Voxels.solid(104, 136), PARSLEY = Voxels.solid(120, 136), CHEESE_PAINT = Voxels.solid(104, 152), MOUTH_PAINT = Voxels.solid(104, 168), LIP_PAINT = Voxels.solid(120, 168), STEM = Voxels.solid(120, 152);

	private SandwichModel() {
	}

	private static float sq(float v) {
		return v * v;
	}

	private static Voxels.Paint eye(int side) {
		return (x, y, z, front, out) -> {
			if (z < 0) {
				OLIVE.uv(x, y, z, front, out);
				return;
			}
			out[0] = (80 + side * 15 * x / EYE_R) / 256;
			out[1] = (144 - 15 * y / EYE_R) / 256;
		};
	}

	/**
	 * Рот во всю ширину, как на картинке: линия рта — улыбка от одного острого уголка до другого (уголки задраны и торчат за булку),
	 * нижняя губа — толстый полумесяц под ней, сходящий к уголкам на нет, верхняя — тонкая кромка над ней. Идёт по переднему краю
	 * бутерброда. {@code seam} — щель рта вместо губ.
	 */
	private static Voxels.Mesh lips(boolean seam) {
		int n = 16;
		java.util.List<float[]> segs = new java.util.ArrayList<>();
		float[][] upper = new float[n + 1][], lower = new float[n + 1][], line = new float[n + 1][];
		for (int i = 0; i <= n; i++) {
			float t = 2f * i / n - 1f, x = LIP_HALF * t, full = 1f - t * t;
			float y = -LIP_DIP + LIP_SMILE * t * t, z = LIP_Z * (float) Math.sqrt(1f - sq(x / LIP_WRAP));
			float low = LIP_TIP + (LIP_LOWER - LIP_TIP) * full, up = LIP_TIP + (LIP_UPPER - LIP_TIP) * (float) Math.sqrt(full);
			line[i] = new float[] { x, y, z + 0.02f, 0.024f * (0.5f + 0.5f * full) };
			lower[i] = new float[] { x, y - low - 0.012f * full, z, low };
			upper[i] = new float[] { x, y + up + 0.012f * full, z - 0.01f, up };
		}
		for (float[][] row : seam ? new float[][][] { line } : new float[][][] { upper, lower }) {
			for (int i = 0; i < n; i++) {
				float[] a = row[i], b = row[i + 1];
				segs.add(new float[] { a[0], a[1], a[2], b[0], b[1], b[2], Math.max((a[3] + b[3]) / 2, 0.017f) });
			}
		}
		return Voxels.capsules(PIXEL / 2, 0.5f, segs.toArray(new float[0][]));
	}

	private static Voxels.Mesh sprigStem(int s) {
		float r = 0.02f;
		return Voxels.capsules(1f / 48, 0.5f, new float[][] { { 0, 0, 0, s * 0.03f, 0.13f, 0, r }, { s * 0.03f, 0.13f, 0, s * 0.10f, 0.22f, 0, r },
				{ s * 0.10f, 0.22f, 0, s * 0.19f, 0.25f, 0, r }, { s * 0.19f, 0.25f, 0, s * 0.27f, 0.21f, 0, r } });
	}

	private static Voxels.Mesh sprigLeaves(int s) {
		return Voxels.Mesh.ellipsoids(1f / 48, 0.5f, new float[][] { { s * 0.30f, 0.15f, 0, 0.05f, 0.075f, 0.022f }, { s * 0.22f, 0.15f, 0.01f, 0.04f, 0.06f, 0.022f },
				{ s * 0.36f, 0.20f, -0.01f, 0.05f, 0.045f, 0.022f }, { s * 0.33f, 0.26f, 0, 0.035f, 0.035f, 0.022f } });
	}

	/**
	 * @param root матрица всей модели (поворот, покачивание); {@code flap} — взмах крыльев −1..1; {@code sway} — качание усиков −1..1
	 */
	static void render(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float flap, float sway, int light, int overlay) {
		Voxels.draw(c, p, BOTTOM, new Matrix4f(root).translate(0, BOTTOM_Y, 0), BOTTOM_PAINT, light, overlay);
		Voxels.draw(c, p, CHEESE, new Matrix4f(root).translate(0, CHEESE_Y, 0), CHEESE_PAINT, light, overlay);
		Voxels.draw(c, p, LIPS, new Matrix4f(root).translate(0, CHEESE_Y, 0), LIP_PAINT, light, overlay);
		Voxels.draw(c, p, MOUTH, new Matrix4f(root).translate(0, CHEESE_Y, 0), MOUTH_PAINT, light, overlay);
		Voxels.draw(c, p, TOP, new Matrix4f(root).translate(0, TOP_Y, 0), TOP_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(root).translate(EYE_AT[0], EYE_AT[1], EYE_AT[2]), EYE_R_PAINT, light, overlay);
		Voxels.draw(c, p, EYE, new Matrix4f(root).translate(-EYE_AT[0], EYE_AT[1], EYE_AT[2]), EYE_L_PAINT, light, overlay);
		for (int s : new int[] { 1, -1 }) {
			Matrix4f sprig = new Matrix4f(root).translate(s * SPRIG_AT[0], SPRIG_AT[1], SPRIG_AT[2]).rotateZ((float) Math.toRadians(-s * 6f * sway)).rotateX((float) Math.toRadians(5f * sway));
			Voxels.draw(c, p, s > 0 ? STEM_R : STEM_L, sprig, STEM, light, overlay);
			Voxels.draw(c, p, s > 0 ? LEAVES_R : LEAVES_L, sprig, PARSLEY, light, overlay);
			// крыло растёт вбок и вниз, лист развёрнут плоскостью вперёд (как на картинке); машет вокруг плеча
			Matrix4f wing = new Matrix4f(root).translate(s * WING_AT[0], WING_AT[1], WING_AT[2])
					.rotateZ((float) Math.toRadians(s * (-22f + 26f * flap))).rotateX((float) Math.toRadians(-62f));
			Voxels.draw(c, p, s > 0 ? WING_R : WING_L, wing, WING_PAINT, light, overlay);
		}
	}
}
