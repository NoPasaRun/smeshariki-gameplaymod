package com.smeshariki.gameplay.lava;

import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Снимок озера лавы для уровней: маркеры волн в области (их NBT) и уровень лавы у каждого. Возврат снимает поднятые с тех пор слои (сразу, слой за
 * слоем от верхнего) и ставит маркеры заново из снимка: начатая волна и заказанные пропадают, маркер снова внизу. Плоты возвращает снимок плотов.
 */
public final class LavaSnapshots {
	/** Выше снимка проверяем не больше стольких слоёв: волны поднимают лаву до {@code PeakY}, а это десятки блоков. */
	private static final int MAX_LAYERS = 64;

	private LavaSnapshots() {
	}

	public static CompoundTag take(ServerLevel level, AABB box, Predicate<Vec3> inside) {
		ListTag markers = new ListTag();
		for (LavaWaveEntity wave : level.getEntitiesOfClass(LavaWaveEntity.class, box, w -> inside.test(w.position()))) {
			TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			if (!wave.save(out)) continue;
			CompoundTag tag = out.buildResult();
			tag.putInt("SnapshotBase", wave.getBlockY() - 1);
			markers.add(tag);
		}
		CompoundTag snapshot = new CompoundTag();
		snapshot.put("Markers", markers);
		return snapshot;
	}

	public static void restore(ServerLevel level, CompoundTag snapshot) {
		EntitySpawnRequest request = new EntitySpawnRequest(EntitySpawnReason.LOAD, true);
		for (Tag t : snapshot.getListOrEmpty("Markers")) {
			if (!(t instanceof CompoundTag tag)) continue;
			Vec3 pos = tag.read("Pos", Vec3.CODEC).orElse(null);
			if (pos == null) continue;
			int x = (int) Math.floor(pos.x), z = (int) Math.floor(pos.z), base = tag.getIntOr("SnapshotBase", (int) Math.floor(pos.y) - 1);
			level.getChunk(x >> 4, z >> 4);
			drainAbove(level, x, base, z);
			UUID id = tag.read("UUID", UUIDUtil.CODEC).orElse(null);
			if (id != null && level.getEntity(id) instanceof Entity old) old.discard();
			CompoundTag copy = tag.copy();
			copy.remove("SnapshotBase");
			copy.putBoolean("Launch", false);
			Entity entity;
			try {
				entity = EntityType.loadEntityRecursive(copy, level, request, EntityProcessor.NOP);
			} catch (RuntimeException e) {
				continue;
			}
			if (entity != null) level.addFreshEntity(entity);
		}
	}

	/** Снять слои лавы озера над {@code base} у столбца (x, z): от верхнего вниз, каждый слой — все соединённые с ним клетки этого уровня. */
	private static void drainAbove(ServerLevel level, int x, int base, int z) {
		int top = base;
		while (top - base < MAX_LAYERS && level.getFluidState(new BlockPos(x, top + 1, z)).is(FluidTags.LAVA)) top++;
		for (int y = top; y > base; y--) {
			LavaLayer layer = new LavaLayer();
			if (!layer.plan(level, x, y, z, false, "Возврат озера у " + x + " " + z)) break;
			while (!layer.pour(level)) {
			}
		}
	}
}
