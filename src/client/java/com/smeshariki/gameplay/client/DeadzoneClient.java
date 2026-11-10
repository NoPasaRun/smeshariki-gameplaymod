package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.smeshariki.gameplay.barrage.Barrages;
import com.smeshariki.gameplay.barrage.DeadzoneEntity;
import com.smeshariki.gameplay.barrage.DeadzoneScroll;
import com.smeshariki.gameplay.barrage.FireMarkerEntity;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Мёртвая зона глазами того, кто держит маркер: открытые грани блоков зоны подсвечены красным (воздух в зону не входит). С маркером зоны в
 * руке колесо меняет размер: зоны под прицелом, а если прицел не на ней — следующей зоны; её блоки там, где она встанет, подсвечены светлее.
 */
public final class DeadzoneClient {
	private static final int ZONE = 0x60FF1E1E, PREVIEW = 0x50FF8C3C;
	/** Отступ подсветки от грани, чтобы не мерцала. */
	private static final float OFFSET = 0.004f;
	/** Больше граней на зону не рисуем; пересчёт граней не чаще раза в столько тиков, если зона не менялась. */
	private static final int MAX_FACES = 40000, REFRESH = 40;
	/** Высота следующей зоны (у новой зоны {@code HalfY} по умолчанию). */
	private static final float PREVIEW_HALF_Y = 8f;
	/** Отдельно от блока — как у предмета: в 3 блоках по взгляду. */
	private static final double AIR_DISTANCE = 3.0;

	private static float placeHalf = DeadzoneScroll.DEFAULT_HALF;
	private static double scrolled;

	/** Открытые грани зоны: x, y, z клетки и номер стороны подряд. */
	private record Faces(String key, long tick, int[] data) {
	}

	private static final Map<Object, Faces> CACHE = new HashMap<>();

	private DeadzoneClient() {
	}

