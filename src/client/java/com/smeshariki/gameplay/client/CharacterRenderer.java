package com.smeshariki.gameplay.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.CharacterPose;
import com.smeshariki.gameplay.character.CharacterPose.Part;
import com.smeshariki.gameplay.character.Characters;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;

/** Рисует персонажа-шарика по его модели (Крош, Ёжик, Карыч, Лосяш, Нюша, Бараш, Совунья): общее здесь — поворот, ходьба, удар, смерть и скрытие изнутри. */
public final class CharacterRenderer extends EntityRenderer<CharacterEntity, CharacterRenderer.State> {
	private final CharacterModel model;
	private final Identifier texture;

	/** Руки от первого лица: место на экране, поворот внутрь и вверх (градусы), размер. */
	private static final float HAND_X = 0.62f, HAND_Y = -0.5f, HAND_Z = -0.66f, HAND_IN = 15f, HAND_UP = 105f, HAND_SCALE = 1.6f;

	/** Превью в окне инвентаря: поворот тела и головы (градусы) задаёт окно на время отрисовки. */
	private static boolean preview;
	private static float previewBody, previewHead;

	/** Включает поворот для превью персонажа (вызывать парой с {@link #endPreview()}). */
	static void beginPreview(float body, float head) {
		preview = true;
		previewBody = body;
		previewHead = head;
	}

	static void endPreview() {
		preview = false;
	}

	public static void init() {
		EntityRendererRegistry.register(Characters.KROSH, ctx -> new CharacterRenderer(ctx, new KroshModel(), "krosh"));
		EntityRendererRegistry.register(Characters.YOZHIK, ctx -> new CharacterRenderer(ctx, new YozhikModel(), "yozhik"));
		EntityRendererRegistry.register(Characters.KARYCH, ctx -> new CharacterRenderer(ctx, new KarychModel(), "karych"));
		EntityRendererRegistry.register(Characters.LOSYASH, ctx -> new CharacterRenderer(ctx, new LosyashModel(), "losyash"));
		EntityRendererRegistry.register(Characters.NYUSHA, ctx -> new CharacterRenderer(ctx, new NyushaModel(), "nyusha"));
		EntityRendererRegistry.register(Characters.BARASH, ctx -> new CharacterRenderer(ctx, new BarashModel(), "barash"));
		EntityRendererRegistry.register(Characters.SOVUNYA, ctx -> new CharacterRenderer(ctx, new SovunyaModel(), "sovunya"));
	}

	public static final class State extends EntityRenderState {
		float headYaw, bodyYaw, walkPos, walkSpeed, death, spread, slopeDrop;
		/** Взмах рукой того, кто ведёт персонажа: ход 0..1 и рука (сторона модели: −1 — правая у персонажа). */
		float attack;
		int attackSide;
		/** Лежит на скате: наклон туловища, иначе NaN. */
		float lying = Float.NaN;
		boolean hurt, hidden;
		/** Надеты очки. */
		boolean glasses;
		/** Визуальная поза (копия; null — нет). */
		CharacterPose pose;
		/** Поворот клубка (null, если не катится). */
		Quaternionf roll;
	}

	private CharacterRenderer(EntityRendererProvider.Context context, CharacterModel model, String name) {
		super(context);
		this.model = model;
		this.texture = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/entity/" + name + ".png");
		this.shadowRadius = 0.4f * model.scale();
	}

	/**
	 * Смещение руки от покоя при взмахе и смене предмета, как у ванильной руки: те же движения, что у руки игрока без предмета
	 * ({@code item} = false) и у предмета в руке ({@code item} = true) в {@code ItemInHandRenderer}. Применяется поверх покоя: ставит руку туда,
	 * где при этом оказалась бы ванильная, не меняя её места в покое.
	 *
	 * @param sign 1 — правая, −1 — левая; {@code attack} — ход взмаха 0..1; {@code equip} — насколько рука опущена при смене предмета 0..1
	 */
	static Matrix4f vanillaDelta(int sign, float attack, float equip, boolean item) {
		Matrix4f now = vanillaArm(sign, attack, equip, item), rest = vanillaArm(sign, 0, 0, item);
		return now.mul(rest.invert());
	}

