package com.smeshariki.gameplay.mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Бутерброд в мире один, и у него одна локация. Данные мира {@code data/gameplaymod/sandwich.dat}: UUID единственного и имя его
 * локации-области LevelMod ({@code /sandwich place}; мост {@link Mechanics#place}).
 * <ul>
 * <li><b>Единственность.</b> Новый (из яйца, {@code /summon}, появившийся сам) становится единственным, прежний исчезает: загруженный —
 * сразу, незагруженный — когда загрузится (его UUID уже не тот, что в данных). У самого Бутерброда в NBT пометка {@code Known}: по ней
 * новый отличается от загруженного с диска.</li>
 * <li><b>Выбор цели.</b> {@link #hunt} — Бутерброд начинает охоту на игрока в своей локации; нет загруженного Бутерброда в локации —
 * появляется в случайном свободном месте локации ({@link #randomSpot}). Вызывают действие уровня {@code gameplaymod:sandwich_hunt},
 * команда {@code /sandwich hunt} и случай: раз в секунду, пока в локации есть незаражённый игрок в выживании или приключении, а Бутерброд
 * без цели (или его нет), с шансом 1 из {@link #HUNT_ODDS}; после гибели Бутерброда случай молчит {@link #QUIET_TICKS}.</li>
 * </ul>
 */
public final class UniqueSandwich {
	/** Случайный выбор цели: шанс 1 из стольких раз в секунду (в среднем раз в минуту). */
	public static final int HUNT_ODDS = 60;
	/** После гибели Бутерброда случай молчит столько тиков (5 минут). */
	public static final long QUIET_TICKS = 5 * 60 * 20;
	/** Место появления: столько попыток, не ближе стольких блоков к игроку, поверхность ищется на столько блоков выше и ниже него. */
	private static final int SPOT_TRIES = 96, SPOT_UP = 8, SPOT_DOWN = 16;
	private static final double SPOT_AWAY = 8;

	private static final class Data extends SavedData {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.optionalFieldOf("id").forGetter(d -> d.id),
				Codec.STRING.optionalFieldOf("place", "").forGetter(d -> d.place),
				Codec.LONG.optionalFieldOf("quiet_until", 0L).forGetter(d -> d.quietUntil),
				Codec.STRING.optionalFieldOf("seen", "").forGetter(d -> d.seen)
		).apply(i, Data::new));
		static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "sandwich"), Data::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
		Optional<UUID> id;
		String place;
		/** Время мира, до которого случай цель не выбирает (после гибели Бутерброда). */
		long quietUntil;
		/** Где и когда Бутерброда видели загруженным в последний раз (текст для отладки); пусто — не видели. */
		String seen;

		Data() {
			this(Optional.empty(), "", 0L, "");
		}

		Data(Optional<UUID> id, String place, long quietUntil, String seen) {
			this.seen = seen;
			this.id = id;
			this.place = place;
			this.quietUntil = quietUntil;
		}
	}

	static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("gameplaymod/sandwich");
	/** Кому раз в полсекунды показывать состояние Бутерброда над панелью ({@code /sandwich debug}); только в памяти. */
	private static final java.util.Set<UUID> WATCHERS = new java.util.HashSet<>();

	private UniqueSandwich() {
	}

	/** Запоминает, где Бутерброд сейчас, и показывает состояние тем, кто включил отладку. */
	private static void track(MinecraftServer server) {
		SandwichEntity sandwich = find(server);
		if (sandwich != null && server.getTickCount() % 20 == 0) {
			Data data = data(server);
			String seen = String.format("%.1f %.1f %.1f в %s", sandwich.getX(), sandwich.getY(), sandwich.getZ(), sandwich.level().dimension().identifier());
			if (!seen.equals(data.seen)) {
				data.seen = seen;
				data.setDirty();
			}
		}
		if (WATCHERS.isEmpty()) return;
		net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.literal(sandwich != null ? "Бутерброд: " + sandwich.debugLine() : absent(server));
		for (UUID id : WATCHERS) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) player.sendOverlayMessage(line);
		}
	}

	private static String absent(MinecraftServer server) {
		Data data = data(server);
		if (data.id.isEmpty()) return "Бутерброда в мире не было";
		return "Бутерброд не загружен или убит" + (data.seen.isEmpty() ? "" : "; последний раз был в " + data.seen);
	}

	/** Всё, что известно о Бутерброде, строками: для {@code /sandwich info}. */
	public static List<String> describe(MinecraftServer server) {
		Data data = data(server);
		List<String> lines = new ArrayList<>();
		SandwichEntity sandwich = find(server);
		lines.add(sandwich != null ? "Бутерброд: " + sandwich.debugLine() + " в " + sandwich.level().dimension().identifier() : absent(server));
		Mechanics.Area area = Mechanics.place(server, data.place);
		lines.add("Локация: " + (data.place.isEmpty() ? "не задана (никого не заражает)" : "«" + data.place + "»" + (area == null ? " — такой локации-области нет" : "")));
		if (sandwich != null && area != null) lines.add(area.dimension() == sandwich.level().dimension() && area.contains(sandwich.position()) ? "Он в своей локации" : "Он вне своей локации: при выборе цели появится новый в локации, этот исчезнет");
		ServerLevel level = area == null ? null : server.getLevel(area.dimension());
		if (level != null) {
			long quiet = data.quietUntil - level.getGameTime();
			if (quiet > 0) lines.add("Случайный выбор цели молчит ещё " + quiet / 20 + " с (после гибели)");
		}
		if (data.id.isPresent()) lines.add("UUID: " + data.id.get());
		return lines;
	}

	/** Включает или выключает игроку показ состояния над панелью; возвращает, включён ли теперь. */
	public static boolean toggleDebug(ServerPlayer player) {
		if (WATCHERS.remove(player.getUUID())) return false;
		WATCHERS.add(player.getUUID());
		return true;
	}

	static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof SandwichEntity sandwich) onLoad(level, sandwich);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 == 0) track(server);
			if (server.getTickCount() % 20 == 0) randomHunt(server);
		});
	}

	private static Data data(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(Data.TYPE);
	}

	/** Имя локации Бутерброда; пусто — не задана (тогда он никого не заражает). */
	public static String place(MinecraftServer server) {
		return data(server).place;
	}

	public static void setPlace(MinecraftServer server, String place) {
		Data data = data(server);
		if (data.place.equals(place)) return;
		data.place = place;
		data.setDirty();
	}

	/** Бутерброд убит: случай не выбирает цель {@link #QUIET_TICKS} (уровень и команда — могут). */
	static void onDeath(ServerLevel level) {
		Data data = data(level.getServer());
		data.quietUntil = level.getGameTime() + QUIET_TICKS;
		data.setDirty();
	}

	/** Загруженный Бутерброд, {@code null} — его нет или он в незагруженном чанке. */
	public static @Nullable SandwichEntity find(MinecraftServer server) {
		Optional<UUID> id = data(server).id;
		if (id.isEmpty()) return null;
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(id.get()) instanceof SandwichEntity sandwich && sandwich.isAlive()) return sandwich;
		}
		return null;
	}

	private static void onLoad(ServerLevel level, SandwichEntity sandwich) {
		MinecraftServer server = level.getServer();
		Data data = data(server);
		UUID id = sandwich.getUUID();
		if (data.id.isPresent() && data.id.get().equals(id)) {
			sandwich.known = true;
			return;
		}
		if (sandwich.known && data.id.isPresent()) {
			// загружен с диска, а единственным с тех пор стал другой
			LOGGER.info("Бутерброд: лишний {} в {} убран (единственный — {})", id, sandwich.position(), data.id.get());
			sandwich.discard();
			return;
		}
		// новый (или первый в мире без записи): прежний, если загружен, исчезает
		if (data.id.isPresent()) {
			for (ServerLevel other : server.getAllLevels()) {
				if (other.getEntity(data.id.get()) instanceof SandwichEntity old && old != sandwich) {
					LOGGER.info("Бутерброд: прежний {} в {} убран, появился новый {} в {}", data.id.get(), old.position(), id, sandwich.position());
					old.discard();
				}
			}
		}
		data.id = Optional.of(id);
		data.setDirty();
		sandwich.known = true;
	}

	private static void randomHunt(MinecraftServer server) {
		Mechanics.Area area = Mechanics.place(server, place(server));
		ServerLevel level = area == null ? null : server.getLevel(area.dimension());
		if (level == null || level.getGameTime() < data(server).quietUntil) return;
		SandwichEntity sandwich = find(server);
		if (sandwich != null && sandwich.hasTarget()) return;
		List<ServerPlayer> candidates = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (player.isAlive() && player.gameMode().isSurvival() && !player.hasEffect(Mobs.SPELL) && area.contains(CharacterEntity.body(player).position())) candidates.add(player);
		}
		RandomSource random = level.getRandom();
		if (candidates.isEmpty() || random.nextInt(HUNT_ODDS) != 0) return;
		hunt(server, candidates.get(random.nextInt(candidates.size())));
	}

	/**
	 * Бутерброд выбирает {@code target} целью заражения. Цель должна быть в его локации; нет локации, цель вне её или Бутерброду негде
	 * появиться — false. Бутерброд вне локации (или его нет) появляется в случайном свободном месте локации.
	 */
	public static boolean hunt(MinecraftServer server, ServerPlayer target) {
		Mechanics.Area area = Mechanics.place(server, place(server));
		ServerLevel level = area == null ? null : server.getLevel(area.dimension());
		if (level == null || target.level() != level || !target.isAlive() || target.isSpectator() || !area.contains(CharacterEntity.body(target).position())) return false;
		SandwichEntity sandwich = find(server);
		if (sandwich == null || sandwich.level() != level || !area.contains(sandwich.position())) {
			sandwich = spawn(level, area, target);
			if (sandwich == null) return false;
		}
		sandwich.setTarget(target);
		return true;
	}

	/** Новый Бутерброд в случайном свободном месте локации (прежний исчезает, {@link #onLoad}); {@code null} — места не нашлось. */
	public static @Nullable SandwichEntity spawn(ServerLevel level, Mechanics.Area area, ServerPlayer near) {
		SandwichEntity sandwich = Mobs.SANDWICH.create(level, EntitySpawnReason.TRIGGERED);
		if (sandwich == null) return null;
		Vec3 spot = randomSpot(level, area, near, sandwich);
		if (spot == null) return null;
		LOGGER.info("Бутерброд: появляется в локации в {} (рядом с {})", spot, near.getName().getString());
		sandwich.snapTo(spot.x, spot.y, spot.z, level.getRandom().nextFloat() * 360f, 0f);
		return level.addFreshEntity(sandwich) ? sandwich : null;
	}

	/**
	 * Случайная точка локации, где Бутерброд помещается (не в блоках): над поверхностью на высоте парения, поверхность ищется около высоты
	 * игрока (у области высоты нет), не ближе {@link #SPOT_AWAY} к нему. {@code null} — за {@link #SPOT_TRIES} попыток не нашлось.
	 */
	private static @Nullable Vec3 randomSpot(ServerLevel level, Mechanics.Area area, ServerPlayer near, SandwichEntity sandwich) {
		double[] bounds = area.boundsXZ();
		RandomSource random = level.getRandom();
		Vec3 player = CharacterEntity.body(near).position();
		AABB size = sandwich.getType().getDimensions().makeBoundingBox(0, 0, 0);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < SPOT_TRIES; i++) {
			double x = Mth.lerp(random.nextDouble(), bounds[0], bounds[2]), z = Mth.lerp(random.nextDouble(), bounds[1], bounds[3]);
			// под конец поиска годится и место рядом с игроком
			if (!area.contains(new Vec3(x, player.y, z)) || (i < SPOT_TRIES * 3 / 4 && Math.hypot(x - player.x, z - player.z) < SPOT_AWAY)) continue;
			pos.set(Mth.floor(x), Mth.floor(player.y), Mth.floor(z));
			if (!level.isLoaded(pos)) continue;
			for (int y = Mth.floor(player.y) + SPOT_UP; y >= Mth.floor(player.y) - SPOT_DOWN && y >= level.getMinY(); y--) {
				pos.setY(y);
				VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
				if (shape.isEmpty()) continue;
				Vec3 spot = new Vec3(x, y + shape.max(Direction.Axis.Y) + SandwichEntity.HOVER, z);
				if (level.noCollision(size.move(spot)) && !level.containsAnyLiquid(size.move(spot))) return spot;
			}
		}
		return null;
	}
}
