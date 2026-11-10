package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.lava.LavaWaveEntity;
import com.smeshariki.gameplay.lava.LavaWaves;

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
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Рисует волну лавы: поверхность с текстурой ванильной лавы над каждым столбцом озера, высота в углах клеток по профилю волны (поэтому без ступенек),
 * у края озера — стенка до лавы, чтобы сбоку волна не была пустой. Светится сама. С маркером в руке поверх линии: оранжевый путь гребня, жёлтые края по ширине.
 */
public final class LavaWaveRenderer extends EntityRenderer<LavaWaveEntity, LavaWaveRenderer.State> {
	private static final Identifier LAVA = Identifier.withDefaultNamespace("block/lava_still");
	private static final int FULL_BRIGHT = 0xF000F0;
	/** Ниже этой высоты над лавой волну не рисуем: иначе поверхность мерцала бы с лавой. */
	private static final double MIN_HEIGHT = 0.04;
	/** Чуть выше настоящей лавы, по той же причине. */
	private static final double LIFT = 0.01;
	private static final int ORANGE = 0xFFFF8020, YELLOW = 0xFFFFD020;
	/** Через столько тиков места над столбцами пересчитываются: под волной могли поставить или сломать блок. */
	private static final int ROOM_REFRESH = 40;

	/** Сколько места над столбцами озера: на волну и уровень лавы. */
	private static final class Rooms {
		int base, made;
		final Map<Long, Double> room = new HashMap<>();
	}

	private static final Map<LavaWaveEntity, Rooms> ROOMS = new WeakHashMap<>();

