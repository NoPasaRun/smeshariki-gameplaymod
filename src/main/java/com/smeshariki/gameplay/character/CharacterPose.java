package com.smeshariki.gameplay.character;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Визуальная поза персонажа-шарика: произвольные повороты частей модели (для катсцен и других модов). На хитбокс, физику и
 * ИИ не влияет — меняется только то, что рисуется. Ставится на клиенте: {@link CharacterEntity#setVisualPose}.
 * <p>
 * Оси модели: X — влево персонажа (если смотреть ему в спину, то вправо), Y — вверх, Z — вперёд (куда смотрит лицо). Повороты
 * по правилу правой руки, положительный угол вокруг X наклоняет верх вперёд (рука, висящая вниз, уходит назад), вокруг Y —
 * поворачивает влево, вокруг Z — наклоняет верх вправо. Сторона {@code LEFT} — левая рука/нога самого персонажа (у неё x &gt; 0).
 * <p>
 * Оси вращения (точки): {@link Part#ROOT} и {@link Part#BODY} — центр круглого тела; руки (крылья) — плечи; ноги — бёдра (у
 * основания тела). Корень поворачивает всю модель целиком; тело — только само тело и всё, что на нём (лицо, уши, рога, колючки,
 * очки, а также руки, растущие из плеч); руки и ноги — каждая вокруг своей точки поверх тела.
 * <p>
 * По умолчанию поворот прибавляется к обычной позе (лёгкий наклон рук, качание при ходьбе и планировании). Если
 * {@link #absolute} включён, у частей с заданным поворотом обычная поза отключается и остаётся только он.
 */
public final class CharacterPose {
	public enum Part {
		ROOT, BODY, ARM_LEFT, ARM_RIGHT, LEG_LEFT, LEG_RIGHT
	}

	/** Отключать ли обычную позу (наклон, качание) у частей, которым задан поворот. */
	public boolean absolute;
	private final Quaternionf[] rotations = new Quaternionf[Part.values().length];

	/** Задаёт поворот части (копия кватерниона). */
	public CharacterPose set(Part part, Quaternionfc rotation) {
		this.rotations[part.ordinal()] = new Quaternionf(rotation);
		return this;
	}

	/** Задаёт поворот углами в градусах: сначала вокруг Z, затем X, затем Y (порядок Y·X·Z, как у камеры в игре). */
	public CharacterPose setEuler(Part part, float xDeg, float yDeg, float zDeg) {
		this.rotations[part.ordinal()] = new Quaternionf().rotationYXZ((float) Math.toRadians(yDeg), (float) Math.toRadians(xDeg), (float) Math.toRadians(zDeg));
		return this;
	}

	/** Поворот части или {@code null}, если не задан. */
	public Quaternionf get(Part part) {
		return this.rotations[part.ordinal()];
	}

	/** Снимает поворот части. */
	public CharacterPose clear(Part part) {
		this.rotations[part.ordinal()] = null;
		return this;
	}

	public CharacterPose clear() {
		java.util.Arrays.fill(this.rotations, null);
		return this;
	}

	public CharacterPose copy() {
		CharacterPose copy = new CharacterPose();
		copy.absolute = this.absolute;
		for (int i = 0; i < this.rotations.length; i++) {
			if (this.rotations[i] != null) copy.rotations[i] = new Quaternionf(this.rotations[i]);
		}
		return copy;
	}

	/** Плавный переход между позами (сферическая интерполяция); часть без поворота считается неподвижной. {@code absolute} берётся у {@code to}. */
	public static CharacterPose lerp(CharacterPose from, CharacterPose to, float t) {
		CharacterPose result = new CharacterPose();
		result.absolute = to.absolute;
		for (Part part : Part.values()) {
			Quaternionf a = from == null ? null : from.get(part), b = to.get(part);
			if (a == null && b == null) continue;
			result.rotations[part.ordinal()] = new Quaternionf(a == null ? new Quaternionf() : a).slerp(b == null ? new Quaternionf() : b, t);
		}
		return result;
	}
}
