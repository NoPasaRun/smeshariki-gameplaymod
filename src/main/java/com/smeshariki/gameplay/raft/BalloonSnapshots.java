package com.smeshariki.gameplay.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import com.smeshariki.gameplay.rope.RopeEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Снимок шаров вместе с верёвками, висящими на них (стропы с их топливом, тарзанки): чтобы уровни могли начать полёт заново.
 * Возврат удаляет нынешние шар и верёвки и создаёт их из снимка с теми же UUID, поэтому верёвки снова находят свой шар. Если шар в это
 * время в незагруженном чанке, при загрузке ваниль отбросит его как повтор UUID.
 */
public final class BalloonSnapshots {
	/** Насколько далеко от шара искать его верёвки: длиннее верёвок не бывает. */
	private static final double ROPE_REACH = 64;

	private BalloonSnapshots() {
	}

	/**
	 * Плоты и шары, чья позиция внутри box и проходит inside, и их верёвки; пустой снимок, если их нет. Брёвна реки в файл мира не пишутся
	 * ({@code noSave}), поэтому и в снимок не попадают.
	 */
	public static CompoundTag take(ServerLevel level, AABB box, Predicate<Vec3> inside) {
		ListTag balloons = new ListTag();
		ListTag ropes = new ListTag();
		java.util.Set<UUID> seen = new java.util.HashSet<>();
		for (RaftEntity balloon : level.getEntitiesOfClass(RaftEntity.class, box, b -> inside.test(b.position()) && !b.destroyed())) {
			CompoundTag tag = save(level, balloon);
			if (tag == null) continue;
			balloons.add(tag);
			UUID id = balloon.getUUID();
			for (RopeEntity rope : ropesOf(level, balloon)) {
				// верёвка, висящая на двух шарах, попала бы дважды
				if (!seen.add(rope.getUUID())) continue;
				CompoundTag ropeTag = save(level, rope);
				if (ropeTag != null) ropes.add(ropeTag);
			}
		}
		CompoundTag snapshot = new CompoundTag();
		snapshot.put("Balloons", balloons);
		snapshot.put("Ropes", ropes);
		return snapshot;
	}

	/**
	 * Верёвки шара: висящие на нём и те, чей конец прикреплён к нему (тарзанки на корзине). Вторые нужны в снимке тоже: когда шар
	 * удаляют, они видят потерянный плот и отпускают конец, а новый шар с тем же UUID их уже не подбирает.
	 */
	private static List<RopeEntity> ropesOf(ServerLevel level, RaftEntity balloon) {
		UUID id = balloon.getUUID();
		return level.getEntitiesOfClass(RopeEntity.class, balloon.getBoundingBox().inflate(ROPE_REACH), r -> id.equals(r.anchorRaftId()) || id.equals(r.endRaftId()));
	}

	private static CompoundTag save(ServerLevel level, Entity entity) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		if (!entity.save(out)) return null;
		CompoundTag tag = out.buildResult();
		tag.remove("Passengers");
		return tag;
	}

	/** Возвращает шары и их верёвки к снимку: нынешние (и все верёвки на них, даже повешенные позже) удаляются. */
	public static void restore(ServerLevel level, CompoundTag snapshot) {
		List<CompoundTag> all = new ArrayList<>();
		for (Tag tag : snapshot.getListOrEmpty("Balloons")) if (tag instanceof CompoundTag c) all.add(c);
		int balloons = all.size();
		for (Tag tag : snapshot.getListOrEmpty("Ropes")) if (tag instanceof CompoundTag c) all.add(c);
		for (int i = 0; i < balloons; i++) {
			UUID id = all.get(i).read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
			if (id == null) continue;
			if (level.getEntity(id) instanceof RaftEntity old) {
				for (RopeEntity rope : ropesOf(level, old)) rope.discard();
				old.discard();
			}
		}
		EntitySpawnRequest request = new EntitySpawnRequest(EntitySpawnReason.LOAD, true);
		for (CompoundTag tag : all) {
			UUID id = tag.read("UUID", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
			// верёвка из снимка, отвязанная от шара после снимка, ещё жива
			if (id != null && level.getEntity(id) instanceof Entity alive) alive.discard();
			Vec3 pos = tag.read("Pos", Vec3.CODEC).orElse(null);
			if (pos != null) level.getChunk((int) Math.floor(pos.x) >> 4, (int) Math.floor(pos.z) >> 4);
			Entity entity;
			try {
				entity = EntityType.loadEntityRecursive(tag.copy(), level, request, EntityProcessor.NOP);
			} catch (RuntimeException e) {
				continue;
			}
			if (entity != null) level.addFreshEntity(entity);
		}
	}
}
