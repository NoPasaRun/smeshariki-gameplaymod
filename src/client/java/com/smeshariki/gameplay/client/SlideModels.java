package com.smeshariki.gameplay.client;

import org.joml.Vector3f;

import com.smeshariki.gameplay.slide.FieldSlideBlock;
import com.smeshariki.gameplay.slide.SlideBlock;

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

/**
 * Настоящий клин ската: наклонная грань, треугольные (трапециевидные) бока, задняя и передняя стенки, низ. Кубоиды JSON такую форму не дают, поэтому
 * JSON-модель блока задаёт только текстуру (и вид предмета), а геометрия строится здесь по {@link SlideBlock#profile}. Текстура на боках привязана к сетке блока, как у ванильных.
 */
final class SlideModels {
	private SlideModels() {
	}

	static void init() {
		ModelLoadingPlugin.register(plugin -> plugin.modifyBlockModelAfterBake().register((model, context) -> {
			BlockState state = context.state();
			if (state != null && state.getBlock() instanceof FieldSlideBlock) return field(state, model.particleMaterial());
			return state != null && state.getBlock() instanceof SlideBlock block ? wedge(block, state, model.particleMaterial()) : model;
		}));
	}

	/** Скат по высотам углов: верх, низ и бока; бока видны только на краю трассы, между клетками поверхность сплошная. */
	private static BlockStateModel field(BlockState state, Material.Baked material) {
		Builder b = new Builder(Direction.NORTH, material);
		double[] c = FieldSlideBlock.corners(state);
		float nw = (float) c[0], ne = (float) c[1], sw = (float) c[2], se = (float) c[3];
		b.add(new Vector3f[] {new Vector3f(0, nw, 0), new Vector3f(1, ne, 0), new Vector3f(1, se, 1), new Vector3f(0, sw, 1)},
				new long[] {b.uv(0, 0), b.uv(1, 0), b.uv(1, 1), b.uv(0, 1)}, Direction.UP, null, 0, 1, 0);
		b.add(new Vector3f[] {new Vector3f(0, 0, 0), new Vector3f(1, 0, 0), new Vector3f(1, 0, 1), new Vector3f(0, 0, 1)},
				new long[] {b.uv(0, 0), b.uv(1, 0), b.uv(1, 1), b.uv(0, 1)}, Direction.DOWN, Direction.DOWN, 0, -1, 0);
		b.skirt(0, 0, 1, 0, nw, ne, Direction.NORTH);
		b.skirt(1, 1, 0, 1, se, sw, Direction.SOUTH);
		b.skirt(0, 1, 0, 0, sw, nw, Direction.WEST);
		b.skirt(1, 0, 1, 1, ne, se, Direction.EAST);
		return new SingleVariant(new SimpleModelWrapper(b.quads.build(), false, material));
	}

	private static BlockStateModel wedge(SlideBlock block, BlockState state, Material.Baked material) {
		Builder b = new Builder(state.getValue(SlideBlock.FACING), material);
		double[][] points = splitAtBlockTop(block.profile(state));
		for (int i = 0; i + 1 < points.length; i++) {
			double t0 = points[i][0], h0 = points[i][1], t1 = points[i + 1][0], h1 = points[i + 1][1];
			if (h0 < 1e-6 && h1 < 1e-6) continue; // здесь блок пуст
			b.top(t0, h0, t1, h1);
			b.bottom(t0, t1);
			b.sides(t0, h0, t1, h1);
		}
		double back = points[0][1], front = points[points.length - 1][1];
		if (back > 1e-6) b.end(0, back);
		if (front > 1e-6) b.end(1, front);
		return new SingleVariant(new SimpleModelWrapper(b.quads.build(), false, material));
	}

	/** Высокий край пологих скатов выходит за блок: на этой высоте полоса бока начинает текстуру заново. */
	private static double[][] splitAtBlockTop(double[][] p) {
		java.util.List<double[]> out = new java.util.ArrayList<>();
		for (int i = 0; i < p.length; i++) {
			out.add(p[i]);
			if (i + 1 < p.length && (p[i][1] - 1) * (p[i + 1][1] - 1) < -1e-9) {
				double k = (1 - p[i][1]) / (p[i + 1][1] - p[i][1]);
				out.add(new double[] {p[i][0] + k * (p[i + 1][0] - p[i][0]), 1});
			}
		}
		return out.toArray(new double[0][]);
	}

	/** Сборка в координатах ската: {@code t} — путь вниз по склону, {@code s} — поперёк, {@code h} — высота. */
	private static final class Builder {
		final QuadCollection.Builder quads = new QuadCollection.Builder();
		final Direction facing, right;
		final float fx, fz, rx, rz;
		final BakedQuad.MaterialInfo info;
		final TextureAtlasSprite sprite;

		Builder(Direction facing, Material.Baked material) {
			this.facing = facing;
			this.right = facing.getClockWise();
			this.fx = facing.getStepX();
			this.fz = facing.getStepZ();
			this.rx = right.getStepX();
			this.rz = right.getStepZ();
			this.sprite = material.sprite();
			this.info = BakedQuad.MaterialInfo.of(material, sprite.transparency(), -1, true, 0);
		}

