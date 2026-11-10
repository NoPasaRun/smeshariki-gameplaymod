package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.slide.SlopeSurface;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Открывает расчёт позы лежащего для клиентских миксинов (пакет миксинов не должен зависеть от пакетных классов). */
public final class SlideVisualAccess {
	private SlideVisualAccess() {
	}

	/** {yaw, наклон, на сколько опустить} или null, если тело не скользит. */
	public static float[] lying(Entity e, float partialTick) {
		if (e.getPose() != net.minecraft.world.entity.Pose.SLIDING) return null;
		SlideVisual.Pose p = SlideVisual.of(e);
		Vec3 at = e.getPosition(partialTick);
		return new float[] {p.yaw, p.tilt, (float) SlopeSurface.gapUnderCenter(e, at.x, at.y, at.z)};
	}
}
