package com.smeshariki.gameplay.character;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * Каждый смешарик в мире один (решение пользователя 2026-11-05). Реестр — файл мира {@code data/gameplaymod/characters.dat}: какая сущность —
 * этот смешарик и где её видели последней (чтобы найти и в незагруженном чанке). Второй такой же при загрузке убирается (роняя одежду): так
 * {@code /summon} и старые копии не плодят двойников. Яйцо призыва переносит смешарика ({@link CharacterEggItem}), выбор персонажа переносит
 * игрока в него ({@link Possession}). Погибший возрождается дома, если дом дают ({@link #setHomes}; уровень может отказать — тогда его
 * создаст следующий выбор там, где нужно уровню).
 */
public final class Singletons {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("gameplaymod/singletons");

	/** Где смешарик возрождается после гибели. */
	public record Home(ResourceKey<Level> dimension, Vec3 pos) {
	}

	/**
	 * Дом смешарика {@code type}. {@code dying} — погибший (при гибели) или {@code null} (просто вопрос). Пусто — дома нет или возрождать сейчас
	 * не надо (гибель посреди уровня: им распоряжается уровень).
	 */
	@FunctionalInterface
	public interface Homes {
		Optional<Home> home(MinecraftServer server, EntityType<CharacterEntity> type, @Nullable CharacterEntity dying);
	}

	private static Homes homes = (server, type, dying) -> Optional.empty();

	public static void setHomes(Homes provider) {
		homes = provider;
	}

	/** Сколько ждать загрузки сущности в незагруженном чанке, прежде чем счесть её потерянной, тиков. */
	private static final int FETCH_TICKS = 100;
	/** Как часто запоминать, где загруженные смешарики. */
	private static final int TRACK_INTERVAL = 20;

	private record Entry(UUID id, ResourceKey<Level> dimension, Vec3 pos, boolean away) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id),
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Entry::dimension),
				Vec3.CODEC.fieldOf("pos").forGetter(Entry::pos),
				Codec.BOOL.optionalFieldOf("away", false).forGetter(Entry::away)
		).apply(i, Entry::new));
	}

	private static final class Data extends SavedData {
		static final Codec<Data> CODEC = Codec.unboundedMap(Identifier.CODEC, Entry.CODEC).xmap(Data::new, d -> d.entries);
		static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "characters"), Data::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
		final Map<Identifier, Entry> entries;

		Data() {
			this(Map.of());
		}

		Data(Map<Identifier, Entry> entries) {
			this.entries = new HashMap<>(entries);
		}

		void put(Identifier key, Entry entry) {
			if (!entry.equals(entries.put(key, entry))) setDirty();
		}

		void remove(Identifier key) {
			if (entries.remove(key) != null) setDirty();
		}
	}

	/** Загруженные смешарики на сервере. */
	private static final Map<Identifier, CharacterEntity> LOADED = new HashMap<>();

	private record Pending(Identifier key, UUID id, ServerLevel level, ChunkPos chunk, boolean forced, long deadline, Consumer<@Nullable CharacterEntity> then) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	private Singletons() {
	}

	static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof CharacterEntity c) onLoad(level, c);
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CharacterEntity c) onUnload(level, c);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof CharacterEntity c && c.level() instanceof ServerLevel level) onDeath(level, c);
		});
		ServerTickEvents.END_SERVER_TICK.register(Singletons::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Pending p : PENDING) unforce(p);
			PENDING.clear();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> LOADED.clear());
	}

	private static Data data(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(Data.TYPE);
	}

	public static Identifier key(EntityType<?> type) {
		return EntityType.getKey(type);
	}

	private static Entry entry(CharacterEntity c, boolean away) {
		return new Entry(c.getUUID(), c.level().dimension(), c.position(), away);
	}

	// ---------------------------------------------------------------- события сущностей

	private static void onLoad(ServerLevel level, CharacterEntity c) {
		Data data = data(level.getServer());
		Identifier key = key(c.getType());
		Entry known = data.entries.get(key);
		if (known == null || known.id().equals(c.getUUID())) {
			data.put(key, entry(c, false));
			LOADED.put(key, c);
			for (Pending p : List.copyOf(PENDING)) if (p.id().equals(c.getUUID())) resolve(p, c);
			return;
		}
		// второй такой же смешарик: лишний он, а не тот, что в реестре (тот где-то в мире или ушёл с игроком)
		c.retireDuplicate(level);
	}

	private static void onUnload(ServerLevel level, CharacterEntity c) {
		Data data = data(level.getServer());
		Identifier key = key(c.getType());
		Entry known = data.entries.get(key);
		if (LOADED.get(key) == c) LOADED.remove(key);
		if (known == null || !known.id().equals(c.getUUID())) return;
		Entity.RemovalReason reason = c.getRemovalReason();
		if (reason == null || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) data.put(key, entry(c, false));
		else if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) data.put(key, entry(c, true));
		else if (reason.shouldDestroy()) data.remove(key);
		// смена измерения: та же сущность (тот же UUID) сейчас появится в другом измерении
	}

	private static void onDeath(ServerLevel level, CharacterEntity c) {
		MinecraftServer server = level.getServer();
		Data data = data(server);
		Identifier key = key(c.getType());
		Entry known = data.entries.get(key);
		if (known == null || !known.id().equals(c.getUUID())) return;
		data.remove(key);
		LOADED.remove(key);
		@SuppressWarnings("unchecked")
		EntityType<CharacterEntity> type = (EntityType<CharacterEntity>) c.getType();
		homes.home(server, type, c).ifPresent(home -> {
			ServerLevel to = server.getLevel(home.dimension());
			if (to != null) create(to, type, home.pos(), c.getYRot());
		});
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % TRACK_INTERVAL == 0) {
			Data data = data(server);
			for (var e : List.copyOf(LOADED.entrySet())) {
				CharacterEntity c = e.getValue();
				if (c.isRemoved()) continue;
				Entry known = data.entries.get(e.getKey());
				if (known == null || !known.id().equals(c.getUUID())) continue;
				if (known.away() || !known.dimension().equals(c.level().dimension()) || known.pos().distanceToSqr(c.position()) > 1) data.put(e.getKey(), entry(c, false));
			}
		}
		for (Pending p : List.copyOf(PENDING)) {
			if (p.level().getEntity(p.id()) instanceof CharacterEntity c) {
				resolve(p, c);
			} else if (server.getTickCount() >= p.deadline()) {
				// так и не загрузился: потерян (чанк повреждён, сущность пропала мимо событий) — забываем, вызвавший создаст нового
				Entry known = data(server).entries.get(p.key());
				if (known != null && known.id().equals(p.id())) data(server).remove(p.key());
				LOGGER.warn("Смешарик {} не нашёлся там, где его видели последним; будет создан новый", p.key());
				resolve(p, null);
			}
		}
	}

	private static void resolve(Pending p, @Nullable CharacterEntity c) {
		PENDING.remove(p);
		unforce(p);
		p.then().accept(c);
	}

	private static void unforce(Pending p) {
		if (!p.forced()) return;
		for (Pending other : PENDING) if (other.level() == p.level() && other.chunk().equals(p.chunk())) return;
		p.level().setChunkForced(p.chunk().x(), p.chunk().z(), false);
	}

	// ---------------------------------------------------------------- для других

	/** Смешарик, если он сейчас загружен. */
	public static @Nullable CharacterEntity find(MinecraftServer server, EntityType<?> type) {
		Identifier key = key(type);
		CharacterEntity c = LOADED.get(key);
		if (c != null && !c.isRemoved()) return c;
		Entry known = data(server).entries.get(key);
		if (known == null) return null;
		ServerLevel level = server.getLevel(known.dimension());
		return level != null && level.getEntity(known.id()) instanceof CharacterEntity found ? found : null;
	}

	/**
	 * Занят ли смешарик: в нём другой игрок, им управляет скрипт (напарник), или он ушёл из мира вместе с игроком, вышедшим из игры в нём.
	 * {@code asker} — кто спрашивает: сам в нём — не занят.
	 */
	public static boolean busy(MinecraftServer server, EntityType<?> type, @Nullable ServerPlayer asker) {
		Entry known = data(server).entries.get(key(type));
		if (known == null) return false;
		if (known.away()) return true;
		CharacterEntity c = find(server, type);
		if (c == null) return false;
		if (c.isScripted()) return true;
		return c.isVehicle() && !(asker != null && c.hasPassenger(asker));
	}

	/**
	 * Достать смешарика: загруженного — сразу, лежащего в незагруженном чанке — после загрузки чанка (до {@link #FETCH_TICKS} тиков).
	 * {@code then} получит {@code null}, если смешарика нет (не было, погиб, потерян): тогда его создают ({@link #create}). Ушедшего с игроком не
	 * достать: {@code then} не вызывается; проверяйте {@link #busy} заранее.
	 */
	public static void fetch(MinecraftServer server, EntityType<CharacterEntity> type, Consumer<@Nullable CharacterEntity> then) {
		CharacterEntity loaded = find(server, type);
		if (loaded != null) {
			then.accept(loaded);
			return;
		}
		Identifier key = key(type);
		Entry known = data(server).entries.get(key);
		if (known == null) {
			then.accept(null);
			return;
		}
		if (known.away()) return;
		ServerLevel level = server.getLevel(known.dimension());
		if (level == null) {
			data(server).remove(key);
			then.accept(null);
			return;
		}
		ChunkPos chunk = ChunkPos.containing(net.minecraft.core.BlockPos.containing(known.pos()));
		boolean forced = !level.getForceLoadedChunks().contains(chunk.pack());
		if (forced) level.setChunkForced(chunk.x(), chunk.z(), true);
		PENDING.add(new Pending(key, known.id(), level, chunk, forced, server.getTickCount() + FETCH_TICKS, then));
	}

	/** Создать смешарика (его ещё нет в мире) и записать в реестр. */
	public static CharacterEntity create(ServerLevel level, EntityType<CharacterEntity> type, Vec3 pos, float yaw) {
		CharacterEntity c = type.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (c == null) throw new IllegalStateException("Не создаётся " + key(type));
		c.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
		c.setPersistenceRequired();
		data(level.getServer()).put(key(type), entry(c, false));
		level.getChunk(net.minecraft.core.BlockPos.containing(pos));
		level.addFreshEntity(c);
		return c;
	}

	/** Перенести смешарика в точку, в том числе в другое измерение. */
	public static void move(CharacterEntity c, ServerLevel to, Vec3 pos, float yaw) {
		c.getNavigation().stop();
		if (c.level() == to) {
			c.teleportTo(pos.x, pos.y, pos.z);
			c.setYRot(yaw);
		} else {
			c.teleport(new TeleportTransition(to, pos, Vec3.ZERO, yaw, 0f, TeleportTransition.DO_NOTHING));
		}
		c.setDeltaMovement(Vec3.ZERO);
		c.resetFallDistance();
	}

	/**
	 * Яйцо призыва и подобное: смешарик переносится в точку, а если его нет — появляется там. Занятого не трогает. @return {@code false} — занят
	 */
	public static boolean summonAt(ServerLevel level, EntityType<CharacterEntity> type, Vec3 pos, float yaw) {
		if (busy(level.getServer(), type, null)) return false;
		fetch(level.getServer(), type, c -> {
			if (c == null) create(level, type, pos, yaw);
			else if (!c.isVehicle() && !c.isScripted()) move(c, level, pos, yaw);
		});
		return true;
	}
}
