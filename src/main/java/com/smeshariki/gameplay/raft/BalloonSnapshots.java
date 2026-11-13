package com.smeshariki.gameplay.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
	/**
	 * Общий снимок: все остальные сущности области, кроме тех, что нельзя или не нужно возвращать: игроки и смешарики, всё живое (кроме стоек для брони),
	 * везущее кого-то и едущее на ком-то, предметы на земле, шары опыта, снаряды и падающее (недолговечное), шары и плоты с их верёвками и волны лавы (у них свои
	 * снимки), сущности {@code buildmod:*} (платформы и стены держат состояние в памяти). Остаются верёвки на блоках, рамки и картины, маркеры, вагонетки с
	 * содержимым, лодки, дисплеи и всё подобное. Возврат создаёт их заново с теми же UUID (как верёвки шаров); появившееся позже не удаляется.
	 */
	public static CompoundTag takeEntities(ServerLevel level, AABB box, Predicate<Vec3> inside) {
		ListTag entities = new ListTag();
		for (Entity entity : level.getEntities((Entity) null, box, e -> inside.test(e.position()) && remembered(e))) {
			CompoundTag tag = save(level, entity);
			if (tag != null) entities.add(tag);
		}
		CompoundTag snapshot = new CompoundTag();
		snapshot.put("Entities", entities);
		return snapshot;
	}

	/** Нужна ли сущность общему снимку (см. {@link #takeEntities}). */
	static boolean remembered(Entity e) {
		if (e.isRemoved() || e.isVehicle() || e.isPassenger()) return false;
		if (e instanceof net.minecraft.world.entity.player.Player || e instanceof com.smeshariki.gameplay.character.CharacterEntity) return false;
		if (e instanceof net.minecraft.world.entity.LivingEntity && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)) return false;
		if (e instanceof RaftEntity || e instanceof com.smeshariki.gameplay.lava.LavaWaveEntity) return false;
		if (e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb || e instanceof net.minecraft.world.entity.projectile.Projectile
				|| e instanceof net.minecraft.world.entity.item.FallingBlockEntity || e instanceof net.minecraft.world.entity.item.PrimedTnt || e instanceof net.minecraft.world.entity.LightningBolt) return false;
		if (e instanceof RopeEntity rope && (rope.anchorRaftId() != null || rope.endRaftId() != null)) return false;
		return !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace().equals("buildmod");
	}

	private static List<RopeEntity> ropesOf(ServerLevel level, RaftEntity balloon) {
		UUID id = balloon.getUUID();
		return level.getEntitiesOfClass(RopeEntity.class, balloon.getBoundingBox().inflate(ROPE_REACH), r -> id.equals(r.anchorRaftId()) || id.equals(r.endRaftId()));
	}

	/**
	 * Убирает сущность без выпадения содержимого: вагонетка с сундуком или воронкой при удалении выбрасывает предметы на землю, а возврат создаёт её заново
	 * с её предметами из снимка — предметы бы удвоились (одни в вагонетке, другие на земле).
	 */
	public static void discardQuietly(Entity entity) {
		if (entity instanceof net.minecraft.world.Container container) container.clearContent();
		entity.discard();
	}

	/** Сохранение сущности для снимка (NBT без пассажиров); {@code null} — сущность не сохраняется. */
	public static CompoundTag saveEntity(ServerLevel level, Entity entity) {
		return save(level, entity);
	}

	private static CompoundTag save(ServerLevel level, Entity entity) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		if (!entity.save(out)) return null;
		CompoundTag tag = out.buildResult();
		tag.remove("Passengers");
		return tag;
	}

	/** Возврат, ждущий загрузки чанков: старые сущности в чанках без обработки сущностей (игроки ушли) невидимы для поиска по UUID, и новые с тем же UUID ваниль отвергает. */
	private static final class Pending {
		final ServerLevel level;
		final CompoundTag snapshot;
		final java.util.List<long[]> forced = new ArrayList<>();
		int wait = 3, attempts;
		/** Верёвки: ставятся в последнюю очередь, когда всё остальное уже на месте и блоки области вернулись ({@link #setRestoreGate}). */
		final boolean late;
		int waited;

		Pending(ServerLevel level, CompoundTag snapshot, boolean late) {
			this.level = level;
			this.snapshot = snapshot;
			this.late = late;
		}
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	/** Дольше этого верёвки не ждут возврата блоков, тиков (страховка: ворота не должны держать их вечно). */
	private static final int LATE_LIMIT = 1200;
	private static BooleanSupplier restoreGate = () -> false;

	/** Ворота возврата: пока они вернут true (идёт возврат блоков мира), верёвки из снимка не ставятся: им нужны блоки, на которых они держатся. */
	public static void setRestoreGate(BooleanSupplier busy) {
		restoreGate = busy;
	}

	/** Верёвка ли это (тарзанка, спокойная, тянущая): такие ставятся последними. */
	private static boolean isRope(CompoundTag tag) {
		String id = tag.getStringOr("id", "");
		return id.equals("gameplaymod:tarzan_rope") || id.equals("gameplaymod:calm_rope") || id.equals("gameplaymod:pull_rope");
	}
	private static boolean registered;

	/**
	 * Возвращает шары и их верёвки к снимку: нынешние (и все верёвки на них, даже повешенные позже) удаляются. Сами сущности ставятся через несколько тиков, когда чанки снимка обрабатываются.
	 * Верёвки (висящие на шарах и на блоках) ставятся в последнюю очередь: после остальных сущностей и после возврата блоков (ворота {@link #setRestoreGate}).
	 */
	public static void restore(ServerLevel level, CompoundTag snapshot) {
		if (!registered) {
			registered = true;
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(BalloonSnapshots::tick);
		}
		CompoundTag main = new CompoundTag(), late = new CompoundTag();
		main.put("Balloons", snapshot.getListOrEmpty("Balloons").copy());
		ListTag mainEntities = new ListTag(), lateEntities = new ListTag(), lateRopes = new ListTag();
		for (Tag tag : snapshot.getListOrEmpty("Entities")) (tag instanceof CompoundTag c && isRope(c) ? lateEntities : mainEntities).add(tag.copy());
		for (Tag tag : snapshot.getListOrEmpty("Ropes")) lateRopes.add(tag.copy());
		main.put("Entities", mainEntities);
		late.put("Entities", lateEntities);
		late.put("Ropes", lateRopes);
		queue(level, main, false);
		if (!lateEntities.isEmpty() || !lateRopes.isEmpty()) queue(level, late, true);
	}

	private static void queue(ServerLevel level, CompoundTag snapshot, boolean late) {
		Pending pending = new Pending(level, snapshot, late);
		java.util.Set<Long> chunks = new java.util.HashSet<>();
		for (String key : new String[] {"Balloons", "Ropes", "Entities"}) {
			for (Tag tag : snapshot.getListOrEmpty(key)) {
				Vec3 pos = tag instanceof CompoundTag c ? c.read("Pos", Vec3.CODEC).orElse(null) : null;
				if (pos != null) chunks.add(net.minecraft.world.level.ChunkPos.pack((int) Math.floor(pos.x) >> 4, (int) Math.floor(pos.z) >> 4));
			}
		}
		for (long chunk : chunks) {
			int cx = net.minecraft.world.level.ChunkPos.getX(chunk), cz = net.minecraft.world.level.ChunkPos.getZ(chunk);
			if (level.setChunkForced(cx, cz, true)) pending.forced.add(new long[] {cx, cz});
		}
		PENDING.add(pending);
	}

	private static void tick(net.minecraft.server.MinecraftServer server) {
		for (java.util.Iterator<Pending> it = PENDING.iterator(); it.hasNext();) {
			Pending p = it.next();
			if (--p.wait > 0) continue;
			if (p.late && p.waited++ < LATE_LIMIT) {
				// верёвки — после остальных сущностей и когда блоки области вернулись
				boolean othersPending = false;
				for (Pending other : PENDING) if (!other.late) othersPending = true;
				if (othersPending || restoreGate.getAsBoolean()) {
					p.wait = 1;
					continue;
				}
			}
			int failed = place(p.level, p.snapshot);
			if (failed > 0 && ++p.attempts < 6) {
				p.wait = 5;
				continue;
			}
			for (long[] c : p.forced) p.level.setChunkForced((int) c[0], (int) c[1], false);
			it.remove();
		}
	}

	/** Сколько сущностей ваниль не приняла (UUID ещё занят). */
	private static int place(ServerLevel level, CompoundTag snapshot) {
		int failed = 0;
		List<CompoundTag> all = new ArrayList<>();
		for (Tag tag : snapshot.getListOrEmpty("Balloons")) if (tag instanceof CompoundTag c) all.add(c);
		int balloons = all.size();
		for (Tag tag : snapshot.getListOrEmpty("Ropes")) if (tag instanceof CompoundTag c) all.add(c);
		for (Tag tag : snapshot.getListOrEmpty("Entities")) if (tag instanceof CompoundTag c) all.add(c);
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
			if (id != null && level.getEntity(id) instanceof Entity alive) discardQuietly(alive);
			Entity entity;
			try {
				entity = EntityType.loadEntityRecursive(tag.copy(), level, request, EntityProcessor.NOP);
			} catch (RuntimeException e) {
				continue;
			}
			if (entity != null && !level.addFreshEntity(entity)) failed++;
		}
		return failed;
	}
}
