package com.smeshariki.gameplay.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.mob.Mobs;
import com.smeshariki.gameplay.mob.SandwichEntity;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Рисует Бутерброда ({@link SandwichModel}, увеличен в {@link #SCALE} раза): поворот к взгляду, покачивание на месте, взмахи крыльев, падение
 * набок при гибели, вспышка при заражении, мотание головой после удара; притворяющегося перед заражённым остальным не рисует. Он же — личина морока ({@code mob/SandwichSpell}): пока морок на игроке этого клиента, все живые существа рисуются
 * этим рендерером вместо своего ({@link #disguises}, {@code client/mixin/EntityRenderDispatcherSandwichMixin}) — такими же бутербродами,
 * но там, где стоят сами (настоящий парит на месте).
 */
public final class SandwichRenderer extends EntityRenderer<LivingEntity, SandwichRenderer.State> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/entity/sandwich.png");
	private static final float SCALE = 2f;
	/** Покачивание вверх-вниз (в единицах модели) и его частота; частота взмахов крыльев (радиан за тик). */
	private static final float BOB = 0.04f, BOB_RATE = 0.12f, FLAP_RATE = 0.18f;
	/** Насколько Бутерброд раздувается на пике вспышки. */
	private static final float FLASH_SWELL = 0.2f;

	/** Действующий рендерер (пересоздаётся при перезагрузке ресурсов): им рисуются личины. */
	private static SandwichRenderer instance;

	public static void init() {
		EntityRendererRegistry.register(Mobs.SANDWICH, SandwichRenderer::new);
	}

	/**
	 * Рендерер-личина для сущности или null, если её рисует свой: личину получают живые, пока на игроке клиента морок. Не получают:
	 * сам Бутерброд, невидимые (в том числе игрок внутри своего персонажа), стойки для брони, свой игрок и его персонаж, а также всё, что
	 * рисуется в окнах (превью выбора персонажа и одежды) и не состоит в мире клиента.
	 */
	public static SandwichRenderer disguises(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (instance == null || mc.player == null || Mobs.SPELL == null || !mc.player.hasEffect(Mobs.SPELL)) return null;
		if (!(entity instanceof LivingEntity) || entity instanceof SandwichEntity || entity instanceof ArmorStand || entity.isInvisible()) return null;
		// свой игрок и его персонаж остаются собой; превью в окнах (выбор персонажа, одежда) и сущности не из мира — тоже
		if (entity == mc.player || entity == mc.player.getVehicle() || CharacterRenderer.inPreview()) return null;
		if (mc.level == null || mc.level.getEntity(entity.getId()) != entity) return null;
		return instance;
	}

	/** Рендерер для состояния, снятого личиной (тип сущности в состоянии чужой, по нему игра нашла бы не тот рендерер); иначе null. */
	public static SandwichRenderer disguises(EntityRenderState state) {
		return state instanceof State s && s.disguise ? instance : null;
	}

	public static final class State extends EntityRenderState {
		float yaw, pitch, death, flash;
		boolean hurt, disguise, hidden;
	}

	private SandwichRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.35f * SCALE;
		instance = this;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(LivingEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.disguise = !(entity instanceof SandwichEntity);
		state.flash = 0f;
		state.hidden = false;
		float shake = 0f;
		if (entity instanceof SandwichEntity sandwich) {
			state.flash = sandwich.flash(partialTick);
			shake = sandwich.shake(partialTick);
			// притворяется перед заражённым: остальным его не видно (ни тени, ни имени)
			Minecraft mc = Minecraft.getInstance();
			state.hidden = mc.player != null && sandwich.hiddenFrom(mc.player);
			if (state.hidden) state.shadowRadius = 0f;
		}
		state.yaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) + shake;
		state.pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
		state.hurt = entity.hurtTime > 0 || entity.deathTime > 0;
		state.death = entity.deathTime > 0 ? Mth.clamp((entity.deathTime + partialTick) / 20f, 0f, 1f) : 0f;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.hidden) return;
		int light = state.lightCoords;
		int overlay = OverlayTexture.pack(OverlayTexture.u(state.flash), OverlayTexture.v(state.hurt));
		float bob = Mth.sin(state.ageInTicks * BOB_RATE) * BOB * (1f - state.death);
		float flap = Mth.sin(state.ageInTicks * FLAP_RATE) * (1f - state.death);
		float sway = Mth.sin(state.ageInTicks * BOB_RATE - 1f);
		// весь бутерброд поворачивается к взгляду и немного наклоняется за ним вокруг своей середины
		Matrix4f root = new Matrix4f().translate(0, bob, 0).rotateZ((float) Math.toRadians(90) * state.death).rotateY((float) Math.toRadians(-state.yaw))
				.translate(0, 0.3f, 0).rotateX((float) Math.toRadians(Mth.clamp(state.pitch, -30f, 30f) * 0.5f)).translate(0, -0.3f, 0);
		pose.pushPose();
		// вспышка при наложении морока: белеет (наложение) и раздувается
		float scale = SCALE * (1f + FLASH_SWELL * state.flash);
		pose.scale(scale, scale, scale);
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, c) -> SandwichModel.render(c, p, root, flap, sway, light, overlay));
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
