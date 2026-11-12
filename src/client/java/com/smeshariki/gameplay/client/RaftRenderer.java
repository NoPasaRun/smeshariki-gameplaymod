package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.smeshariki.gameplay.raft.RaftBlocks;
import com.smeshariki.gameplay.raft.RaftEntity;
import com.smeshariki.gameplay.raft.Rafts;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/** Рисует плот: каждый его блок — как движущийся блок (свет, тонировка и затенение углов ванильные). */
public final class RaftRenderer extends EntityRenderer<RaftEntity, RaftRenderer.State> {
	/** Блоки, пришедшие раньше сущности. */
	private static final Map<Integer, RaftBlocks> PENDING = new HashMap<>();

	static void init() {
		EntityRendererRegistry.register(Rafts.TYPE, RaftRenderer::new);
		EntityRendererRegistry.register(com.smeshariki.gameplay.raft.Balloons.TYPE, RaftRenderer::new);
		EntityRendererRegistry.register(com.smeshariki.gameplay.raft.Logs.TYPE, RaftRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(RaftBlocks.TYPE, (payload, ctx) -> {
			ClientLevel level = Minecraft.getInstance().level;
			RaftEntity e = level != null && level.getEntity(payload.entityId()) instanceof RaftEntity r ? r : null;
			if (e != null) apply(e, payload);
			else PENDING.put(payload.entityId(), payload);
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!(entity instanceof RaftEntity r)) return;
			RaftEntity.ALL.add(r);
			RaftBlocks pending = PENDING.remove(r.getId());
			if (pending != null) apply(r, pending);
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof RaftEntity r) RaftEntity.ALL.remove(r);
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PENDING.clear());
	}

	private static void apply(RaftEntity e, RaftBlocks m) {
		e.setCells(m.sx(), m.sy(), m.sz(), Rafts.cellsOf(m));
	}

	/** Вид плота для рисования блоков: соседей берёт из самого плота (для затенения и скрытых граней). */
	private static final class View {
		RaftEntity entity;
		int baseX, baseY, baseZ;
	}

	private static final class Cell extends MovingBlockRenderState {
		final View view;
		final int rx, ry, rz;

		Cell(View view, int rx, int ry, int rz) {
			this.view = view;
			this.rx = rx;
			this.ry = ry;
			this.rz = rz;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return view.entity.cellAt(pos.getX() - view.baseX, pos.getY() - view.baseY, pos.getZ() - view.baseZ);
		}
	}

	private static final class Cache {
		final View view = new View();
		Cell[] cells;
		/** В кэше и скрытые внутри блоки: при разрушении они становятся видны. */
		boolean all;
		long lastBase = Long.MIN_VALUE;
	}

	public static final class State extends EntityRenderState {
		Cache cache;
		double offsetX, offsetZ;
		/** Доля пройденного разрушения; у целого -1. */
		float destroy = -1f;
		int seed;
		float centerX, centerY, centerZ;
	}

	public RaftRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRender(RaftEntity entity, Frustum frustum, double x, double y, double z) {
		return true;
	}

	@Override
	public void extractRenderState(RaftEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		Cache cache = entity.clientCache instanceof Cache c ? c : null;
		boolean destroyed = entity.destroyed();
		if (cache == null || destroyed && !cache.all) {
			cache = new Cache();
			cache.all = destroyed;
			cache.view.entity = entity;
			entity.clientCache = cache;
			List<Cell> list = new ArrayList<>();
			for (int y = 0; y < entity.sy(); y++) {
				for (int z = 0; z < entity.sz(); z++) {
					for (int x = 0; x < entity.sx(); x++) {
						BlockState s = entity.cellAt(x, y, z);
						if (s.isAir() || s.getRenderShape() != RenderShape.MODEL || !destroyed && hidden(entity, x, y, z)) continue;
						Cell cell = new Cell(cache.view, x, y, z);
						cell.blockState = s;
						cell.randomSeedPos = new BlockPos(x, y, z);
						list.add(cell);
					}
				}
			}
			cache.cells = list.toArray(new Cell[0]);
		}
		// позиция сущности — центр основания; блоки рисуем от угла, а для света берём ближайшие клетки мира
		View view = cache.view;
		view.baseX = (int) Math.floor(state.x - entity.sx() / 2.0);
		view.baseY = (int) Math.floor(state.y);
		view.baseZ = (int) Math.floor(state.z - entity.sz() / 2.0);
		long base = BlockPos.asLong(view.baseX, view.baseY, view.baseZ);
		boolean moved = base != cache.lastBase;
		cache.lastBase = base;
		for (Cell c : cache.cells) {
			if (moved || c.blockPos == null) c.blockPos = new BlockPos(view.baseX + c.rx, view.baseY + c.ry, view.baseZ + c.rz);
			c.biome = level.getBiome(c.blockPos);
			c.cardinalLighting = level.cardinalLighting();
			c.lightEngine = level.getLightEngine();
		}
		state.cache = cache;
		state.offsetX = -entity.sx() / 2.0;
		state.offsetZ = -entity.sz() / 2.0;
		state.destroy = entity.destroyProgress(partialTick);
		state.seed = entity.getUUID().hashCode();
		state.centerX = entity.sx() / 2f;
		state.centerY = entity.sy() / 2f;
		state.centerZ = entity.sz() / 2f;
	}

