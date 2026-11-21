package com.smeshariki.gameplay.client;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.ball.BallEntity;
import com.smeshariki.gameplay.ball.Balls;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Мяч — объёмный шар (сетка по широте и долготе) с пятнистой чёрно-белой текстурой {@code textures/entity/ball.png} в развёртке
 * широта-долгота; катится вместе с движением ({@link BallEntity#spin}).
 */
public class BallRenderer extends EntityRenderer<BallEntity, BallRenderer.State> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/entity/ball.png");
	private static final int LAT = 12, LON = 24;
	/** Единичная сфера: {@code [широта][долгота][x, y, z, u, v]}. */
	private static final float[][][] SPHERE = new float[LAT + 1][LON + 1][];

	static {
		for (int i = 0; i <= LAT; i++) {
			double theta = Math.PI * i / LAT;
			for (int j = 0; j <= LON; j++) {
				double phi = 2 * Math.PI * j / LON;
				SPHERE[i][j] = new float[] { (float) (Math.sin(theta) * Math.cos(phi)), (float) Math.cos(theta), (float) (Math.sin(theta) * Math.sin(phi)), (float) j / LON, (float) i / LAT };
			}
		}
	}

	public static final class State extends EntityRenderState {
		final Quaternionf spin = new Quaternionf();
		/** Стрелка удара от середины мяча (только у целящего), {@code null} — нет. */
		@org.jetbrains.annotations.Nullable net.minecraft.world.phys.Vec3 arrow;
	}

	public BallRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = BallEntity.RADIUS * 0.9f;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(BallEntity ball, State state, float partialTick) {
		super.extractRenderState(ball, state, partialTick);
		ball.spinO.slerp(ball.spin, partialTick, state.spin);
		state.arrow = BallKickClient.aimedArrow(ball, partialTick);
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		pose.pushPose();
		pose.translate(0, BallEntity.RADIUS, 0);
		pose.mulPose(state.spin);
		pose.scale(BallEntity.RADIUS, BallEntity.RADIUS, BallEntity.RADIUS);
		collector.submitCustomGeometry(pose, RenderTypes.entitySolid(TEXTURE), (p, c) -> {
			for (int i = 0; i < LAT; i++) {
				for (int j = 0; j < LON; j++) {
					// обход против часовой снаружи
					vertex(c, p, SPHERE[i][j], light);
					vertex(c, p, SPHERE[i][j + 1], light);
					vertex(c, p, SPHERE[i + 1][j + 1], light);
					vertex(c, p, SPHERE[i + 1][j], light);
				}
			}
		});
		pose.popPose();
		if (state.arrow != null) submitArrow(state.arrow, pose, collector);
		super.submit(state, pose, collector, camera);
	}

	/** Пиксельная стрелка как у стрелки к цели LevelMod ({@code GuideArrow}): остриё справа, столбцы — вперёд, строки — вбок; древко тянется. */
	private static final String[] SHAPE = {
		".......#.....",
		".......##....",
		"###....###...",
		"###########..",
		"############.",
		"###########..",
		"###....###...",
		".......##....",
		".......#.....",
	};
	private static final int SHAPE_COLS = 13, ROWS = 9;
	private static final float PIXEL = 0.08f, THICK = 0.16f;
	/** Цвета слабого удара (как у стрелки LevelMod) и сильнейшего: заливка шахматкой, обводка, бока. */
	private static final int[] WEAK = { 0xFFE066, 0xFFD23F, 0xC96A12, 0x8F4A0C }, STRONG = { 0xFF5A3C, 0xF0412A, 0x9A1E10, 0x5E1008 };
	private static final int ALPHA = 0xC8, ALPHA_EDGE = 0xF0;

	/** Стрелка от края мяча до конца {@code arrow} (от середины мяча), с толщиной; цвет от жёлтого к красному по силе. */
	private static void submitArrow(net.minecraft.world.phys.Vec3 arrow, PoseStack pose, SubmitNodeCollector collector) {
		double len = arrow.length();
		net.minecraft.world.phys.Vec3 f = arrow.scale(1 / len);
		net.minecraft.world.phys.Vec3 s = Math.hypot(f.x, f.z) > 1e-3 ? new net.minecraft.world.phys.Vec3(-f.z, 0, f.x).normalize() : new net.minecraft.world.phys.Vec3(1, 0, 0);
		net.minecraft.world.phys.Vec3 u = s.cross(f);
		float start = BallEntity.RADIUS + 0.05f;
		int cols = Math.max(SHAPE_COLS, Math.round((float) (len - start) / PIXEL));
		float t = (float) Math.clamp((BallKickClient.power(arrow) - 0.5) / (BallEntity.MAX_POWER - 0.5), 0, 1);
		int fill = mix(WEAK[0], STRONG[0], t), fillAlt = mix(WEAK[1], STRONG[1], t), outline = mix(WEAK[2], STRONG[2], t), side = mix(WEAK[3], STRONG[3], t);
		int edgeColor = ALPHA_EDGE << 24 | outline, sideColor = ALPHA_EDGE << 24 | side;
		float[] o = { 0, BallEntity.RADIUS, 0 };
		float[][] axes = { { (float) f.x, (float) f.y, (float) f.z }, { (float) s.x, (float) s.y, (float) s.z }, { (float) u.x, (float) u.y, (float) u.z } };
		collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, c) -> {
			float top = THICK / 2, bottom = -THICK / 2;
			for (int r = 0; r < ROWS; r++) {
				for (int col = 0; col < cols; col++) {
					if (!filled(col, r, cols)) continue;
					boolean edge = !filled(col - 1, r, cols) || !filled(col + 1, r, cols) || !filled(col, r - 1, cols) || !filled(col, r + 1, cols);
					int topColor = (edge ? ALPHA_EDGE : ALPHA) << 24 | (edge ? outline : (col + r) % 2 == 0 ? fill : fillAlt);
					float a0 = start + col * PIXEL, a1 = a0 + PIXEL, w0 = (r - ROWS / 2f) * PIXEL, w1 = w0 + PIXEL;
					face(c, p, o, axes, topColor, a0, w0, top, a1, w0, top, a1, w1, top, a0, w1, top);
					face(c, p, o, axes, edgeColor, a0, w0, bottom, a1, w0, bottom, a1, w1, bottom, a0, w1, bottom);
					if (!filled(col - 1, r, cols)) face(c, p, o, axes, sideColor, a0, w0, bottom, a0, w1, bottom, a0, w1, top, a0, w0, top);
					if (!filled(col + 1, r, cols)) face(c, p, o, axes, sideColor, a1, w0, bottom, a1, w1, bottom, a1, w1, top, a1, w0, top);
					if (!filled(col, r - 1, cols)) face(c, p, o, axes, sideColor, a0, w0, bottom, a1, w0, bottom, a1, w0, top, a0, w0, top);
					if (!filled(col, r + 1, cols)) face(c, p, o, axes, sideColor, a0, w1, bottom, a1, w1, bottom, a1, w1, top, a0, w1, top);
				}
			}
		});
	}

	/** Клетка растянутой стрелки длиной {@code cols}: оперение и остриё из рисунка, между ними древко. */
	private static boolean filled(int col, int row, int cols) {
		if (col < 0 || col >= cols || row < 0 || row >= ROWS) return false;
		int shift = cols - SHAPE_COLS;
		int at = col <= 2 ? col : col >= 7 + shift ? col - shift : 3;
		return SHAPE[row].charAt(at) == '#';
	}

	private static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return r << 16 | g << 8 | bl;
	}

	/** Грань по четырём точкам (вдоль, вбок, по толщине); видна с обеих сторон. */
	private static void face(VertexConsumer c, PoseStack.Pose p, float[] o, float[][] ax, int color, float... q) {
		for (int k = 0; k < 8; k++) {
			int i = k < 4 ? k : 7 - k;
			float a = q[i * 3], w = q[i * 3 + 1], h = q[i * 3 + 2];
			c.addVertex(p, o[0] + ax[0][0] * a + ax[1][0] * w + ax[2][0] * h, o[1] + ax[0][1] * a + ax[1][1] * w + ax[2][1] * h, o[2] + ax[0][2] * a + ax[1][2] * w + ax[2][2] * h).setColor(color);
		}
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose p, float[] v, int light) {
		c.addVertex(p, v[0], v[1], v[2]).setColor(255, 255, 255, 255).setUv(v[3], v[4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, v[0], v[1], v[2]);
	}

	public static void init() {
		EntityRendererRegistry.register(Balls.TYPE, BallRenderer::new);
	}
}
