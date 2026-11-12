package com.smeshariki.gameplay.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;
import com.smeshariki.gameplay.character.CharacterPose.Part;

/**
 * Общие ручки и ножки Кроша и Ёжика (как были у Кроша): ручки-капсулы, отведённые в стороны, и плоские стопы с круглым носком. Красятся
 * вертикальным градиентом текстуры (0,128)–(64,192) цветом тела персонажа. Тело должно стоять на высоте 0,5 над землёй.
 */
final class Limbs {
	private static final float ARM_HALF = 0.17f, ARM_PIVOT = 0.15f, FOOT_HALF = 0.084f;
	private static final Voxels.Mesh ARM = Voxels.Mesh.ellipsoids(Voxels.PIXEL, 0.5f, new float[][] { { 0, 0, 0, 0.075f, ARM_HALF, 0.075f } });
	/** Стопа вытянута вперёд и закруглена на конце: пятка и круглый носок. */
	private static final Voxels.Mesh FOOT = Voxels.Mesh.ellipsoids(Voxels.PIXEL / 2, 0.5f, new float[][] { { 0, 0, -0.03f, 0.105f, FOOT_HALF, 0.13f }, { 0, -0.01f, 0.1f, 0.095f, 0.07f, 0.115f } });
	private static final Voxels.Paint ARM_PAINT = Voxels.gradient(ARM_HALF), FOOT_PAINT = Voxels.gradient(FOOT_HALF);
	/** Ручки: расстояние от оси, наклон наружу (градусы) и плечо ниже центра тела; стопы: расстояние от оси и вынос носка вперёд. */
	private static final float ARM_X = 0.43f, ARM_TILT = 8f, SHOULDER_BELOW = 0.2f, FOOT_X = 0.155f, FOOT_FORWARD = 0.05f;

	private Limbs() {
	}

	/**
	 * Матрица центра ручки (крыла, копыта-руки) для любого персонажа: плечо у всех на одном месте. {@code side} −1 слева (x &lt; 0), 1
	 * справа; {@code tiltDeg} — наклон нижнего конца наружу/внутрь (по умолчанию {@link #tilt}), {@code pivot} — на сколько выше центра
	 * плечо (качание идёт вокруг него). {@code pose} (может быть null) добавляет поворот вокруг плеча.
	 */
	static Matrix4f arm(Matrix4f root, float bodyY, int side, float tiltDeg, float swing, float pivot, CharacterPose pose) {
		if (side == attackSide && attack > 0f) swing -= raise(attack);
		Quaternionf q = pose == null ? null : pose.get(side < 0 ? Part.ARM_RIGHT : Part.ARM_LEFT);
		if (q != null && pose.absolute) {
			tiltDeg = 0;
			swing = 0;
		}
		Matrix4f m = new Matrix4f(root).translate(side * ARM_X, bodyY - SHOULDER_BELOW + pivot, 0);
		if (q != null) m.rotate(q);
		return m.rotateX(swing).rotateZ((float) Math.toRadians(tiltDeg)).translate(0, -pivot, 0);
	}

	/** Взмах рукой (бьёт рука стороны {@code attackSide}, ход {@code attack} 0..1): ставится рендерером на время отрисовки одного персонажа, только в потоке отрисовки. */
	private static float attack;
	private static int attackSide;

	static void setAttack(float progress, int side) {
		attack = progress;
		attackSide = side;
	}

	/** Насколько рука поднимается вперёд при взмахе (радианы): как у ванильной руки при ударе. */
	private static float raise(float progress) {
		float f = 1f - progress;
		f *= f;
		f *= f;
		f = 1f - f;
		return (float) (Math.sin(f * Math.PI) * 1.2 + Math.sin(progress * Math.PI) * 0.525);
	}

	/** Высота бедра (оси вращения ноги) — у основания тела. */
	static float hip(float bodyY) {
		return bodyY - RoundBody.R + 0.08f;
	}

	/**
	 * Матрица ноги на земле под бедром: точка {@code (x, 0, stepZ)}; поворот ноги из позы идёт вокруг бедра на высоте {@code hipY}.
	 * Шаг {@code stepZ} при абсолютной позе отключается.
	 */
	static Matrix4f leg(Matrix4f root, float x, float hipY, float stepZ, CharacterPose pose) {
		Quaternionf q = pose == null ? null : pose.get(x < 0 ? Part.LEG_RIGHT : Part.LEG_LEFT);
		if (q != null && pose.absolute) stepZ = 0;
		Matrix4f m = new Matrix4f(root).translate(x, hipY, 0);
		if (q != null) m.rotate(q);
		return m.translate(0, -hipY, stepZ);
	}

	/** Наклон ручки по умолчанию для стороны {@code side}. */
	static float tilt(int side) {
		return -side * ARM_TILT;
	}

	/** Рисует обе ручки (от корпуса {@code head}, плечо ниже центра тела {@code bodyY}) и обе стопы (от {@code feet}), качая их при ходьбе. */
	static void draw(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float bodyY, float swing, CharacterPose pose, int light, int overlay) {
		arm(c, p, head, bodyY, -1, -swing * 0.6f, pose, light, overlay);
		arm(c, p, head, bodyY, 1, swing * 0.6f, pose, light, overlay);
		foot(c, p, feet, bodyY, -FOOT_X, swing * 0.18f, pose, light, overlay);
		foot(c, p, feet, bodyY, FOOT_X, -swing * 0.18f, pose, light, overlay);
	}

	/** Общая ручка без поворотов модели, для вида от первого лица. */
	static void hand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		Voxels.draw(c, p, ARM, m, ARM_PAINT, light, overlay);
	}

	/** Ручка качается вокруг плеча в противофазе с ножками. */
	private static void arm(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float bodyY, int side, float swing, CharacterPose pose, int light, int overlay) {
		Matrix4f m = arm(root, bodyY, side, tilt(side), swing, ARM_PIVOT, pose);
		Voxels.draw(c, p, ARM, m, ARM_PAINT, light, overlay);
	}

	private static void foot(VertexConsumer c, PoseStack.Pose p, Matrix4f root, float bodyY, float x, float stepZ, CharacterPose pose, int light, int overlay) {
		Voxels.draw(c, p, FOOT, new Matrix4f(leg(root, x, hip(bodyY), stepZ, pose)).translate(0, FOOT_HALF, FOOT_FORWARD), FOOT_PAINT, light, overlay);
	}
}
