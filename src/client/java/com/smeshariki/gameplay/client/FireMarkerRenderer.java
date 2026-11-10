package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.barrage.BarrageEntity;
import com.smeshariki.gameplay.barrage.Barrages;
import com.smeshariki.gameplay.barrage.DeadzoneEntity;
import com.smeshariki.gameplay.barrage.FireMarkerEntity;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * Показывает настройки обстрела и зоны линиями, только пока в руке маркер. Обстрел: белая стрелка — курс; лучи — секторы, длина по плотности (красные — стреляют,
 * зелёные — свободны); лучи показывают, откуда летят стрелы; жёлтое кольцо — на каком удалении от игрока они появляются, серое — радиус действия. Зона: красный ящик.
 */
public final class FireMarkerRenderer extends EntityRenderer<FireMarkerEntity, FireMarkerRenderer.State> {
	private static final int WHITE = 0xFFFFFFFF, RED = 0xFFFF3030, GREEN = 0xFF30FF50, YELLOW = 0xFFFFD020, GRAY = 0xFF909090, CYAN = 0xFF30E0FF;
	private static final float LINE_WIDTH = 2f;
	private static final int RING_STEPS = 64;

	public static void init() {
		EntityRendererRegistry.register(Barrages.BARRAGE, FireMarkerRenderer::new);
		EntityRendererRegistry.register(Barrages.DEADZONE, FireMarkerRenderer::new);
		EntityRendererRegistry.register(com.smeshariki.gameplay.launcher.Launchers.TYPE, FireMarkerRenderer::new);
		EntityRendererRegistry.register(com.smeshariki.gameplay.smoke.Smokes.TYPE, FireMarkerRenderer::new);
		EntityRendererRegistry.register(Barrages.ARROW, net.minecraft.client.renderer.entity.TippableArrowRenderer::new);
	}

	/** Отрезок: концы относительно сущности и цвет. */
	private record Line(Vec3 a, Vec3 b, int color) {
	}

	public static final class State extends EntityRenderState {
		final List<Line> lines = new ArrayList<>();
	}

	public FireMarkerRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	static boolean holdsMarker() {
		var player = Minecraft.getInstance().player;
		return player != null && Barrages.holdsMarker(player);
	}

	/** Линии длиннее сущности: рисуем, даже если сама она вне кадра. */
	@Override
	public boolean shouldRender(FireMarkerEntity entity, Frustum frustum, double x, double y, double z) {
		return holdsMarker();
	}

	@Override
	public void extractRenderState(FireMarkerEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.lines.clear();
		if (!holdsMarker()) return;
		if (entity instanceof BarrageEntity barrage) barrage(barrage, state.lines);
		else if (entity instanceof DeadzoneEntity zone) box(zone, state.lines);
		else if (entity instanceof com.smeshariki.gameplay.launcher.LauncherEntity launcher) arc(launcher, state.lines);
		else if (entity instanceof com.smeshariki.gameplay.smoke.SmokeEntity smoke) column(smoke, state.lines);
	}

	private static void barrage(BarrageEntity b, List<Line> out) {
		Vec3 course = b.direction(0);
		Vec3 tip = course.scale(3), side = new Vec3(-course.z, 0, course.x).scale(0.6);
		out.add(new Line(Vec3.ZERO, tip, WHITE));
		out.add(new Line(tip, course.scale(2.2).add(side), WHITE));
		out.add(new Line(tip, course.scale(2.2).subtract(side), WHITE));
		float[] density = b.density();
		float max = 0.01f;
		for (float d : density) max = Math.max(max, d);
		for (int i = 0; i < density.length; i++) {
			Vec3 ray = b.direction(i * 360.0 / density.length);
			if (density[i] > 0) out.add(new Line(ray.scale(0.5), ray.scale(0.5 + b.distance() * density[i] / max), RED));
			else out.add(new Line(ray.scale(0.5), ray.scale(b.distance()), GREEN));
		}
		ring(b.distance(), YELLOW, out);
		ring(b.radius(), GRAY, out);
	}

