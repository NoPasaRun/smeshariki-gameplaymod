package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.Mth;

/**
 * Воксельные тела «из кубиков как в майне»: набор граней по форме (эллипсоиды или любая фигура), раскраска по текстуре
 * 256×256 и отрисовка. Общие для всех персонажей.
 */
final class Voxels {
	/** Размер кубика основной сетки — пиксель блока. */
	static final float PIXEL = 1f / 16;

	private Voxels() {
	}

	/** Грань куба: четыре вершины (x, y, z) и нормаль. */
	record Face(float[] corners, float nx, float ny, float nz) {
	}

	interface Shape {
		boolean contains(float x, float y, float z);
	}

	/** Набор граней воксельного тела; создаётся один раз. */
	record Mesh(List<Face> faces) {
		/**
		 * Куб размером {@code v} входит в тело, если центр его клетки внутри фигуры. Тело лежит в пределах ±hx, ±hy, ±hz от
		 * начала. Сетка клеток {@code offset} (0,5 — клетки симметричны относительно начала, 0 — центральная клетка в начале).
		 * Видны только грани без соседа.
		 */
		static Mesh of(float v, float offset, float hx, float hy, float hz, Shape shape) {
			int nx = (int) Math.ceil(hx / v) + 2, ny = (int) Math.ceil(hy / v) + 2, nz = (int) Math.ceil(hz / v) + 2;
			int sx = 2 * nx + 1, sy = 2 * ny + 1, sz = 2 * nz + 1;
			boolean[] in = new boolean[sx * sy * sz];
			for (int i = -nx; i <= nx; i++) {
				for (int j = -ny; j <= ny; j++) {
					for (int k = -nz; k <= nz; k++) {
						in[((i + nx) * sy + (j + ny)) * sz + (k + nz)] = shape.contains((i + offset) * v, (j + offset) * v, (k + offset) * v);
					}
				}
			}
			List<Face> faces = new ArrayList<>();
			int[][] dirs = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
			int[][] around = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };
			for (int i = -nx; i <= nx; i++) {
				for (int j = -ny; j <= ny; j++) {
					for (int k = -nz; k <= nz; k++) {
						if (!in[((i + nx) * sy + (j + ny)) * sz + (k + nz)]) continue;
						for (int[] d : dirs) {
							int a = i + d[0], b = j + d[1], c = k + d[2];
							boolean inside = a >= -nx && a <= nx && b >= -ny && b <= ny && c >= -nz && c <= nz && in[((a + nx) * sy + (b + ny)) * sz + (c + nz)];
							if (inside) continue;
							float[] center = { (i + offset) * v, (j + offset) * v, (k + offset) * v };
							int axis = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;
							float[] q = new float[12];
							for (int m = 0; m < 4; m++) {
								float[] p = center.clone();
								p[axis] += d[axis] * v / 2;
								p[(axis + 1) % 3] += around[m][0] * v / 2;
								p[(axis + 2) % 3] += around[m][1] * v / 2;
								System.arraycopy(p, 0, q, m * 3, 3);
							}
							faces.add(new Face(q, d[0], d[1], d[2]));
						}
					}
				}
			}
			return new Mesh(faces);
		}

		/** Объединение эллипсоидов: у каждого центр (cx, cy, cz) и радиусы (rx, ry, rz). */
		static Mesh ellipsoids(float v, float offset, float[][] ells) {
			float hx = 0, hy = 0, hz = 0;
			for (float[] e : ells) {
				hx = Math.max(hx, Math.abs(e[0]) + e[3]);
				hy = Math.max(hy, Math.abs(e[1]) + e[4]);
				hz = Math.max(hz, Math.abs(e[2]) + e[5]);
			}
			return of(v, offset, hx, hy, hz, (x, y, z) -> {
				for (float[] e : ells) {
					float dx = (x - e[0]) / e[3], dy = (y - e[1]) / e[4], dz = (z - e[2]) / e[5];
					if (dx * dx + dy * dy + dz * dz <= 1f) return true;
				}
				return false;
			});
		}
	}

	/** Текстурные координаты вершины; {@code front} — передняя половина тела (для лица и спины). */
	interface Paint {
		void uv(float x, float y, float z, boolean front, float[] out);
	}

	/** Лицо на передней половине тела и спина на задней: плоская проекция, {@code half} — сколько блоков в половине квадрата. */
	static Paint face(float half) {
		return (x, y, z, front, out) -> {
			out[0] = front ? (64 + 64 * x / half) / 256 : (192 - 64 * x / half) / 256;
			out[1] = (64 - 64 * y / half) / 256;
		};
	}

	/** Вертикальный градиент (светлый сверху, тёмный снизу) в области (0,128)–(64,192). */
	static Paint gradient(float halfY) {
		return (x, y, z, front, out) -> {
			out[0] = 32f / 256;
			out[1] = (160 - 30 * Mth.clamp(y / halfY, -1f, 1f)) / 256;
		};
	}

	/** Сплошной цвет: пиксель (px, py) текстуры. */
	static Paint solid(float px, float py) {
		return (x, y, z, front, out) -> {
			out[0] = px / 256;
			out[1] = py / 256;
		};
	}

	/** Рисует тело: преобразование {@code m} включает сдвиг и поворот. */
	static void draw(VertexConsumer c, PoseStack.Pose p, Mesh mesh, Matrix4f m, Paint paint, int light, int overlay) {
		Matrix3f normalMatrix = m.normal(new Matrix3f());
		Vector3f pos = new Vector3f(), nrm = new Vector3f();
		float[] uv = new float[2];
		for (Face f : mesh.faces()) {
			float[] q = f.corners();
			normalMatrix.transform(f.nx(), f.ny(), f.nz(), nrm).normalize();
			boolean front = (q[2] + q[5] + q[8] + q[11]) >= 0;
			for (int k = 0; k < 4; k++) {
				paint.uv(q[k * 3], q[k * 3 + 1], q[k * 3 + 2], front, uv);
				m.transformPosition(q[k * 3], q[k * 3 + 1], q[k * 3 + 2], pos);
				c.addVertex(p, pos.x, pos.y, pos.z).setColor(255, 255, 255, 255).setUv(uv[0], uv[1]).setOverlay(overlay).setLight(light)
						.setNormal(p, nrm.x, nrm.y, nrm.z);
			}
		}
	}
}
