package com.smeshariki.gameplay.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.character.CharacterPose;

/** Внешность персонажа: из каких воксельных частей он состоит и как они двигаются. */
interface CharacterModel {
	/**
	 * @param head матрица корпуса (поворот к голове, падение при смерти); {@code feet} — то же с поворотом тела для ног;
	 *             {@code swing} — фаза ходьбы (−1..1); {@code sway} — лёгкое покачивание; {@code spread} — раскрытость
	 *             крыльев при планировании (0..1); {@code pose} — визуальная поза (может быть null), повороты рук/крыльев и ног применяет сама модель
	 */
	void render(VertexConsumer c, PoseStack.Pose p, Matrix4f head, Matrix4f feet, float swing, float sway, float spread, CharacterPose pose, int light, int overlay);

	/** Рука от первого лица: матрица {@code m} ставит её центр в начало, плечо сверху, кисть снизу (как у модели). По умолчанию общая ручка. */
	default void renderHand(VertexConsumer c, PoseStack.Pose p, Matrix4f m, int light, int overlay) {
		Limbs.hand(c, p, m, light, overlay);
	}

	/** Высота центра тела над ногами: вокруг неё персонаж катится клубком, от неё же отсчитаны места одежды. */
	default float centerY() {
		return 0.6f;
	}

	/** Высота глаз над центром тела и насколько вперёд выпирает лицо (клюв, веки, нос) — для очков. */
	default float glassesY() {
		return 0.08f;
	}

	default float glassesPush() {
		return 0f;
	}

	/** Во сколько раз модель увеличена: у всех одинаково, круглое тело ({@link RoundBody}) выходит размером 0,52 блока в радиусе. */
	float BODY_SCALE = 1.8f / 1.3f;

	default float scale() {
		return BODY_SCALE;
	}

	/** Радиус шара-тела (у всех персонажей 6/16 блока). */
	default float radius() {
		return 0.375f;
	}
}
