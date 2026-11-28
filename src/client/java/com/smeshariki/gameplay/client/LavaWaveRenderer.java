package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.lava.LavaWaveEntity;
import com.smeshariki.gameplay.lava.LavaWaves;
import com.smeshariki.gameplay.lava.WaveKind;

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
 * Рисует волну лавы, воды или снега: поверхность с ванильной текстурой материала над каждым столбцом, где волна идёт ({@link LavaWaveEntity#column}),
 * высота в углах клеток по профилю волны над поверхностью материала, углы соседних клеток с близкой высотой сглажены (поэтому на пологом склоне без
 * ступенек); у края и у обрыва (каскад, водопад) — стенка вниз, чтобы сбоку волна не была пустой. Лава светится сама, вода полупрозрачная, снег
 * плотный; вода и снег освещены, как место маркера. С маркером в руке — стрелка курса на поверхности.
 */
public final class LavaWaveRenderer extends EntityRenderer<LavaWaveEntity, LavaWaveRenderer.State> {
	private static final Identifier LAVA = Identifier.withDefaultNamespace("block/lava_still");
	private static final Identifier WATER = Identifier.withDefaultNamespace("block/water_still");
	private static final Identifier SNOW = Identifier.withDefaultNamespace("block/snow");
	/** Цвет воды (текстура воды серая, ваниль красит её цветом биома; здесь — обычная вода равнин). */
	private static final int WATER_R = 63, WATER_G = 118, WATER_B = 228, WATER_ALPHA = 190;
	/** Соседние столбцы ближе по высоте поверхности — сглаживаются в общий угол; дальше — обрыв со стенкой. */
	private static final double SMOOTH = 1.01;
	private static final int FULL_BRIGHT = 0xF000F0;
	/** Ниже этой высоты над лавой волну не рисуем: иначе поверхность мерцала бы с лавой. */
	private static final double MIN_HEIGHT = 0.04;
	/** Чуть выше настоящей лавы, по той же причине. */
	private static final double LIFT = 0.01;
	private static final Identifier ARROW = Identifier.fromNamespaceAndPath(com.smeshariki.gameplay.GameplayMod.NAMESPACE, "textures/entity/lava_wave_marker_arrow.png");
	private static final Identifier WATER_ARROW = Identifier.fromNamespaceAndPath(com.smeshariki.gameplay.GameplayMod.NAMESPACE, "textures/entity/water_wave_marker_arrow.png");
	private static final Identifier SNOW_ARROW = Identifier.fromNamespaceAndPath(com.smeshariki.gameplay.GameplayMod.NAMESPACE, "textures/entity/snow_wave_marker_arrow.png");
	private static final float ARROW_HALF = 0.4f;

	static void init() {
		EntityRendererRegistry.register(LavaWaves.TYPE, LavaWaveRenderer::new);
		// волну спрашивают плоты клиента, считающие движение сами
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof LavaWaveEntity w) LavaWaveEntity.ALL.add(w);
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof LavaWaveEntity w) LavaWaveEntity.ALL.removeIf(x -> x == w);
		});
		// своё тело (игрок или его персонаж) несут волны воды и снега: его движение считает этот клиент
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_LEVEL_TICK.register(level -> {
			var player = Minecraft.getInstance().player;
			if (player == null || player.level() != level || player.isSpectator() || LavaWaveEntity.ALL.isEmpty()) return;
			var body = com.smeshariki.gameplay.character.CharacterEntity.body(player);
			if (body == player && player.getAbilities().flying) return;
			LavaWaveEntity.carryBody(level, body);
		});
	}

	public static final class State extends EntityRenderState {
		/** По 4 вершины на четырёхугольник: x, y, z, u, v. */
		float[] quads = new float[0];
		int quadCount;
		/** Маркер волны в руке: стрелка курса на поверхности лавы. */
		boolean marker;
		Vec3 course = new Vec3(0, 0, 1);
		/** Высота поверхности материала от позиции маркера (маркер стоит на клетку выше его верхней клетки). */
		float surface;
		WaveKind kind = WaveKind.LAVA;
	}

	public LavaWaveRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	/** Волна шире любого кадра сущности: рисуем, пока идёт (в пределах дальности) или пока в руке маркер волны. */
	@Override
	public boolean shouldRender(LavaWaveEntity entity, Frustum frustum, double x, double y, double z) {
		return holdsMarker() || entity.shouldRender(x, y, z) && entity.running(entity.waveTime(0));
	}

	private static double scrolled;

	/** Колесо с маркером волны в руке, наведённым на маркер волны: полуширина ±1 ({@link LavaWaveScroll}); true — щелчок съеден, панель не листается. */
	static boolean scroll(double delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null || !holdsMarker() || !(mc.crosshairPickEntity instanceof LavaWaveEntity wave)) {
			scrolled = 0;
			return false;
		}
		scrolled += delta;
		int steps = (int) scrolled;
		if (steps == 0) return true;
		scrolled -= steps;
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.smeshariki.gameplay.lava.LavaWaveScroll(wave.getId(), steps));
		return true;
	}

	/** Как у ветра и течения: стрелку видно только с маркером своего вида в руке. */
	static boolean holdsMarker() {
		var player = Minecraft.getInstance().player;
		return player != null && LavaWaves.isMarker(player.getMainHandItem());
	}

	@Override
	public void extractRenderState(LavaWaveEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.quadCount = 0;
		state.kind = entity.kind();
		Vec3 origin = entity.getPosition(partialTick);
		double t = entity.waveTime(partialTick);
		if (entity.running(t)) surface(entity, t, origin, state);
		state.marker = holdsMarker();
		state.course = entity.course();
		double top = entity.lavaTop() + (state.kind.fluid() ? LavaWaveEntity.LAVA_TOP : 1.0);
		if (state.kind == WaveKind.SNOW) {
			LavaWaveEntity.Column here = entity.column(entity.getBlockX(), entity.getBlockZ());
			if (here.exists()) top = here.surface();
		}
		state.surface = (float) (top + 0.02 - origin.y);
	}

	/** Углы клетки: (x, z), (x+1, z), (x+1, z+1), (x, z+1). */
	private static int cornerX(int x, int i) {
		return x + (i == 1 || i == 2 ? 1 : 0);
	}

	private static int cornerZ(int z, int i) {
		return z + (i >= 2 ? 1 : 0);
	}

	/**
	 * Верх волны в углу (px, pz) для клетки с поверхностью {@code surface} и высотой волны {@code h}: от среднего по соседним клеткам угла с близкой
	 * высотой, но не ниже самой высокой из них. Иначе на ступеньке склона низкая волна (у фронта и хвоста) уходила под верх соседнего столбца, и
	 * сквозь неё проступали камень и земля ступени, а у совпадающих плоскостей шла штриховка.
	 */
	private static double cornerTop(LavaWaveEntity e, double surface, double h, int px, int pz) {
		double sum = 0, max = surface;
		int n = 0;
		for (int dx = -1; dx <= 0; dx++) {
			for (int dz = -1; dz <= 0; dz++) {
				LavaWaveEntity.Column c = e.column(px + dx, pz + dz);
				if (!c.exists() || Math.abs(c.surface() - surface) > SMOOTH) continue;
				sum += c.surface();
				max = Math.max(max, c.surface());
				n++;
			}
		}
		double base = n == 0 ? surface : sum / n;
		return Math.max(base + Math.max(0, h), max) + LIFT;
	}

	/** Высота волны над поверхностью клетки в углу (px, pz) (не выше места над столбцом). */
	private static double waveHeight(LavaWaveEntity e, LavaWaveEntity.Column c, int px, int pz, double crest, double amp) {
		return Math.min(c.room(), e.heightAt(e.along(px, pz) - crest, amp));
	}

	private static void surface(LavaWaveEntity e, double t, Vec3 origin, State state) {
		Identifier texture = switch (e.kind()) {
			case LAVA -> LAVA;
			case WATER -> WATER;
			case SNOW -> SNOW;
		};
		TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(texture);
		var box = e.band(t);
		double amp = e.amplitude(t), crest = e.crest(t), w = e.halfWidth();
		int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX), z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
		float u0 = sprite.getU(0), u1 = sprite.getU(1), v0 = sprite.getV(0);
		List<float[]> out = new ArrayList<>();
		// стенки к соседям: север, восток, юг, запад (углы a, b и сдвиг к соседу)
		int[][] edges = { { 0, 1, 0, -1 }, { 1, 2, 1, 0 }, { 2, 3, 0, 1 }, { 3, 0, -1, 0 } };
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				if (Math.abs(e.across(x + 0.5, z + 0.5)) > w) continue;
				LavaWaveEntity.Column col = e.column(x, z);
				if (!col.exists()) continue;
				double[] h = new double[4], top = new double[4];
				double max = 0;
				for (int i = 0; i < 4; i++) {
					int px = cornerX(x, i), pz = cornerZ(z, i);
					h[i] = waveHeight(e, col, px, pz, crest, amp);
					top[i] = cornerTop(e, col.surface(), h[i], px, pz);
					max = Math.max(max, h[i]);
				}
				if (max < MIN_HEIGHT) continue;
				float vv = sprite.getV(1);
				out.add(new float[] {
						(float) (x - origin.x), (float) (top[0] - origin.y), (float) (z - origin.z), u0, v0,
						(float) (x + 1 - origin.x), (float) (top[1] - origin.y), (float) (z - origin.z), u1, v0,
						(float) (x + 1 - origin.x), (float) (top[2] - origin.y), (float) (z + 1 - origin.z), u1, vv,
						(float) (x - origin.x), (float) (top[3] - origin.y), (float) (z + 1 - origin.z), u0, vv });
				for (int[] edge : edges) {
					int a = edge[0], b = edge[1], nx = x + edge[2], nz = z + edge[3];
					LavaWaveEntity.Column n = Math.abs(e.across(nx + 0.5, nz + 0.5)) <= w ? e.column(nx, nz) : LavaWaveEntity.NONE;
					// сосед на той же высоте или выше: стыка нет или стенку рисует он
					if (n.exists() && n.surface() >= col.surface() - SMOOTH) continue;
					if (h[a] < MIN_HEIGHT && h[b] < MIN_HEIGHT) continue;
					int ax = cornerX(x, a), az = cornerZ(z, a), bx = cornerX(x, b), bz = cornerZ(z, b);
					// край — до своей поверхности; обрыв — до верха волны у соседа внизу
					double lowA, lowB;
					if (!n.exists()) {
						lowA = cornerTop(e, col.surface(), 0, ax, az);
						lowB = cornerTop(e, col.surface(), 0, bx, bz);
					} else {
						lowA = cornerTop(e, n.surface(), waveHeight(e, n, ax, az, crest, amp), ax, az);
						lowB = cornerTop(e, n.surface(), waveHeight(e, n, bx, bz, crest, amp), bx, bz);
					}
					float vA = sprite.getV((float) Math.clamp(top[a] - lowA, 0, 1)), vB = sprite.getV((float) Math.clamp(top[b] - lowB, 0, 1));
					out.add(new float[] {
							(float) (ax - origin.x), (float) (lowA - origin.y), (float) (az - origin.z), u0, v0,
							(float) (bx - origin.x), (float) (lowB - origin.y), (float) (bz - origin.z), u1, v0,
							(float) (bx - origin.x), (float) (top[b] - origin.y), (float) (bz - origin.z), u1, vB,
							(float) (ax - origin.x), (float) (top[a] - origin.y), (float) (az - origin.z), u0, vA });
				}
			}
		}
		if (state.quads.length < out.size() * 20) state.quads = new float[out.size() * 20];
		for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, state.quads, i * 20, 20);
		state.quadCount = out.size();
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.quadCount > 0) {
			float[] quads = java.util.Arrays.copyOf(state.quads, state.quadCount * 20);
			WaveKind kind = state.kind;
			int light = kind == WaveKind.LAVA ? FULL_BRIGHT : state.lightCoords;
			int r = kind == WaveKind.WATER ? WATER_R : 255, g = kind == WaveKind.WATER ? WATER_G : 255, b = kind == WaveKind.WATER ? WATER_B : 255;
			int alpha = kind == WaveKind.WATER ? WATER_ALPHA : 255;
			var type = switch (kind) {
				case LAVA -> RenderTypes.beaconBeam(TextureAtlas.LOCATION_BLOCKS, false);
				case WATER -> RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
				case SNOW -> RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS);
			};
			collector.submitCustomGeometry(pose, type, (p, c) -> {
				// лава светится сама: тип без освещения сущностей (с ним волна выходила вдвое темнее озера), но он отсекает изнанку, поэтому грань
				// рисуется в обе стороны; склоны чуть темнее по наклону, иначе гребень сливается с озером
				for (int q = 0; q < quads.length; q += 20) {
					float ax = quads[q + 5] - quads[q], ay = quads[q + 6] - quads[q + 1], az = quads[q + 7] - quads[q + 2];
					float bx = quads[q + 15] - quads[q], by = quads[q + 16] - quads[q + 1], bz = quads[q + 17] - quads[q + 2];
					float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
					float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
					// лава светится сама, склоны темнее, чтобы гребень не сливался с озером; вода и снег освещены миром, им хватает лёгкого
					// затенения (при 0,62 белый снег на склонах выходил серым)
					float slope = len < 1e-6f ? 1f : Math.abs(ny) / len;
					float shade = kind == WaveKind.LAVA ? 0.62f + 0.38f * slope : 0.85f + 0.15f * slope;
					for (int side = 0; side < 2; side++) {
						for (int k = 0; k < 4; k++) {
							int i = q + (side == 0 ? k : 3 - k) * 5;
							c.addVertex(p, quads[i], quads[i + 1], quads[i + 2]).setColor(Math.round(r * shade), Math.round(g * shade), Math.round(b * shade), alpha)
									.setUv(quads[i + 3], quads[i + 4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
						}
					}
				}
			});
		}
		if (state.marker) submitArrow(state, pose, collector);
		super.submit(state, pose, collector, camera);
	}

	/** Стрелка своего цвета (лава багровая, вода синяя, снег белая) на поверхности материала по курсу волны (картинка смотрит вправо: «вправо» — курс, «вверх» — вбок), видна сверху и снизу; светится сама. */
	private static void submitArrow(State state, PoseStack pose, SubmitNodeCollector collector) {
		Vec3 f = state.course.scale(ARROW_HALF), side = new Vec3(-state.course.z, 0, state.course.x).scale(ARROW_HALF), up = new Vec3(0, state.surface, 0);
		Identifier texture = switch (state.kind) {
			case LAVA -> ARROW;
			case WATER -> WATER_ARROW;
			case SNOW -> SNOW_ARROW;
		};
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (p, c) -> {
			arrowVertex(c, p, up.subtract(f).add(side), 0, 0, 1);
			arrowVertex(c, p, up.add(f).add(side), 1, 0, 1);
			arrowVertex(c, p, up.add(f).subtract(side), 1, 1, 1);
			arrowVertex(c, p, up.subtract(f).subtract(side), 0, 1, 1);
			arrowVertex(c, p, up.subtract(f).subtract(side), 0, 1, -1);
			arrowVertex(c, p, up.add(f).subtract(side), 1, 1, -1);
			arrowVertex(c, p, up.add(f).add(side), 1, 0, -1);
			arrowVertex(c, p, up.subtract(f).add(side), 0, 0, -1);
		});
	}

	private static void arrowVertex(VertexConsumer c, PoseStack.Pose p, Vec3 v, float u, float w, float ny) {
		c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(255, 255, 255, 255).setUv(u, w).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(FULL_BRIGHT).setNormal(p, 0, ny, 0);
	}
}
