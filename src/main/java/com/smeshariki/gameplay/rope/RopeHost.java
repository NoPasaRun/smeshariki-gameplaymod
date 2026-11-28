package com.smeshariki.gameplay.rope;

import org.joml.Vector3fc;

import net.minecraft.world.phys.Vec3;

/**
 * Сущность, на которой крепление верёвки не стоит на месте относительно её позиции, а едет с точкой внутри неё (ткань).
 * Верёвка хранит то, что вернул {@link #ropeLocal}, и каждый кадр спрашивает мировую точку у {@link #ropePoint}.
 */
public interface RopeHost {
	/** Что хранить в верёвке для точки world (своя система сущности, например номер узла сетки). */
	Vec3 ropeLocal(Vec3 world);

	/** Мировая точка крепления по сохранённому local при кадре partialTick. */
	Vec3 ropePoint(Vector3fc local, float partialTick);

	/** Точка сущности под лучом взгляда (from, dir единичный) не дальше reach, или null. */
	Vec3 ropePick(Vec3 from, Vec3 dir, double reach);
}
