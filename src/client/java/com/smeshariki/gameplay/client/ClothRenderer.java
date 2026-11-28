package com.smeshariki.gameplay.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.cloth.ClothEntity;
import com.smeshariki.gameplay.cloth.Cloths;
import com.smeshariki.gameplay.rope.RopeItem;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Рисует полотнище {@link ClothEntity} по узлам его сетки: четырёхугольники с двух сторон, текстура белой шерсти, окрашенная
 * цветом клетки (цвет ширмы берётся у её тонировки), свет — по клетке. Нормали по соседним узлам, поэтому складки видны.
 */
public final class ClothRenderer extends EntityRenderer<ClothEntity, ClothRenderer.State> {
	private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/block/white_wool.png");

	static void init() {
		EntityRendererRegistry.register(Cloths.TYPE, ClothRenderer::new);
		Cloths.pickableOnClient = () -> {
			var player = Minecraft.getInstance().player;
			if (player == null) return false;
			var item = player.getMainHandItem();
			return item.is(Cloths.ITEM) || item.getItem() instanceof RopeItem;
		};
	}

	public static final class State extends EntityRenderState {
		int w, h, seg, nx, ny;
		/** Узлы от позиции сущности: x, y, z подряд. */
		float[] nodes = new float[0];
		int[] colors = new int[0], lights = new int[0];
	}

	public ClothRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(ClothEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.w = entity.width();
		state.h = entity.height();
		state.seg = entity.seg();
		state.nx = entity.nx();
		state.ny = entity.ny();
		int n = state.nx * state.ny;
		if (state.nodes.length != n * 3) state.nodes = new float[n * 3];
		double[] p = new double[3];
		for (int j = 0; j < state.ny; j++) {
			for (int i = 0; i < state.nx; i++) {
				entity.node(i, j, partialTick, p);
				int k = (i + j * state.nx) * 3;
				state.nodes[k] = (float) p[0];
				state.nodes[k + 1] = (float) p[1];
				state.nodes[k + 2] = (float) p[2];
			}
		}
		int cells = state.w * state.h;
		if (state.colors.length != cells) {
			state.colors = new int[cells];
			state.lights = new int[cells];
		}
		BlockState[] states = entity.cellStates();
		var colors = Minecraft.getInstance().getBlockColors();
		for (int r = 0; r < state.h; r++) {
			for (int c = 0; c < state.w; c++) {
				int idx = c + r * state.w;
				int color = 0xFFFFFF;
				if (idx < states.length && !states[idx].isAir()) {
					BlockTintSource tint = colors.getTintSource(states[idx], 0);
					if (tint != null) color = tint.color(states[idx]);
				}
				state.colors[idx] = color & 0xFFFFFF;
				state.lights[idx] = LightCoordsUtil.getLightCoords(entity.level(), entity.cellPos(c, r));
			}
		}
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.nodes.length == 0) return;
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (p, c) -> {
			for (int j = 0; j + 1 < state.ny; j++) {
				for (int i = 0; i + 1 < state.nx; i++) {
					int cell = Math.min(state.w - 1, i / state.seg) + Math.min(state.h - 1, j / state.seg) * state.w;
					int color = state.colors[cell], light = state.lights[cell];
					// текстура — на клетку целиком: узлы внутри клетки делят её
					float u0 = (float) (i % state.seg) / state.seg, u1 = u0 + 1f / state.seg;
					float v0 = (float) (j % state.seg) / state.seg, v1 = v0 + 1f / state.seg;
					int a = i + j * state.nx, b = a + 1, d = a + state.nx, e = d + 1;
					// обход a, d, e, b виден со стороны −(u × t), обратный — со стороны u × t: нормаль у каждой стороны своя
					vertex(c, p, state, a, u0, v0, color, light, -1);
					vertex(c, p, state, d, u0, v1, color, light, -1);
					vertex(c, p, state, e, u1, v1, color, light, -1);
					vertex(c, p, state, b, u1, v0, color, light, -1);
					vertex(c, p, state, b, u1, v0, color, light, 1);
					vertex(c, p, state, e, u1, v1, color, light, 1);
					vertex(c, p, state, d, u0, v1, color, light, 1);
					vertex(c, p, state, a, u0, v0, color, light, 1);
				}
			}
		});
		super.submit(state, pose, collector, camera);
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose p, State s, int k, float u, float v, int color, int light, int side) {
		int i = k % s.nx, j = k / s.nx;
		int l = i > 0 ? k - 1 : k, r = i + 1 < s.nx ? k + 1 : k, up = j > 0 ? k - s.nx : k, dn = j + 1 < s.ny ? k + s.nx : k;
		float[] n = s.nodes;
		float ux = n[r * 3] - n[l * 3], uy = n[r * 3 + 1] - n[l * 3 + 1], uz = n[r * 3 + 2] - n[l * 3 + 2];
		float tx = n[dn * 3] - n[up * 3], ty = n[dn * 3 + 1] - n[up * 3 + 1], tz = n[dn * 3 + 2] - n[up * 3 + 2];
		// u — вправо по ширине, t — вниз
		float nx = uy * tz - uz * ty, ny = uz * tx - ux * tz, nz = ux * ty - uy * tx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-6f) {
			nx = 0;
			ny = 1;
			nz = 0;
		} else {
			nx = nx / len * side;
			ny = ny / len * side;
			nz = nz / len * side;
		}
		c.addVertex(p, n[k * 3], n[k * 3 + 1], n[k * 3 + 2]).setColor((color >> 16) & 255, (color >> 8) & 255, color & 255, 255).setUv(u, v)
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
	}
}