	static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(DeadzoneClient::submit);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			placeHalf = DeadzoneScroll.DEFAULT_HALF;
			CACHE.clear();
		});
	}

	private static boolean holdsZoneMarker(Minecraft mc) {
		return mc.player != null && mc.player.getMainHandItem().is(Barrages.DEADZONE_ITEM);
	}

	/** Колесо с маркером зоны в руке; true — щелчок съеден и панель предметов не листается. */
	static boolean scroll(double delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null || !holdsZoneMarker(mc)) {
			scrolled = 0;
			return false;
		}
		scrolled += delta;
		int steps = (int) scrolled;
		if (steps == 0) return true;
		scrolled -= steps;
		if (mc.crosshairPickEntity instanceof DeadzoneEntity zone) {
			ClientPlayNetworking.send(new DeadzoneScroll(zone.getId(), steps));
		} else {
			placeHalf = DeadzoneScroll.step(placeHalf, steps);
			ClientPlayNetworking.send(new DeadzoneScroll(-1, steps));
		}
		return true;
	}

	private static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !Barrages.holdsMarker(mc.player)) {
			CACHE.clear();
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		List<int[]> zones = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		java.util.Set<Object> alive = new java.util.HashSet<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof DeadzoneEntity zone) || zone.distanceToSqr(cam) > 160 * 160) continue;
			alive.add(zone.getId());
			zones.add(faces(mc.level, zone.getId(), zone.position(), zone.halfX(), zone.halfY(), zone.halfZ()));
			colors.add(ZONE);
		}
		Vec3 preview = previewCenter(mc);
		if (preview != null) {
			alive.add("preview");
			zones.add(faces(mc.level, "preview", preview, placeHalf, PREVIEW_HALF_Y, placeHalf));
			colors.add(PREVIEW);
		}
		CACHE.keySet().retainAll(alive);
		if (zones.isEmpty()) return;
		PoseStack pose = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, c) -> {
			for (int i = 0; i < zones.size(); i++) {
				int[] d = zones.get(i);
				int color = colors.get(i);
				for (int k = 0; k + 3 < d.length; k += 4) face(c, p, d[k] - cam.x, d[k + 1] - cam.y, d[k + 2] - cam.z, Direction.from3DDataValue(d[k + 3]), color);
			}
		});
	}

	/** Где встанет следующая зона (как у предмета): у блока под прицелом или в 3 блоках по взгляду; null — прицел на маркере. */
	private static Vec3 previewCenter(Minecraft mc) {
		if (!holdsZoneMarker(mc) || mc.crosshairPickEntity instanceof FireMarkerEntity) return null;
		BlockPos pos = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection())
				: BlockPos.containing(mc.player.getEyePosition().add(mc.player.getLookAngle().scale(AIR_DISTANCE)));
		return new Vec3(pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5);
	}

	private static int[] faces(ClientLevel level, Object id, Vec3 center, double hx, double hy, double hz) {
		BlockPos[] c = DeadzoneEntity.cells(center, hx, hy, hz);
		String key = c[0].asLong() + ":" + c[1].asLong();
		long now = level.getGameTime();
		Faces cached = CACHE.get(id);
		if (cached != null && cached.key().equals(key) && now - cached.tick() < REFRESH) return cached.data();
		int[] out = new int[MAX_FACES * 4];
		int n = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), next = new BlockPos.MutableBlockPos();
		scan:
		for (int x = c[0].getX(); x <= c[1].getX(); x++) for (int y = c[0].getY(); y <= c[1].getY(); y++) for (int z = c[0].getZ(); z <= c[1].getZ(); z++) {
			if (level.getBlockState(pos.set(x, y, z)).isAir()) continue;
			for (Direction d : Direction.values()) {
				if (!level.getBlockState(next.setWithOffset(pos, d)).isAir()) continue;
				if (n >= out.length) break scan;
				out[n++] = x;
				out[n++] = y;
				out[n++] = z;
				out[n++] = d.get3DDataValue();
			}
		}
		int[] data = java.util.Arrays.copyOf(out, n);
		CACHE.put(id, new Faces(key, now, data));
		return data;
	}

	/** Квадрат на стороне d клетки с нижним углом (x, y, z) относительно камеры; обе стороны, чтобы виден был при любом отсечении. */
	private static void face(VertexConsumer c, PoseStack.Pose p, double x, double y, double z, Direction d, int color) {
		float x0 = (float) x, y0 = (float) y, z0 = (float) z, x1 = x0 + 1, y1 = y0 + 1, z1 = z0 + 1, o = OFFSET;
		float[][] q = switch (d) {
			case DOWN -> new float[][] { { x0, y0 - o, z0 }, { x1, y0 - o, z0 }, { x1, y0 - o, z1 }, { x0, y0 - o, z1 } };
			case UP -> new float[][] { { x0, y1 + o, z0 }, { x0, y1 + o, z1 }, { x1, y1 + o, z1 }, { x1, y1 + o, z0 } };
			case NORTH -> new float[][] { { x0, y0, z0 - o }, { x0, y1, z0 - o }, { x1, y1, z0 - o }, { x1, y0, z0 - o } };
			case SOUTH -> new float[][] { { x0, y0, z1 + o }, { x1, y0, z1 + o }, { x1, y1, z1 + o }, { x0, y1, z1 + o } };
			case WEST -> new float[][] { { x0 - o, y0, z0 }, { x0 - o, y0, z1 }, { x0 - o, y1, z1 }, { x0 - o, y1, z0 } };
			case EAST -> new float[][] { { x1 + o, y0, z0 }, { x1 + o, y1, z0 }, { x1 + o, y1, z1 }, { x1 + o, y0, z1 } };
		};
		for (int i = 0; i < 4; i++) c.addVertex(p, q[i][0], q[i][1], q[i][2]).setColor(color);
		for (int i = 3; i >= 0; i--) c.addVertex(p, q[i][0], q[i][1], q[i][2]).setColor(color);
	}
}
