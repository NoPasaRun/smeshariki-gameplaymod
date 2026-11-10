package com.smeshariki.gameplay.client;

import org.joml.Vector3f;

import com.smeshariki.gameplay.slide.ColumnBlock;
import com.smeshariki.gameplay.slide.SpikeBlock;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Формы кольев и колонн: восьмигранные сечения по кольцам высот, плоскими гранями по сторонам света. JSON-модель задаёт только текстуру и вид предмета: кубоиды столько не умеют, да и выходить за блок на 5 они не могут. */
final class SpikeModels {
	private static final int SIDES = 8;
	/** Вершина отстоит от оси дальше грани: чтобы половина ширины была именно у плоских граней. */
	private static final double APOTHEM = 1 / Math.cos(Math.PI / SIDES);

	private SpikeModels() {
	}

	static void init() {
		ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((model, context) -> {
			BlockState state = context.state();
			if (state == null) return model;
			if (state.getBlock() instanceof SpikeBlock spike) return build(spike.rings(state), model.particleMaterial(), true, true);
			if (state.getBlock() instanceof ColumnBlock) {
				int index = state.getValue(ColumnBlock.INDEX), height = state.getValue(ColumnBlock.HEIGHT);
				return build(ColumnBlock.rings(index, height), model.particleMaterial(), index == 0, index >= height - 1);
			}
			return model;
		}));
	}

	private static BlockStateModel build(double[][] rings, Material.Baked material, boolean capBottom, boolean capTop) {
		QuadCollection.Builder quads = new QuadCollection.Builder();
		TextureAtlasSprite sprite = material.sprite();
		BakedQuad.MaterialInfo info = BakedQuad.MaterialInfo.of(material, sprite.transparency(), -1, true, 0);
		for (int i = 0; i + 1 < rings.length; i++) slices(quads, sprite, info, rings[i], rings[i + 1]);
		if (capBottom) cap(quads, sprite, info, rings[0], true);
		if (capTop) cap(quads, sprite, info, rings[rings.length - 1], false);
		return new SingleVariant(new SimpleModelWrapper(quads.build(), false, material));
	}

	/** Кольца дальше блока друг от друга режутся по границам блоков: текстура идёт по клеткам, а не растягивается. */
	private static void slices(QuadCollection.Builder quads, TextureAtlasSprite sprite, BakedQuad.MaterialInfo info, double[] low, double[] high) {
		double y = low[0];
		while (y < high[0] - 1e-6) {
			double next = Math.min(Math.floor(y + 1e-6) + 1, high[0]);
			double r0 = low[1] + (high[1] - low[1]) * (y - low[0]) / (high[0] - low[0]);
			double r1 = low[1] + (high[1] - low[1]) * (next - low[0]) / (high[0] - low[0]);
			double base = Math.floor(y + 1e-6);
			for (int side = 0; side < SIDES; side++) side(quads, sprite, info, side, y, r0, next, r1, base);
			y = next;
		}
	}

	private static float px(int corner, double r) {
		return (float) (0.5 + Math.cos(angle(corner)) * r * APOTHEM);
	}

	private static float pz(int corner, double r) {
		return (float) (0.5 + Math.sin(angle(corner)) * r * APOTHEM);
	}

	private static double angle(int corner) {
		return (corner + 0.5) * 2 * Math.PI / SIDES;
	}

	private static void side(QuadCollection.Builder quads, TextureAtlasSprite sprite, BakedQuad.MaterialInfo info, int side, double y0, double r0, double y1, double r1, double base) {
		Vector3f a = new Vector3f(px(side, r0), (float) y0, pz(side, r0)), b = new Vector3f(px(side + 1, r0), (float) y0, pz(side + 1, r0));
		Vector3f c = new Vector3f(px(side + 1, r1), (float) y1, pz(side + 1, r1)), d = new Vector3f(px(side, r1), (float) y1, pz(side, r1));
		float v0 = (float) (1 - (y0 - base)), v1 = (float) (1 - (y1 - base));
		double mid = (side + 1) * 2 * Math.PI / SIDES;
		Direction facing = Math.abs(Math.cos(mid)) >= Math.abs(Math.sin(mid)) ? (Math.cos(mid) > 0 ? Direction.EAST : Direction.WEST) : (Math.sin(mid) > 0 ? Direction.SOUTH : Direction.NORTH);
		// вокруг оси с ростом угла (от +x к +z) и снаружи это обход против часовой: низ, верх, верх соседа, низ соседа
		quads.addUnculledFace(new BakedQuad(a, d, c, b, uv(sprite, 0, v0), uv(sprite, 0, v1), uv(sprite, 1, v1), uv(sprite, 1, v0), facing, info));
	}

	private static void cap(QuadCollection.Builder quads, TextureAtlasSprite sprite, BakedQuad.MaterialInfo info, double[] ring, boolean down) {
		double r = ring[1];
		if (r <= 1e-6) return;
		float y = (float) ring[0];
		for (int corner = 0; corner < SIDES; corner++) {
			Vector3f center = new Vector3f(0.5f, y, 0.5f), p = new Vector3f(px(corner, r), y, pz(corner, r)), q = new Vector3f(px(corner + 1, r), y, pz(corner + 1, r));
			long uc = uv(sprite, 0.5f, 0.5f), up = uv(sprite, clamp(p.x), clamp(p.z)), uq = uv(sprite, clamp(q.x), clamp(q.z));
			if (down) quads.addUnculledFace(new BakedQuad(center, q, p, p, uc, uq, up, up, Direction.DOWN, info));
			else quads.addUnculledFace(new BakedQuad(center, p, q, q, uc, up, uq, uq, Direction.UP, info));
		}
	}

	private static float clamp(float v) {
		return Math.clamp(v, 0f, 1f);
	}

	private static long uv(TextureAtlasSprite sprite, float u, float v) {
		return UVPair.pack(sprite.getU(u), sprite.getV(v));
	}
}
