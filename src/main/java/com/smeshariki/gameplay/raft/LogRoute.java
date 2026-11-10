package com.smeshariki.gameplay.raft;

/**
 * Маршрут брёвен по одной реке: узлы средней линии с полушириной русла. Маршруты лежат в данных мира ({@link LogRoutes}), их пишет
 * {@code SmesharikiWorldMod/tools/log_route.py}; если русло поменялось, пересчитать. Плот течением вёл бы себя хуже: он не
 * поворачивается, застревает на поворотах и на обрыве и идёт одной линией, а реку шириной 13–17 блоков брёвна должны занимать всю.
 */
public final class LogRoute {
	/** Запас от оси бревна до берега в крайней точке качания: половина бревна (до 2,8 по диагонали) и зазор. Размах — полуширина минус запас, на широкой реке он больше. */
	public static final double MARGIN = 3.0;
	/** На падении высота считается вдвое короче: по воде бревно идёт 10 блоков в секунду, падает 20. */
	private static final double FALL_WEIGHT = 0.5;

	/** Узлы: x, y (основание плота), z, полуширина русла поперёк линии. */
	private final float[] nodes;
	private final int count;
	private final double[] distance, normalX, normalZ;

	public LogRoute(float[] nodes) {
		this.nodes = nodes.clone();
		this.count = nodes.length / 4;
		distance = new double[count];
		normalX = new double[count];
		normalZ = new double[count];
		for (int i = 1; i < count; i++) {
			double dx = x(i) - x(i - 1), dy = (y(i) - y(i - 1)) * FALL_WEIGHT, dz = z(i) - z(i - 1);
			distance[i] = distance[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
		for (int i = 0; i < count; i++) {
			int a = Math.max(0, i - 1), b = Math.min(count - 1, i + 1);
			double dx = x(b) - x(a), dz = z(b) - z(a), len = Math.max(1.0e-6, Math.hypot(dx, dz));
			normalX[i] = -dz / len;
			normalZ[i] = dx / len;
		}
	}

	public float[] nodesArray() {
		return nodes.clone();
	}

	double length() {
		return count == 0 ? 0 : distance[count - 1];
	}

	int nodes() {
		return count;
	}

	double x(int i) {
		return nodes[i * 4];
	}

	double y(int i) {
		return nodes[i * 4 + 1];
	}

	double z(int i) {
		return nodes[i * 4 + 2];
	}

	private double halfWidth(int i) {
		return nodes[i * 4 + 3];
	}

	/**
	 * Точка маршрута: {@code out} получает x, y, z центра основания.
	 *
	 * @param s    пройденный путь, блоков
	 * @param lane  полоса от −1 (левый берег по течению) до 1 (правый)
	 * @param shift сдвиг поперёк, блоков (плюс — к правому берегу)
	 */
	void at(double s, double lane, double shift, double[] out) {
		int lo = 0, hi = count - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (distance[mid] <= s) lo = mid;
			else hi = mid;
		}
		double t = Math.clamp((s - distance[lo]) / Math.max(1.0e-6, distance[hi] - distance[lo]), 0.0, 1.0);
		double span = Math.max(0.0, lerp(halfWidth(lo), halfWidth(hi), t) - MARGIN);
		// сдвиг не подводит ось ближе MARGIN к берегу: в узких местах его нет
		double side = Math.clamp(lane * span + shift, -span, span);
		out[0] = lerp(x(lo), x(hi), t) + lerp(normalX[lo], normalX[hi], t) * side;
		out[1] = lerp(y(lo), y(hi), t);
		out[2] = lerp(z(lo), z(hi), t) + lerp(normalZ[lo], normalZ[hi], t) * side;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}
}