	private static Matrix4f vanillaArm(int sign, float a, float equip, boolean item) {
		float root = (float) Math.sqrt(a);
		float pi = (float) Math.PI;
		Matrix4f m = new Matrix4f();
		if (!item) {
			m.translate(sign * (-0.3f * Mth.sin(root * pi) + 0.64000005f), 0.4f * Mth.sin(root * 2 * pi) - 0.6f + equip * -0.6f, -0.4f * Mth.sin(a * pi) - 0.71999997f);
			m.rotateY((float) Math.toRadians(sign * 45f));
			m.rotateY((float) Math.toRadians(sign * Mth.sin(root * pi) * 70f));
			m.rotateZ((float) Math.toRadians(sign * Mth.sin(a * a * pi) * -20f));
			return m;
		}
		m.translate(sign * -0.4f * Mth.sin(root * pi), 0.2f * Mth.sin(root * 2 * pi), -0.2f * Mth.sin(a * pi));
		m.translate(sign * 0.56f, -0.52f + equip * -0.6f, -0.72f);
		m.rotateY((float) Math.toRadians(sign * (45f + Mth.sin(a * a * pi) * -20f)));
		m.rotateZ((float) Math.toRadians(sign * Mth.sin(root * pi) * -20f));
		m.rotateX((float) Math.toRadians(Mth.sin(root * pi) * -80f));
		m.rotateY((float) Math.toRadians(sign * -45f));
		return m;
	}

