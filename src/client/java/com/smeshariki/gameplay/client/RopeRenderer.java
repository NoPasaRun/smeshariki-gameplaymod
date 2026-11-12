package com.smeshariki.gameplay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.raft.BalloonRopeEntity;
import com.smeshariki.gameplay.raft.BalloonRopeItem;
import com.smeshariki.gameplay.raft.Balloons;
import com.smeshariki.gameplay.rope.RopeEntity;
import com.smeshariki.gameplay.rope.Ropes;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Рисует верёвку линией от крепления до конца; пока она слабее длины, линия провисает. */
public final class RopeRenderer extends EntityRenderer<RopeEntity, RopeRenderer.State> {
	private static final int SEGMENTS = 20;
	/** Половина толщины верёвки, блоков. */
	private static final float HALF_WIDTH = 0.07f;
	private static final int COLOR_R = 0xB0, COLOR_G = 0x7A, COLOR_B = 0x3A;
	private static final int PALE_R = 0xE6, PALE_G = 0xDC, PALE_B = 0xC0;
	/** Обычная тарзанка (большой размах) — зеленоватая, без размаха — багровая. */
	private static final int WIDE_R = 0x5E, WIDE_G = 0xA0, WIDE_B = 0x4A;
	private static final int CALM_R = 0x9A, CALM_G = 0x24, CALM_B = 0x38;

	/** Где конец верёвки в кадре от первого лица: вперёд, вправо и вниз от глаз, блоков. */
	private static final double HAND_FORWARD = 0.7, HAND_RIGHT = 0.4, HAND_DOWN = 0.25;
	/** Ручка строп: половина ширины, высота и половина толщины палки, блоков. */
	private static final double HANDLE_WIDTH = 0.28, HANDLE_HEIGHT = 0.42, HANDLE_BAR = 0.035;

	public static void init() {
		EntityRendererRegistry.register(Ropes.TYPE, RopeRenderer::new);
		EntityRendererRegistry.register(Ropes.CALM_TYPE, RopeRenderer::new);
		EntityRendererRegistry.register(Ropes.PULL_TYPE, RopeRenderer::new);
		EntityRendererRegistry.register(Balloons.ROPE_TYPE, RopeRenderer::new);
	}

	public static final class State extends EntityRenderState {
		/** Крепление и конец относительно позиции сущности. */
		Vec3 toAnchor = Vec3.ZERO, toEnd = Vec3.ZERO;
		/** Длина нити от крепления до конца или руки и длина хвоста ниже руки. */
		double arm, tail;
		/** Цвет ручки-треугольника у строп шара или -1 у обычной тарзанки. */
		int handle = -1;
		/** Цвет нити: у верёвки для подтягивания светлее. */
		boolean pale;
		/** 1 — зеленоватая (большой размах), 2 — багровая (малый). */
		int kind;
	}

	public RopeRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRender(RopeEntity entity, Frustum frustum, double x, double y, double z) {
		return true;
	}

	@Override
	public void extractRenderState(RopeEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 self = entity.getPosition(partialTick);
		state.toAnchor = entity.anchor(partialTick).subtract(self);
		state.arm = entity.armLength();
		state.tail = entity.mode() == RopeEntity.HELD ? Math.max(0, entity.length() - state.arm) : 0;
		state.toEnd = Vec3.ZERO;
		state.pale = entity instanceof com.smeshariki.gameplay.rope.PullRopeEntity;
		state.kind = entity.getClass() == RopeEntity.class ? 1 : entity instanceof com.smeshariki.gameplay.rope.CalmRopeEntity ? 2 : 0;
		state.handle = entity instanceof BalloonRopeEntity rope ? (rope.kind() == BalloonRopeItem.Kind.LIFT ? 0x4F9BE8 : 0xE8603C) : -1;
		if (entity.mode() == RopeEntity.HELD) {
			Entity holder = entity.level().getEntity(entity.holderId());
			if (holder != null) state.toEnd = drawnHoldPoint(holder, partialTick).subtract(self);
		}
	}

