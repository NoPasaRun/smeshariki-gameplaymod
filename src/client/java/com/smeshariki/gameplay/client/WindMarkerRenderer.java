package com.smeshariki.gameplay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.wind.WindMarkerEntity;
import com.smeshariki.gameplay.wind.WindMarkerItem;
import com.smeshariki.gameplay.wind.Winds;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Рисует зону маркера: полупрозрачные завитки ветра на плоскости во всю зону ({@link WindMarkerEntity#RADIUS}), бегущие вдоль стрелки
 * и тающие к краям, как слабеет ветер (в ванили подходящей картинки
 * нет: её частицы порыва — это клубы). Пока в руке маркер, поверх рисуется стрелка, в плоскости ветра и поперёк неё.
 */
public final class WindMarkerRenderer extends EntityRenderer<WindMarkerEntity, WindMarkerRenderer.State> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/item/wind_marker.png");
	/** Половина стороны картинки, блоков. */
	private static final float HALF = 0.5f;
	private static final Identifier WIND_TEXTURE = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/entity/wind.png");
	/** На сколько длин плоскости ветер сдвигается за тик. */
	private static final double SCROLL_SPEED = 0.03;
	private static final int STRIPS = 8, WIND_ALPHA = 255;

	static void init() {
		EntityRendererRegistry.register(Winds.TYPE, WindMarkerRenderer::new);
		Winds.holdsMarkerOnClient = WindMarkerRenderer::holdsMarker;
	}

	public static final class State extends EntityRenderState {
		boolean visible;
		Vec3 dir = Vec3.ZERO, side = Vec3.ZERO;
		double scroll;
	}

	public WindMarkerRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRender(WindMarkerEntity entity, Frustum frustum, double x, double y, double z) {
		return true;
	}

	private static boolean holdsMarker() {
		var player = Minecraft.getInstance().player;
		return player != null && player.getMainHandItem().getItem() instanceof WindMarkerItem;
	}

	@Override
	public void extractRenderState(WindMarkerEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.visible = holdsMarker();
		state.dir = entity.direction();
		state.side = entity.side();
		state.scroll = (entity.tickCount + partialTick) * SCROLL_SPEED;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		pose.pushPose();
		pose.translate(0, WindMarkerEntity.CENTER_Y, 0);
		Vec3 f = state.dir, s = state.side, n = f.cross(s);
		collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(WIND_TEXTURE), (p, c) -> {
			for (int i = 0; i < STRIPS; i++) strip(c, p, f, s, n, (double) i / STRIPS, (double) (i + 1) / STRIPS, state.scroll, light);
		});
		if (state.visible) {
			// картинка стрелки смотрит вправо: её «вправо» кладём на направление ветра
			Vec3 a = f.scale(HALF), side = s.scale(HALF), up = n.scale(HALF);
			collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, c) -> {
				quad(c, p, a, side, light);
				quad(c, p, a, up, light);
			});
		}
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}

	/** Полоса плоскости поперёк ветра от доли t0 до t1 длины. Текстура не повторяется сама, поэтому на её шве полоса делится надвое. */
	private static void strip(VertexConsumer c, PoseStack.Pose p, Vec3 f, Vec3 s, Vec3 n, double t0, double t1, double scroll, int light) {
		double u0 = t0 - scroll, u1 = t1 - scroll, base = Math.floor(u0);
		if (u1 > base + 1) {
			double split = t0 + (base + 1 - u0);
			strip(c, p, f, s, n, t0, split, scroll, light);
			strip(c, p, f, s, n, split, t1, scroll, light);
			return;
		}
		// поперёк — две половины: посередине ярко, к краям зоны тает
		windVertex(c, p, f, s, n, t0, -1, u0 - base, 1, light);
		windVertex(c, p, f, s, n, t1, -1, u1 - base, 1, light);
		windVertex(c, p, f, s, n, t1, 0, u1 - base, 0.5, light);
		windVertex(c, p, f, s, n, t0, 0, u0 - base, 0.5, light);
		windVertex(c, p, f, s, n, t0, 0, u0 - base, 0.5, light);
		windVertex(c, p, f, s, n, t1, 0, u1 - base, 0.5, light);
		windVertex(c, p, f, s, n, t1, 1, u1 - base, 0, light);
		windVertex(c, p, f, s, n, t0, 1, u0 - base, 0, light);
	}

	/** Вершина плоскости: к началу и концу вдоль ветра и к бокам она тает, чтобы не было видно обреза. */
	private static void windVertex(VertexConsumer c, PoseStack.Pose p, Vec3 f, Vec3 s, Vec3 n, double t, double across, double u, double w, int light) {
		Vec3 v = f.scale((2 * t - 1) * WindMarkerEntity.RADIUS).add(s.scale(across * WindMarkerEntity.RADIUS));
		int alpha = (int) (WIND_ALPHA * Math.sin(Math.PI * t) * (1 - Math.abs(across)));
		c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(255, 255, 255, alpha).setUv((float) u, (float) w)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, (float) n.x, (float) n.y, (float) n.z);
	}

	/** Двусторонний квадрат: f — вправо по картинке, s — вверх по ней. */
	private static void quad(VertexConsumer c, PoseStack.Pose p, Vec3 f, Vec3 s, int light) {
		Vec3 n = f.cross(s).normalize();
		vertex(c, p, f.scale(-1).add(s), 0, 0, n, light);
		vertex(c, p, f.scale(-1).subtract(s), 0, 1, n, light);
		vertex(c, p, f.subtract(s), 1, 1, n, light);
		vertex(c, p, f.add(s), 1, 0, n, light);
		n = n.scale(-1);
		vertex(c, p, f.add(s), 1, 0, n, light);
		vertex(c, p, f.subtract(s), 1, 1, n, light);
		vertex(c, p, f.scale(-1).subtract(s), 0, 1, n, light);
		vertex(c, p, f.scale(-1).add(s), 0, 0, n, light);
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose p, Vec3 v, float u, float w, Vec3 n, int light) {
		c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(255, 255, 255, 255).setUv(u, w)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, (float) n.x, (float) n.y, (float) n.z);
	}
}
