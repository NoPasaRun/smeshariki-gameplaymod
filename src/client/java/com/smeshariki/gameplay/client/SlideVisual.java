package com.smeshariki.gameplay.client;

import java.util.Map;
import java.util.WeakHashMap;

import com.smeshariki.gameplay.slide.SlopeSurface;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Как рисовать лежащего на скате: направление ног и наклон сглажены по кадрам, чтобы тело не дёргалось на клетках. */
final class SlideVisual {
	static final class Pose {
		float yaw = Float.NaN, tilt;
		int seen = Integer.MIN_VALUE / 2;
	}

	private static final Map<Entity, Pose> POSES = new WeakHashMap<>();

	private SlideVisual() {
	}

	/** Персонаж недавно скользил: рисуется лёжа, без мигания на тик. */
	static boolean lying(Entity e) {
		Pose p = POSES.computeIfAbsent(e, k -> new Pose());
		if (SlopeSurface.isSliding(e)) p.seen = e.tickCount;
		return e.tickCount - p.seen <= 4;
	}

	static Pose of(Entity e) {
		Pose p = POSES.computeIfAbsent(e, k -> new Pose());
		Vec3 d = SlopeSurface.slideDirection(e);
		float yaw = d.lengthSqr() < 1e-6 ? e.getYRot() : (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		p.yaw = Float.isNaN(p.yaw) ? yaw : Mth.rotLerp(0.15f, p.yaw, yaw);
		p.tilt += ((float) Math.toDegrees(Math.atan(SlopeSurface.slopeUnder(e))) - p.tilt) * 0.15f;
		return p;
	}
}