	static void init() {
		EntityRendererRegistry.register(LavaWaves.TYPE, LavaWaveRenderer::new);
		// волну спрашивают плоты клиента, считающие движение сами
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof LavaWaveEntity w) LavaWaveEntity.ALL.add(w);
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof LavaWaveEntity w) LavaWaveEntity.ALL.remove(w);
		});
	}

	private record Line(Vec3 a, Vec3 b, int color) {
	}

	public static final class State extends EntityRenderState {
		/** По 4 вершины на четырёхугольник: x, y, z, u, v. */
		float[] quads = new float[0];
		int quadCount;
		final List<Line> lines = new ArrayList<>();
	}

	public LavaWaveRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	/** Волна шире любого кадра сущности: рисуем, пока идёт или пока в руке маркер. */
	@Override
	public boolean shouldRender(LavaWaveEntity entity, Frustum frustum, double x, double y, double z) {
		return entity.shouldRender(x, y, z) && (entity.running(entity.waveTime(0)) || FireMarkerRenderer.holdsMarker());
	}

	@Override
	public void extractRenderState(LavaWaveEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.quadCount = 0;
		state.lines.clear();
		Vec3 origin = entity.getPosition(partialTick);
		double t = entity.waveTime(partialTick);
		if (entity.running(t)) surface(entity, t, origin, state);
		if (FireMarkerRenderer.holdsMarker()) lines(entity, origin, state.lines);
	}

	private static double room(LavaWaveEntity e, Rooms rooms, int x, int z) {
		return rooms.room.computeIfAbsent(((long) x << 32) ^ (z & 0xFFFFFFFFL), k -> e.columnRoom(x, z));
	}

	private static void surface(LavaWaveEntity e, double t, Vec3 origin, State state) {
		Rooms rooms = ROOMS.computeIfAbsent(e, k -> new Rooms());
		if (rooms.base != e.base() || e.tickCount - rooms.made > ROOM_REFRESH || e.tickCount < rooms.made) {
			rooms.room.clear();
			rooms.base = e.base();
			rooms.made = e.tickCount;
		}
		TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(LAVA);
		var box = e.band(t);
		double amp = e.amplitude(t), crest = e.crest(t), base = e.surfaceY() + LIFT, w = e.halfWidth();
		int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX), z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
		List<float[]> out = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				double cx = x + 0.5, cz = z + 0.5;
				if (Math.abs(e.across(cx, cz)) > w) continue;
				double room = room(e, rooms, x, z);
				if (Double.isNaN(room)) continue;
				// высоты в углах: (x, z), (x+1, z), (x+1, z+1), (x, z+1)
				double[] h = new double[4];
				double max = 0;
				for (int i = 0; i < 4; i++) {
					double px = x + (i == 1 || i == 2 ? 1 : 0), pz = z + (i >= 2 ? 1 : 0);
					h[i] = Math.min(room, e.heightAt(e.along(px, pz) - crest, amp));
					max = Math.max(max, h[i]);
				}
				if (max < MIN_HEIGHT) continue;
				float u0 = sprite.getU(0), u1 = sprite.getU(1), v0 = sprite.getV(0), v1 = sprite.getV(1);
				out.add(new float[] {
						(float) (x - origin.x), (float) (base + h[0] - origin.y), (float) (z - origin.z), u0, v0,
						(float) (x + 1 - origin.x), (float) (base + h[1] - origin.y), (float) (z - origin.z), u1, v0,
						(float) (x + 1 - origin.x), (float) (base + h[2] - origin.y), (float) (z + 1 - origin.z), u1, v1,
						(float) (x - origin.x), (float) (base + h[3] - origin.y), (float) (z + 1 - origin.z), u0, v1 });
				// стенки к соседям, которые не озеро или вне ширины волны: север, восток, юг, запад
				int[][] edges = { { 0, 1, 0, -1 }, { 1, 2, 1, 0 }, { 2, 3, 0, 1 }, { 3, 0, -1, 0 } };
				for (int[] edge : edges) {
					int nx = x + edge[2], nz = z + edge[3];
					boolean open = Math.abs(e.across(nx + 0.5, nz + 0.5)) <= w && !Double.isNaN(room(e, rooms, nx, nz));
					if (open) continue;
					int a = edge[0], b = edge[1];
					if (h[a] < MIN_HEIGHT && h[b] < MIN_HEIGHT) continue;
					float ax = (float) (x + (a == 1 || a == 2 ? 1 : 0) - origin.x), az = (float) (z + (a >= 2 ? 1 : 0) - origin.z);
					float bx = (float) (x + (b == 1 || b == 2 ? 1 : 0) - origin.x), bz = (float) (z + (b >= 2 ? 1 : 0) - origin.z);
					float low = (float) (base - origin.y);
					float vA = sprite.getV((float) Math.min(1, h[a])), vB = sprite.getV((float) Math.min(1, h[b]));
					out.add(new float[] {
							ax, low, az, u0, v0,
							bx, low, bz, u1, v0,
							bx, (float) (base + h[b] - origin.y), bz, u1, vB,
							ax, (float) (base + h[a] - origin.y), az, u0, vA });
				}
			}
		}
		if (state.quads.length < out.size() * 20) state.quads = new float[out.size() * 20];
		for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, state.quads, i * 20, 20);
		state.quadCount = out.size();
	}

	private static void lines(LavaWaveEntity e, Vec3 origin, List<Line> out) {
		Vec3 c = e.course(), s = e.side();
		Vec3 at = new Vec3(e.getX(), e.surfaceY() + 0.05, e.getZ()).subtract(origin);
		Vec3 end = at.add(c.scale(e.length()));
		out.add(new Line(at, end, ORANGE));
		out.add(new Line(end, end.subtract(c.scale(1)).add(s.scale(0.6)), ORANGE));
		out.add(new Line(end, end.subtract(c.scale(1)).subtract(s.scale(0.6)), ORANGE));
		Vec3 half = s.scale(e.halfWidth());
		out.add(new Line(at.subtract(half), at.add(half), YELLOW));
		out.add(new Line(end.subtract(half), end.add(half), YELLOW));
		out.add(new Line(at.subtract(half), end.subtract(half), YELLOW));
		out.add(new Line(at.add(half), end.add(half), YELLOW));
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.quadCount > 0) {
			float[] quads = java.util.Arrays.copyOf(state.quads, state.quadCount * 20);
			collector.submitCustomGeometry(pose, RenderTypes.beaconBeam(TextureAtlas.LOCATION_BLOCKS, false), (p, c) -> {
				// лава светится сама: тип без освещения сущностей (с ним волна выходила вдвое темнее озера), но он отсекает изнанку, поэтому грань
				// рисуется в обе стороны; склоны чуть темнее по наклону, иначе гребень сливается с озером
				for (int q = 0; q < quads.length; q += 20) {
					float ax = quads[q + 5] - quads[q], ay = quads[q + 6] - quads[q + 1], az = quads[q + 7] - quads[q + 2];
					float bx = quads[q + 15] - quads[q], by = quads[q + 16] - quads[q + 1], bz = quads[q + 17] - quads[q + 2];
					float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					int shade = Math.round(255 * (len < 1e-6f ? 1f : 0.62f + 0.38f * Math.abs(ny) / len));
					for (int side = 0; side < 2; side++) {
						for (int k = 0; k < 4; k++) {
							int i = q + (side == 0 ? k : 3 - k) * 5;
							c.addVertex(p, quads[i], quads[i + 1], quads[i + 2]).setColor(shade, shade, shade, 255).setUv(quads[i + 3], quads[i + 4])
									.setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(p, 0, 1, 0);
						}
					}
				}
			});
		}
		if (!state.lines.isEmpty()) {
			List<Line> lines = List.copyOf(state.lines);
			collector.submitCustomGeometry(pose, RenderTypes.lines(), (p, c) -> {
				for (Line line : lines) segment(c, p, line);
			});
		}
		super.submit(state, pose, collector, camera);
	}

	private static void segment(VertexConsumer c, PoseStack.Pose p, Line line) {
		Vec3 n = line.b().subtract(line.a()).normalize();
		c.addVertex(p, (float) line.a().x, (float) line.a().y, (float) line.a().z).setColor(line.color()).setNormal(p, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2f);
		c.addVertex(p, (float) line.b().x, (float) line.b().y, (float) line.b().z).setColor(line.color()).setNormal(p, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2f);
	}
}