	/** Жерло: квадрат клетки и голубая дуга полёта. */
	private static void arc(com.smeshariki.gameplay.launcher.LauncherEntity launcher, List<Line> out) {
		Vec3 last = null;
		for (Vec3 p : launcher.arc()) {
			if (last != null) out.add(new Line(last, p, CYAN));
			last = p;
		}
		double h = 0.5;
		Vec3 a = new Vec3(-h, 0.02, -h), b = new Vec3(h, 0.02, -h), c = new Vec3(h, 0.02, h), d = new Vec3(-h, 0.02, h);
		out.add(new Line(a, b, CYAN));
		out.add(new Line(b, c, CYAN));
		out.add(new Line(c, d, CYAN));
		out.add(new Line(d, a, CYAN));
	}

	/** Дым: серый столб (кольца внизу и вверху, четыре образующие). */
	private static void column(com.smeshariki.gameplay.smoke.SmokeEntity smoke, List<Line> out) {
		double r = smoke.radius(), h = smoke.height();
		ring(r, GRAY, out);
		int from = out.size();
		ring(r, GRAY, out);
		for (int i = from; i < out.size(); i++) out.set(i, new Line(out.get(i).a().add(0, h, 0), out.get(i).b().add(0, h, 0), GRAY));
		for (int k = 0; k < 4; k++) {
			double a = k * Math.PI / 2;
			out.add(new Line(new Vec3(Math.cos(a) * r, 0, Math.sin(a) * r), new Vec3(Math.cos(a) * r, h, Math.sin(a) * r), GRAY));
		}
	}

	private static void ring(double radius, int color, List<Line> out) {
		Vec3 last = null;
		for (int i = 0; i <= RING_STEPS; i++) {
			double a = i * Math.PI * 2 / RING_STEPS;
			Vec3 p = new Vec3(Math.cos(a) * radius, 0, Math.sin(a) * radius);
			if (last != null) out.add(new Line(last, p, color));
			last = p;
		}
	}

	private static void box(DeadzoneEntity z, List<Line> out) {
		double x = z.halfX(), y = z.halfY(), w = z.halfZ();
		for (int sy : new int[] { -1, 1 }) {
			out.add(new Line(new Vec3(-x, sy * y, -w), new Vec3(x, sy * y, -w), RED));
			out.add(new Line(new Vec3(x, sy * y, -w), new Vec3(x, sy * y, w), RED));
			out.add(new Line(new Vec3(x, sy * y, w), new Vec3(-x, sy * y, w), RED));
			out.add(new Line(new Vec3(-x, sy * y, w), new Vec3(-x, sy * y, -w), RED));
		}
		for (int sx : new int[] { -1, 1 }) for (int sz : new int[] { -1, 1 }) out.add(new Line(new Vec3(sx * x, -y, sz * w), new Vec3(sx * x, y, sz * w), RED));
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.lines.isEmpty()) return;
		List<Line> lines = List.copyOf(state.lines);
		collector.submitCustomGeometry(pose, RenderTypes.lines(), (p, c) -> {
			for (Line line : lines) segment(c, p, line);
		});
		super.submit(state, pose, collector, camera);
	}

	private static void segment(VertexConsumer c, PoseStack.Pose p, Line line) {
		Vec3 n = line.b().subtract(line.a()).normalize();
		c.addVertex(p, (float) line.a().x, (float) line.a().y, (float) line.a().z).setColor(line.color()).setNormal(p, (float) n.x, (float) n.y, (float) n.z).setLineWidth(LINE_WIDTH);
		c.addVertex(p, (float) line.b().x, (float) line.b().y, (float) line.b().z).setColor(line.color()).setNormal(p, (float) n.x, (float) n.y, (float) n.z).setLineWidth(LINE_WIDTH);
	}
}
