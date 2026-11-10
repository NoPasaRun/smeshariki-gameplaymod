package com.smeshariki.gameplay.client;

import static com.smeshariki.gameplay.client.Voxels.PIXEL;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/**
 * Ёжик: розовый шар-тело с очками на лице, фиолетовые колючки-конусы на спине, боках и макушке, маленькие ушки, ручки и
 * длинные ножки. Очки, брови, нос и рот нарисованы на лице. Размеры сняты с картинки (0,00123 блока на пиксель).
 * <p>
 * Текстура 256×256, как у Кроша: лицо (0,0)–(128,128), спина (128,0)–(256,128), градиент для рук и ног (0,128)–(64,192),
 * цвет кожи — (64,128), цвет колючек — (80,128).
 */
final class YozhikModel implements CharacterModel {
	private static final float BODY_R = RoundBody.R, BODY_Y = RoundBody.Y;
	private static final float FACE_HALF = RoundBody.FACE_HALF;
	private static final Voxels.Mesh BODY = RoundBody.MESH;
	private static final Voxels.Mesh EAR = Voxels.Mesh.ellipsoids(PIXEL, 0.5f, new float[][] { { 0, 0, 0, 0.07f, 0.07f, 0.07f } });
	private static final Voxels.Mesh SPIKES = spikes();

	private static final Voxels.Paint FACE = RoundBody.FACE;
	private static final Voxels.Paint SKIN = Voxels.solid(72, 136), SPIKE_PAINT = Voxels.solid(88, 136);

	/** Колючки растут на спине, боках и макушке: направления вне этой области (z спереди, y снизу) не покрываются. */
	private static final float MASK_Z = 0.2f, MASK_Y = -0.5f;
	/** Число точек на всей сфере (колючки берутся из тех, что попали в область), толщина сплошного слоя у тела, высота колючки над слоем, радиус её основания. */
	private static final int SPIKE_POINTS = 64;
	private static final float SHELL = 0.06f, SPIKE_LEN = 0.17f, SPIKE_BASE = 0.085f;

	/**
	 * Колючки-конусы: сплошной слой у самого тела (между колючками пусто не бывает, всё держится за тело), а из него
	 * торчат конусы по равномерно разбросанным направлениям (спираль Фибоначчи). Колючки мельче кубиков тела: их сетка вдвое
	 * мельче (1/32), иначе такие конусы превращаются в шум. Клетки, задевающие кубики тела, в колючки не входят, поэтому
	 * грани тела и колючек не накладываются.
	 */
	private static Voxels.Mesh spikes() {
		double golden = Math.PI * (3 - Math.sqrt(5));
		List<float[]> dirs = new ArrayList<>();
		for (int k = 0; k < SPIKE_POINTS; k++) {
			double y = 1 - 2 * (k + 0.5) / SPIKE_POINTS, r = Math.sqrt(1 - y * y), phi = k * golden;
			float[] d = { (float) (Math.cos(phi) * r), (float) y, (float) (Math.sin(phi) * r) };
			// чуть шире области, чтобы у её края конусы были целыми
			if (d[2] <= MASK_Z + 0.35f && d[1] >= MASK_Y - 0.35f) dirs.add(d);
		}
		float reach = BODY_R + SHELL + SPIKE_LEN + 0.05f;
		return Voxels.Mesh.of(PIXEL / 2, 0.5f, reach, reach, reach, (x, y, z) -> {
			// кубик тела (сетка 1/16), в который попадает эта клетка, — если он в теле, клетка занята
			float bx = ((float) Math.floor(x / PIXEL) + 0.5f) * PIXEL / BODY_R, by = ((float) Math.floor(y / PIXEL) + 0.5f) * PIXEL / BODY_R,
					bz = ((float) Math.floor(z / PIXEL) + 0.5f) * PIXEL / BODY_R;
			if (bx * bx + by * by + bz * bz <= 1f) return false;
			float r = (float) Math.sqrt(x * x + y * y + z * z);
			if (r - BODY_R > SHELL + SPIKE_LEN) return false;
			float dx = x / r, dy = y / r, dz = z / r;
			if (dz > MASK_Z || dy < MASK_Y) return false;
			float height = SHELL;
			for (float[] d : dirs) {
				double angle = Math.acos(Math.max(-1f, Math.min(1f, dx * d[0] + dy * d[1] + dz * d[2])));
				height = Math.max(height, SHELL + SPIKE_LEN * (float) (1 - BODY_R * angle / SPIKE_BASE));
			}
			return r - BODY_R <= height;
		});
	}

	@Override
	public float centerY() {
		return BODY_Y;
	}

	@Override
	public void render(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float swing, float sway, float spread, CharacterPose pose, int light, int overlay) {
		Voxels.draw(c, p, BODY, new Matrix4f(head).translate(0, BODY_Y, 0), FACE, light, overlay);
		// колючки-конусы на спине, боках и макушке
		Voxels.draw(c, p, SPIKES, new Matrix4f(head).translate(0, BODY_Y, 0), SPIKE_PAINT, light, overlay);

		// ушки по бокам макушки
		for (int side : new int[] { -1, 1 }) {
			Voxels.draw(c, p, EAR, new Matrix4f(head).translate(side * 0.35f, BODY_Y + 0.17f, 0), SKIN, light, overlay);
		}

		// ручки и ножки общие с Крошем
		Limbs.draw(c, p, head, feet, BODY_Y, swing, pose, light, overlay);
	}
}