	/**
	 * Куда рисовать конец в руке. Настоящая точка хвата на груди, и от первого лица верёвка шла бы сквозь камеру и закрывала
	 * экран, поэтому своему игроку рисуем её от правой руки в кадре (только вид: держит верёвка всё равно точка на груди).
	 */
	private static Vec3 drawnHoldPoint(Entity holder, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (holder != mc.player || !mc.options.getCameraType().isFirstPerson()) return RopeEntity.holdPoint(holder, partialTick);
		double yaw = Math.toRadians(holder.getViewYRot(partialTick));
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		return holder.getEyePosition(partialTick).add(holder.getViewVector(partialTick).scale(HAND_FORWARD)).add(right.scale(HAND_RIGHT)).add(0, -HAND_DOWN, 0);
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		Vec3 a = state.toAnchor, b = state.toEnd;
		double sag = RopeEntity.sagOf(a, b, state.arm);
		Vec3 tailEnd = b.add(0, -state.tail, 0);
		int light = state.lightCoords;
		collector.submitCustomGeometry(pose, RenderTypes.leash(), (p, consumer) -> {
			boolean hangs = state.tail > 0.01;
			// у строп верёвка кончается вершиной ручки: она сидит над самым концом
			Vec3 apex = state.handle < 0 ? tailEnd : tailEnd.subtract(handleDown(a, tailEnd, hangs).scale(HANDLE_HEIGHT));
			strand(consumer, p, a, hangs ? b : apex, sag, state.pale, state.kind, light);
			// остаток верёвки, свисающий из руки
			if (hangs) strand(consumer, p, b, apex, 0, state.pale, state.kind, light);
			if (state.handle >= 0) handle(consumer, p, apex, tailEnd, state.handle, light);
		});
		super.submit(state, pose, collector, camera);
	}

	/** Направление верёвки у самого конца: вдоль неё, а хвост из руки свисает отвесно. */
	private static Vec3 handleDown(Vec3 anchor, Vec3 end, boolean hangs) {
		Vec3 along = end.subtract(anchor);
		return hangs || along.lengthSqr() < 1e-4 ? new Vec3(0, -1, 0) : along.normalize();
	}

	/** Ручка-треугольник: вершина на верёвке, основание на самом конце. Так стропа лежит на земле, а рука держит основание. */
	private static void handle(VertexConsumer c, PoseStack.Pose p, Vec3 apex, Vec3 end, int color, int light) {
		Vec3 down = end.subtract(apex).normalize();
		Vec3 side = down.cross(Math.abs(down.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
		Vec3 left = end.add(side.scale(HANDLE_WIDTH)), right = end.subtract(side.scale(HANDLE_WIDTH));
		bar(c, p, apex, left, color, light);
		bar(c, p, left, right, color, light);
		bar(c, p, right, apex, color, light);
	}

	private static void bar(VertexConsumer c, PoseStack.Pose p, Vec3 from, Vec3 to, int color, int light) {
		Vec3 dir = to.subtract(from);
		Vec3 side1 = dir.cross(Math.abs(dir.y) > 0.99 * dir.length() ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(HANDLE_BAR);
		Vec3 side2 = dir.cross(side1).normalize().scale(HANDLE_BAR);
		int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
		quad(c, p, from, to, side1, r, g, b, light);
		quad(c, p, from, to, side2, r * 205 / 255, g * 205 / 255, b * 205 / 255, light);
	}

	/** Верёвка от {@code from} до {@code to} с провисом: две перекрёстные ленты — со всех сторон она выглядит объёмной. */
	private static void strand(VertexConsumer consumer, PoseStack.Pose p, Vec3 from, Vec3 to, double sag, boolean pale, int kind, int light) {
		Vec3 prev = from;
		for (int i = 1; i <= SEGMENTS; i++) {
			Vec3 cur = RopeEntity.curvePoint(from, to, sag, (double) i / SEGMENTS);
			Vec3 dir = cur.subtract(prev);
			if (dir.lengthSqr() > 1e-8) {
				int shade = i % 2 == 0 ? 255 : 205;
				int br = pale ? PALE_R : kind == 1 ? WIDE_R : kind == 2 ? CALM_R : COLOR_R, bg = pale ? PALE_G : kind == 1 ? WIDE_G : kind == 2 ? CALM_G : COLOR_G, bb = pale ? PALE_B : kind == 1 ? WIDE_B : kind == 2 ? CALM_B : COLOR_B;
				int r = br * shade / 255, g = bg * shade / 255, bl = bb * shade / 255;
				Vec3 side1 = dir.cross(Math.abs(dir.y) > 0.99 * dir.length() ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(HALF_WIDTH);
				Vec3 side2 = dir.cross(side1).normalize().scale(HALF_WIDTH);
				quad(consumer, p, prev, cur, side1, r, g, bl, light);
				quad(consumer, p, prev, cur, side2, r, g, bl, light);
			}
			prev = cur;
		}
	}

	private static void quad(VertexConsumer c, PoseStack.Pose p, Vec3 from, Vec3 to, Vec3 side, int r, int g, int b, int light) {
		vertex(c, p, from.add(side), r, g, b, light);
		vertex(c, p, to.add(side), r, g, b, light);
		vertex(c, p, to.subtract(side), r, g, b, light);
		vertex(c, p, from.subtract(side), r, g, b, light);
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose p, Vec3 v, int r, int g, int b, int light) {
		c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(r, g, b, 255).setLight(light);
	}
}
