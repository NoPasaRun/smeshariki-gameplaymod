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

	/** NBT маркера для снимка: сам маркер, ход его волны ({@link LavaWaveEntity#progress}) и уровень лавы под ним. Запуск и обрушение при возврате продолжаются с того же места. */
	public static CompoundTag markerTag(ServerLevel level, LavaWaveEntity wave) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		if (!wave.save(out)) return null;
		CompoundTag tag = out.buildResult();
		tag.merge(wave.progress());
		// «запустить заново при загрузке» (Launch) заменено точным ходом волны
		tag.putBoolean("Launch", false);
		tag.putInt("SnapshotBase", wave.getBlockY() - 1);
		return tag;
	}

	public static CompoundTag take(ServerLevel level, AABB box, Predicate<Vec3> inside) {
		ListTag markers = new ListTag();
		for (LavaWaveEntity wave : level.getEntitiesOfClass(LavaWaveEntity.class, box, w -> inside.test(w.position()))) {
			CompoundTag tag = markerTag(level, wave);
			if (tag != null) markers.add(tag);
		}
		CompoundTag snapshot = new CompoundTag();
		snapshot.put("Markers", markers);
		return snapshot;
	}

	/** Маркер, который ваниль не приняла сразу: UUID старого ещё занят несколько тиков после {@code discard}. Без повтора группа остаётся без маркера, и волну не пустить. */
	private record Retry(ServerLevel level, CompoundTag tag, int[] left) {
	}

	private static final java.util.List<Retry> RETRIES = new java.util.ArrayList<>();
	private static boolean registered;
	private static final int RETRY_EVERY = 2, RETRY_TIMES = 20;
	private static int retryClock;

	private static void tickRetries(net.minecraft.server.MinecraftServer server) {
		if (RETRIES.isEmpty() || ++retryClock % RETRY_EVERY != 0) return;
		for (java.util.Iterator<Retry> it = RETRIES.iterator(); it.hasNext();) {
			Retry r = it.next();
			if (place(r.level, r.tag) || --r.left[0] <= 0) {
				if (r.left[0] <= 0) com.mojang.logging.LogUtils.getLogger().warn("Маркер волны лавы не вернулся из снимка: UUID занят дольше {} тиков", RETRY_EVERY * RETRY_TIMES);
				it.remove();
			}
		}
	}

	/** Ставит маркер из снимка; false — ваниль не приняла (UUID ещё занят). */
	private static boolean place(ServerLevel level, CompoundTag copy) {
		UUID id = copy.read("UUID", UUIDUtil.CODEC).orElse(null);
		if (id != null && level.getEntity(id) instanceof Entity old) old.discard();
		Entity entity;
		try {
			entity = EntityType.loadEntityRecursive(copy.copy(), level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true), EntityProcessor.NOP);
		} catch (RuntimeException e) {
			return true;
		}
		return entity == null || level.addFreshEntity(entity);
	}

	public static void restore(ServerLevel level, CompoundTag snapshot) {
		if (!registered) {
			registered = true;
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(LavaSnapshots::tickRetries);
		}
		for (Tag t : snapshot.getListOrEmpty("Markers")) {
			if (!(t instanceof CompoundTag tag)) continue;
			Vec3 pos = tag.read("Pos", Vec3.CODEC).orElse(null);
			if (pos == null) continue;
			int x = (int) Math.floor(pos.x), z = (int) Math.floor(pos.z), base = tag.getIntOr("SnapshotBase", (int) Math.floor(pos.y) - 1);
			level.getChunk(x >> 4, z >> 4);
			drainAbove(level, x, base, z);
			CompoundTag copy = tag.copy();
			copy.remove("SnapshotBase");
			// ход волны возвращается из снимка (ResumeElapsed и др.); заказ, ждущий маркера (LavaWaves.PENDING), отменяется: он пустил бы волну сразу, как только маркер появится
			copy.putBoolean("Launch", false);
			LavaWaves.cancelPending(copy.getStringOr("Group", ""));
			// прежний повтор для этого же маркера (быстрые сбросы подряд) заменяется новым
			UUID id = copy.read("UUID", UUIDUtil.CODEC).orElse(null);
			if (id != null) RETRIES.removeIf(r -> id.equals(r.tag.read("UUID", UUIDUtil.CODEC).orElse(null)));
			if (!place(level, copy)) RETRIES.add(new Retry(level, copy, new int[] {RETRY_TIMES}));
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
