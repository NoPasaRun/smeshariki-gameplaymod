package com.smeshariki.gameplay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.current.CurrentField;
import com.smeshariki.gameplay.current.CurrentMarkerEntity;
import com.smeshariki.gameplay.current.CurrentMarkerItem;
import com.smeshariki.gameplay.current.Currents;

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
 * Рисует течение маркера всегда: бурление — бегущие по течению завитки и пена ({@code textures/entity/current.png}, гуще,
 * чем у ветра) на открытой поверхности воды, которой маркер раздаёт течение; два слоя с разным шагом и скоростью, к краю
 * поля тают. Пока в руке маркер, поверх — плоская картинка стрелки (та же, что значок предмета), лежащая параллельно
 * плоскости XZ и повёрнутая по течению.
 */
public final class CurrentMarkerRenderer extends EntityRenderer<CurrentMarkerEntity, CurrentMarkerRenderer.State> {
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/item/current_marker.png");
	/** Половина стороны картинки, блоков. */
	private static final float HALF = 0.4f;
	private static final Identifier FLOW_TEXTURE = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/entity/current.png");
	/** Слой бурления: блоков на повтор текстуры, скорость по течению (блоков за тик), сдвиг поперёк (доля повтора), подъём над водой, прозрачность. */
	private record Layer(double period, double speed, double shift, float lift, int alpha) {
	}

	private static final Layer[] LAYERS = {new Layer(5.0, 1.5 / 20, 0.0, 0.02f, 150), new Layer(3.5, 2.6 / 20, 0.37, 0.03f, 70)};
	/** Дальше этого от камеры по горизонтали поверхность не рисуем, блоков. */
	private static final double VIEW_RANGE = 64;

	public static void init() {
		EntityRendererRegistry.register(Currents.TYPE, CurrentMarkerRenderer::new);
	}

	public static final class State extends EntityRenderState {
		boolean visible;
		Vec3 dir = Vec3.ZERO;
		CurrentField.Surface surface;
		int originX, originZ;
		double time;
	}

	public CurrentMarkerRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRender(CurrentMarkerEntity entity, Frustum frustum, double x, double y, double z) {
		return true;
	}

	static boolean holdsMarker() {
		var player = Minecraft.getInstance().player;
		return player != null && player.getMainHandItem().getItem() instanceof CurrentMarkerItem;
	}

	@Override
	public void extractRenderState(CurrentMarkerEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.visible = holdsMarker();
		state.dir = entity.direction();
		CurrentField field = CurrentField.of(entity.level());
		state.surface = field == null ? null : field.surfaceOf(entity);
		state.originX = entity.getBlockX();
		state.originZ = entity.getBlockZ();
		state.time = entity.tickCount + partialTick;
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.surface != null && state.surface.size() > 0) submitFlow(state, pose, collector, camera);
		if (state.visible) submitArrow(state, pose, collector);
		super.submit(state, pose, collector, camera);
	}

	private static void submitFlow(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		CurrentField.Surface s = state.surface;
		double dx = state.dir.x, dz = state.dir.z;
		double camX = camera.pos.x, camZ = camera.pos.z, range = VIEW_RANGE * VIEW_RANGE;
		collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(FLOW_TEXTURE), (p, c) -> {
			for (Layer layer : LAYERS) {
				double scroll = state.time * layer.speed() / layer.period();
				scroll -= Math.floor(scroll);
				for (int i = 0; i < s.size(); i++) {
					double cx = s.xs()[i] + 0.5 - camX, cz = s.zs()[i] + 0.5 - camZ;
					if (cx * cx + cz * cz > range) continue;
					int alpha = (int) (layer.alpha() * s.fade()[i]);
					if (alpha <= 0) continue;
					float y = (float) (s.ys()[i] + layer.lift() - state.y);
					int light = s.light()[i];
					// углы клетки: координаты от маркера, текстура — в осях течения, поэтому соседние клетки стыкуются без шва
					int x0 = s.xs()[i] - state.originX, z0 = s.zs()[i] - state.originZ;
					flowVertex(c, p, state, x0, z0, y, dx, dz, layer, scroll, alpha, light);
					flowVertex(c, p, state, x0, z0 + 1, y, dx, dz, layer, scroll, alpha, light);
					flowVertex(c, p, state, x0 + 1, z0 + 1, y, dx, dz, layer, scroll, alpha, light);
					flowVertex(c, p, state, x0 + 1, z0, y, dx, dz, layer, scroll, alpha, light);
				}
			}
		});
	}

	/** Вершина поверхности в клетке маркера со сдвигом (x, z): u — вдоль течения (бежит по нему), v — поперёк. */
	private static void flowVertex(VertexConsumer c, PoseStack.Pose p, State state, int x, int z, float y, double dx, double dz, Layer layer, double scroll, int alpha, int light) {
		double along = x * dx + z * dz, across = -x * dz + z * dx;
		float u = (float) (along / layer.period() - scroll), v = (float) (across / layer.period() + layer.shift());
		float px = (float) (state.originX + x - state.x), pz = (float) (state.originZ + z - state.z);
		c.addVertex(p, px, y, pz).setColor(255, 255, 255, alpha).setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, 1, 0);
	}

	private static void submitArrow(State state, PoseStack pose, SubmitNodeCollector collector) {
		int light = state.lightCoords;
		// картинка стрелки смотрит вправо: её «вправо» кладём на направление течения, «вверх» — вбок
		Vec3 f = state.dir.scale(HALF), s = new Vec3(-state.dir.z, 0, state.dir.x).scale(HALF);
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, c) -> {
			// лицевая сторона сверху и копия снизу — видна и из-под воды
			vertex(c, p, f.scale(-1).add(s), 0, 0, 1, light);
			vertex(c, p, f.add(s), 1, 0, 1, light);
			vertex(c, p, f.subtract(s), 1, 1, 1, light);
			vertex(c, p, f.scale(-1).subtract(s), 0, 1, 1, light);
			vertex(c, p, f.scale(-1).subtract(s), 0, 1, -1, light);
			vertex(c, p, f.subtract(s), 1, 1, -1, light);
			vertex(c, p, f.add(s), 1, 0, -1, light);
			vertex(c, p, f.scale(-1).add(s), 0, 0, -1, light);
		});
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose p, Vec3 v, float u, float w, float ny, int light) {
		c.addVertex(p, (float) v.x, (float) v.y, (float) v.z).setColor(255, 255, 255, 255).setUv(u, w)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0, ny, 0);
	}
}
