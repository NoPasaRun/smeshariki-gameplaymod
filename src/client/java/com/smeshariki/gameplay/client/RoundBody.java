package com.smeshariki.gameplay.client;

/**
 * Общее круглое тело всех персонажей-шариков: один размер (радиус 6/16 блока), одна высота центра над землёй, воксельная
 * сетка 1/16 и лицо плоской проекцией на переднюю половину. Модели рисуют его и добавляют своё (уши, клюв, рога, колючки…).
 */
final class RoundBody {
	/** Радиус шара и высота его центра над ногами. */
	static final float R = 6 * Voxels.PIXEL, Y = 0.5f;
	/** Сколько блоков в половине квадрата лица в текстуре: круг тела занимает 0,8125 от неё. */
	static final float FACE_HALF = R / 0.8125f;
	static final Voxels.Mesh MESH = Voxels.Mesh.ellipsoids(Voxels.PIXEL, 0.5f, new float[][] { { 0, 0, 0, R, R, R } });
	static final Voxels.Paint FACE = Voxels.face(FACE_HALF);

	private RoundBody() {
	}
}