	/**
	 * Руки персонажа от первого лица, в системе камеры. Игрок внутри невидим, поэтому его руку ваниль не рисует; предмет она рисует сама,
	 * и он оказывается в руке. Рисуем обе руки всегда: качаются при ходьбе, а взмах и смена предмета идут так же, как у ванильной руки
	 * ({@link #vanillaDelta}): бьёт та, что и у игрока, пустая рука — по дуге руки, рука с предметом — по дуге предмета. В клубке рук нет.
	 *
	 * @param mainEquip, offEquip насколько опущены основная и вторая рука при смене предмета (0 — в покое)
	 */
	public void submitHands(CharacterEntity entity, LocalPlayer player, float partialTick, PoseStack pose, SubmitNodeCollector collector, int light, float mainEquip, float offEquip) {
		if (entity.isRolling()) return;
		float walk = (float) Math.sin(entity.walkAnimation.position(partialTick) * 0.6662f) * Math.min(1f, entity.walkAnimation.speed(partialTick));
		int mainSide = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
		int hitSide = player.swingingArm == InteractionHand.OFF_HAND ? -mainSide : mainSide;
		float attack = player.getAttackAnim(partialTick);
		float spread = Mth.lerp(partialTick, entity.spreadO, entity.spread);
		int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(entity.hurtTime > 0));
		for (int side : new int[] { -1, 1 }) {
			boolean main = side == mainSide;
			boolean item = !(main ? player.getMainHandItem() : player.getOffhandItem()).isEmpty();
			pose.pushPose();
			pose.mulPose(vanillaDelta(side, side == hitSide ? attack : 0f, main ? mainEquip : offEquip, item));
			pose.translate(side * (HAND_X + spread * 0.25f), HAND_Y - side * walk * 0.012f, HAND_Z + side * walk * 0.03f);
			pose.mulPose(Axis.YP.rotationDegrees(side * HAND_IN));
			pose.mulPose(Axis.ZP.rotationDegrees(side * spread * 55f));
			pose.mulPose(Axis.XP.rotationDegrees(HAND_UP));
			pose.scale(HAND_SCALE, HAND_SCALE, HAND_SCALE);
			collector.submitCustomGeometry(pose, RenderTypes.entityCutout(this.texture), (p, c) -> this.model.renderHand(c, p, new Matrix4f(), light, overlay));
			pose.popPose();
		}
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(CharacterEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
		state.bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
		state.walkPos = entity.walkAnimation.position(partialTick);
		state.walkSpeed = Math.min(1f, entity.walkAnimation.speed(partialTick));
		state.hurt = entity.hurtTime > 0 || entity.deathTime > 0;
		var mc = Minecraft.getInstance();
		// камера внутри шара: от первого лица изнутри ничего не рисуем, иначе экран закрыт гранями (и за открытым окном тоже; превью в окне одежды рисуется)
		state.hidden = !preview && mc.player != null && mc.player.getVehicle() == entity && mc.options.getCameraType().isFirstPerson();
		// камера внутри чужого персонажа (игрок вошёл в него): его грани закрыли бы экран, поэтому его не рисуем, пока камера внутри
		if (!state.hidden && !preview && mc.player != null && mc.player.getVehicle() != entity && entity.getBoundingBox().inflate(0.1).contains(mc.player.getEyePosition(partialTick))) state.hidden = true;
		state.glasses = entity.hasGlasses();
		state.attack = 0f;
		if (!preview && entity.getFirstPassenger() instanceof net.minecraft.world.entity.player.Player rider) {
			state.attack = rider.getAttackAnim(partialTick);
			// основная рука игрока — правая у вселённого персонажа тоже; ось x модели смотрит влево от персонажа, поэтому правая — со знаком −
			boolean right = (rider.getMainArm() == HumanoidArm.RIGHT) != (rider.swingingArm == InteractionHand.OFF_HAND);
			state.attackSide = right ? -1 : 1;
		}
		state.pose = entity.getVisualPose() == null ? null : entity.getVisualPose().copy();
		// превью в окне инвентаря: ванильная функция поворачивает только живые сущности, поэтому поворачиваем сами
		if (preview) {
			state.headYaw = previewHead;
			state.bodyYaw = previewBody;
		}
		state.roll = entity.isRolling() ? new Quaternionf(entity.rollRotO).slerp(entity.rollRot, partialTick) : null;
		state.spread = Mth.lerp(partialTick, entity.spreadO, entity.spread);
		var at = entity.getPosition(partialTick);
		entity.slopeDrop += ((float) com.smeshariki.gameplay.slide.SlopeSurface.gapUnderCenter(entity, at.x, at.y, at.z) - entity.slopeDrop) * 0.25f;
		state.slopeDrop = preview ? 0 : entity.slopeDrop;
		if (!preview && SlideVisual.lying(entity)) {
			SlideVisual.Pose lie = SlideVisual.of(entity);
			state.headYaw = state.bodyYaw = lie.yaw;
			state.lying = lie.tilt;
		} else state.lying = Float.NaN;
		state.death = entity.deathTime > 0 ? Mth.clamp((entity.deathTime + partialTick) / 20f, 0f, 1f) : 0f;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.hidden) return;
		int light = state.lightCoords;
		// всё тело поворачивается к голове, ноги — к телу; при смерти падает на бок
		float fall = (float) Math.toRadians(90) * state.death;
		Matrix4f head = new Matrix4f().rotateZ(fall), feet = new Matrix4f().rotateZ(fall);
		if (state.roll != null) {
			// клубок: всё тело вращается вокруг центра шара в мировых осях
			for (Matrix4f m : new Matrix4f[] { head, feet }) m.translate(0, model.centerY(), 0).rotate(state.roll).translate(0, -model.centerY(), 0);
		}
		head.rotateY((float) Math.toRadians(-state.headYaw));
		feet.rotateY((float) Math.toRadians(-state.bodyYaw));
		if (!Float.isNaN(state.lying)) { // на спине, ноги вперёд: шар лежит на той же точке
			float tilt = (float) Math.toRadians(state.lying - 90);
			for (Matrix4f m : new Matrix4f[] { head, feet }) m.translate(0, model.centerY(), 0).rotateX(tilt).translate(0, -model.centerY(), 0);
		}
		if (state.pose != null) {
			// поза: корень вращает всю модель, тело — только корпус (и всё на нём, включая руки) вокруг центра тела
			Quaternionf root = state.pose.get(Part.ROOT), body = state.pose.get(Part.BODY);
			float cy = model.centerY();
			if (root != null) for (Matrix4f m : new Matrix4f[] { head, feet }) m.translate(0, cy, 0).rotate(root).translate(0, -cy, 0);
			if (body != null) head.translate(0, cy, 0).rotate(body).translate(0, -cy, 0);
		}
		int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(state.hurt));
		pose.pushPose();
		pose.translate(0, -state.slopeDrop, 0);
		pose.scale(model.scale(), model.scale(), model.scale());
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (p, c) -> {
			float swing = (float) Math.sin(state.walkPos * 0.6662f) * state.walkSpeed;
			float sway = (float) Math.sin(state.ageInTicks * 0.1f) * 0.05f + swing * 0.15f;
			Limbs.setAttack(state.attack, state.attackSide);
			model.render(c, p, head, feet, swing, sway, state.spread, state.pose, light, overlay);
			Limbs.setAttack(0f, 0);
			if (state.glasses) GlassesModel.draw(c, p, head, model.centerY() + model.glassesY(), model.glassesY(), model.glassesPush(), light, overlay);
		});
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