	/** Скорость осколка от середины плота и вверх, блоков за тик, и падение, блоков за тик². */
	private static final float SHATTER_SPEED = 0.06f, SHATTER_JITTER = 0.08f, SHATTER_LIFT = 0.08f, SHATTER_GRAVITY = 0.035f;

	/**
	 * Разрушение: каждый блок со своей задержкой отлетает от середины плота с разбросом, подлетает и падает, крутится вокруг своей оси
	 * и сжимается к своей середине. Всё случайное — из UUID и клетки, поэтому у всех игроков одинаково. false — блок уже исчез.
	 */
	private static boolean shatter(State state, Cell c, PoseStack pose) {
		int h = mix(state.seed ^ mix(c.rx * 73856093 ^ c.ry * 19349663 ^ c.rz * 83492791));
		float r1 = unit(h), r2 = unit(h = mix(h)), r3 = unit(h = mix(h)), r4 = unit(h = mix(h)), r5 = unit(mix(h));
		float delay = r1 * 0.35f;
		float u = Math.clamp((state.destroy - delay) / (1f - delay), 0f, 1f);
		if (u >= 1f) return false;
		float t = u * com.smeshariki.gameplay.raft.RaftEntity.DESTROY_TICKS;
		float dx = c.rx + 0.5f - state.centerX + (r2 - 0.5f) * 1.6f, dy = c.ry + 0.5f - state.centerY + (r3 - 0.5f) * 1.6f, dz = c.rz + 0.5f - state.centerZ + (r4 - 0.5f) * 1.6f;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0e-3f) {
			dy = 1f;
			len = 1f;
		}
		float speed = SHATTER_SPEED + SHATTER_JITTER * r5;
		pose.translate(dx / len * speed * t, dy / len * speed * t + SHATTER_LIFT * t - 0.5f * SHATTER_GRAVITY * t * t, dz / len * speed * t);
		pose.translate(0.5f, 0.5f, 0.5f);
		org.joml.Vector3f axis = new org.joml.Vector3f(r2 - 0.5f, r3 - 0.5f, r4 - 0.5f);
		if (axis.lengthSquared() < 1.0e-4f) axis.set(0, 1, 0);
		axis.normalize();
		pose.mulPose(new org.joml.Quaternionf().rotateAxis((float) Math.toRadians(u * (120f + 240f * r5)), axis));
		float scale = 1f - u * u;
		pose.scale(scale, scale, scale);
		pose.translate(-0.5f, -0.5f, -0.5f);
		return true;
	}

	private static int mix(int x) {
		x ^= x >>> 16;
		x *= 0x7feb352d;
		x ^= x >>> 15;
		x *= 0x846ca68b;
		x ^= x >>> 16;
		return x;
	}

	private static float unit(int h) {
		return (h >>> 8) / (float) (1 << 24);
	}

	/** Скрыт ли блок полностью: все шесть соседей внутри плота — непрозрачные кубы. */
	private static boolean hidden(RaftEntity e, int x, int y, int z) {
		return opaque(e.cellAt(x - 1, y, z)) && opaque(e.cellAt(x + 1, y, z)) && opaque(e.cellAt(x, y - 1, z)) && opaque(e.cellAt(x, y + 1, z))
				&& opaque(e.cellAt(x, y, z - 1)) && opaque(e.cellAt(x, y, z + 1));
	}

	private static boolean opaque(BlockState s) {
		return !s.isAir() && s.isSolidRender();
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.cache == null) return;
		pose.pushPose();
		for (Cell c : state.cache.cells) {
			pose.pushPose();
			pose.translate(state.offsetX + c.rx, c.ry, state.offsetZ + c.rz);
			if (state.destroy >= 0 && !shatter(state, c, pose)) {
				pose.popPose();
				continue;
			}
			collector.submitMovingBlock(pose, c, state.outlineColor);
			pose.popPose();
		}
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