		Vector3f at(double t, double h, double s) {
			return new Vector3f(0.5f + (float) (t - 0.5) * fx + (float) (s - 0.5) * rx, (float) h, 0.5f + (float) (t - 0.5) * fz + (float) (s - 0.5) * rz);
		}

		long uv(double u, double v) {
			return UVPair.pack(sprite.getU((float) u), sprite.getV((float) v));
		}

		/** Четыре вершины вокруг грани, порядок приводится к обходу против часовой снаружи ({@code outward} — куда смотрит грань). */
		void add(Vector3f[] p, long[] uv, Direction direction, Direction cull, double ox, double oy, double oz) {
			Vector3f a = new Vector3f(p[2]).sub(p[0]), b = new Vector3f(p[3]).sub(p[1]);
			Vector3f n = a.cross(b);
			if (n.x * ox + n.y * oy + n.z * oz < 0) {
				Vector3f tp = p[1];
				p[1] = p[3];
				p[3] = tp;
				long tu = uv[1];
				uv[1] = uv[3];
				uv[3] = tu;
			}
			BakedQuad q = new BakedQuad(p[0], p[1], p[2], p[3], uv[0], uv[1], uv[2], uv[3], direction, info);
			if (cull != null) quads.addCulledFace(cull, q);
			else quads.addUnculledFace(q);
		}

		void top(double t0, double h0, double t1, double h1) {
			add(new Vector3f[] {at(t0, h0, 0), at(t0, h0, 1), at(t1, h1, 1), at(t1, h1, 0)},
					new long[] {uv(0, t0), uv(1, t0), uv(1, t1), uv(0, t1)}, Direction.UP, null, 0, 1, 0);
		}

		void bottom(double t0, double t1) {
			add(new Vector3f[] {at(t0, 0, 0), at(t0, 0, 1), at(t1, 0, 1), at(t1, 0, 0)},
					new long[] {uv(0, t0), uv(1, t0), uv(1, t1), uv(0, t1)}, Direction.DOWN, Direction.DOWN, 0, -1, 0);
		}

		/** Боковые стенки полосы: до верха блока своя отсечкой граней, что выше — без неё (там соседний блок на другой высоте). */
		void sides(double t0, double h0, double t1, double h1) {
			for (int side = 0; side <= 1; side++) {
				Direction d = side == 0 ? right.getOpposite() : right;
				double ox = d.getStepX(), oz = d.getStepZ();
				if (h0 <= 1 + 1e-6 && h1 <= 1 + 1e-6) {
					add(new Vector3f[] {at(t0, 0, side), at(t0, h0, side), at(t1, h1, side), at(t1, 0, side)},
							new long[] {uv(t0, 1), uv(t0, 1 - h0), uv(t1, 1 - h1), uv(t1, 1)}, d, d, ox, 0, oz);
				} else {
					add(new Vector3f[] {at(t0, 0, side), at(t0, 1, side), at(t1, 1, side), at(t1, 0, side)},
							new long[] {uv(t0, 1), uv(t0, 0), uv(t1, 0), uv(t1, 1)}, d, d, ox, 0, oz);
					add(new Vector3f[] {at(t0, 1, side), at(t0, h0, side), at(t1, h1, side), at(t1, 1, side)},
							new long[] {uv(t0, 1), uv(t0, 2 - h0), uv(t1, 2 - h1), uv(t1, 1)}, d, null, ox, 0, oz);
				}
			}
		}

		/** Бок от низа блока до поверхности между точками (x0, z0) и (x1, z1). */
		void skirt(float x0, float z0, float x1, float z1, float h0, float h1, Direction d) {
			add(new Vector3f[] {new Vector3f(x0, 0, z0), new Vector3f(x0, h0, z0), new Vector3f(x1, h1, z1), new Vector3f(x1, 0, z1)},
					new long[] {uv(0, 1), uv(0, Math.max(0, 1 - h0)), uv(1, Math.max(0, 1 - h1)), uv(1, 1)}, d, null, d.getStepX(), 0, d.getStepZ());
		}

		/** Торец блока: задний при {@code t = 0}, передний при {@code t = 1}. */
		void end(double t, double h) {
			Direction d = t == 0 ? facing.getOpposite() : facing;
			double ox = d.getStepX(), oz = d.getStepZ();
			if (h <= 1 + 1e-6) {
				add(new Vector3f[] {at(t, 0, 0), at(t, h, 0), at(t, h, 1), at(t, 0, 1)},
						new long[] {uv(0, 1), uv(0, 1 - h), uv(1, 1 - h), uv(1, 1)}, d, d, ox, 0, oz);
			} else {
				add(new Vector3f[] {at(t, 0, 0), at(t, 1, 0), at(t, 1, 1), at(t, 0, 1)},
						new long[] {uv(0, 1), uv(0, 0), uv(1, 0), uv(1, 1)}, d, d, ox, 0, oz);
				add(new Vector3f[] {at(t, 1, 0), at(t, h, 0), at(t, h, 1), at(t, 1, 1)},
						new long[] {uv(0, 1), uv(0, 2 - h), uv(1, 2 - h), uv(1, 1)}, d, null, ox, 0, oz);
			}
		}
	}
}
