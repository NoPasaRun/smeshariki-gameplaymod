package com.smeshariki.gameplay.raft;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Маршруты брёвен мира, файл {@code data/gameplaymod/log_routes.dat}: лежит среди файлов мира, поэтому уходит в версии мира. Пишет его
 * {@code SmesharikiWorldMod/tools/log_route.py} при закрытой игре, мод только читает. Клиенту маршруты приходят пакетом при входе: путь
 * бревна он считает сам.
 */
public final class LogRoutes extends SavedData {
	/** Ряд потока в данных мира: см. {@link LogRoute.Flow}. */
	public record LaneEntry(float center, float phase, float shift, float sway) {
		static final Codec<LaneEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.FLOAT.optionalFieldOf("center", 0f).forGetter(LaneEntry::center),
				Codec.FLOAT.optionalFieldOf("phase", 0f).forGetter(LaneEntry::phase),
				Codec.FLOAT.optionalFieldOf("shift", 0f).forGetter(LaneEntry::shift),
				Codec.FLOAT.optionalFieldOf("sway", 0f).forGetter(LaneEntry::sway)
		).apply(i, LaneEntry::new));
	}

	public record Entry(String name, List<Float> nodes, float wavelength, int gapMin, int gapMax, List<LaneEntry> lanes) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("name").forGetter(Entry::name),
				Codec.FLOAT.listOf().fieldOf("nodes").forGetter(Entry::nodes),
				Codec.FLOAT.optionalFieldOf("wavelength", LogRoute.Flow.DEFAULT.wavelength()).forGetter(Entry::wavelength),
				Codec.INT.optionalFieldOf("gap_min", LogRoute.Flow.DEFAULT.gapMin()).forGetter(Entry::gapMin),
				Codec.INT.optionalFieldOf("gap_max", LogRoute.Flow.DEFAULT.gapMax()).forGetter(Entry::gapMax),
				LaneEntry.CODEC.listOf().optionalFieldOf("lanes", List.of()).forGetter(Entry::lanes)
		).apply(i, Entry::new));

		LogRoute.Flow flow() {
			return new LogRoute.Flow(wavelength, gapMin, gapMax, lanes.stream().map(l -> new LogRoute.Flow.Lane(l.center(), l.phase(), l.shift(), l.sway())).toList());
		}
	}

	private static final Codec<LogRoutes> CODEC = RecordCodecBuilder.create(i -> i.group(
			Entry.CODEC.listOf().optionalFieldOf("routes", List.of()).forGetter(r -> r.entries)
	).apply(i, LogRoutes::new));
	// без типа датафикса ваниль падает при чтении файла; исправлять в наших данных нечего
	private static final SavedDataType<LogRoutes> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "log_routes"), LogRoutes::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** Маршруты для клиента, по номеру. */
	public record Sync(List<List<Float>> routes) implements CustomPacketPayload {
		public static final Type<Sync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "log_routes"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list()), Sync::routes, Sync::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private final List<Entry> entries;
	/** Кэш сервера и клиента отдельно: в одиночной игре они в одном процессе. */
	private static volatile List<LogRoute> server = List.of(), client = List.of();

	private LogRoutes() {
		this(List.of());
	}

	private LogRoutes(List<Entry> entries) {
		this.entries = new ArrayList<>(entries);
	}

	static void init() {
		PayloadTypeRegistry.clientboundPlay().register(Sync.TYPE, Sync.CODEC);
		ServerLifecycleEvents.SERVER_STARTED.register(LogRoutes::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = List.of());
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> ServerPlayNetworking.send(handler.player, sync()));
	}

	private static void load(MinecraftServer s) {
		List<LogRoute> routes = new ArrayList<>();
		for (Entry e : s.getDataStorage().computeIfAbsent(TYPE).entries) routes.add(new LogRoute(toArray(e.nodes()), e.flow()));
		server = List.copyOf(routes);
		LogRiver.rebuild(server);
	}

	private static Sync sync() {
		List<List<Float>> out = new ArrayList<>();
		for (LogRoute route : server) {
			List<Float> nodes = new ArrayList<>();
			for (float f : route.nodesArray()) nodes.add(f);
			out.add(nodes);
		}
		return new Sync(out);
	}

	/** Клиент: пришли маршруты мира. */
	public static void receive(Sync sync) {
		List<LogRoute> routes = new ArrayList<>();
		for (List<Float> nodes : sync.routes()) routes.add(new LogRoute(toArray(nodes)));
		client = List.copyOf(routes);
	}

	public static void clearClient() {
		client = List.of();
	}

	static List<LogRoute> server() {
		return server;
	}

	/** Маршрут по номеру на этой стороне, {@code null} — ещё не пришёл или его нет. */
	static @Nullable LogRoute get(Level level, int index) {
		List<LogRoute> routes = level.isClientSide() ? client : server;
		return index >= 0 && index < routes.size() ? routes.get(index) : null;
	}

	private static float[] toArray(List<Float> list) {
		float[] out = new float[list.size() - list.size() % 4];
		for (int i = 0; i < out.length; i++) out[i] = list.get(i);
		return out;
	}
}
