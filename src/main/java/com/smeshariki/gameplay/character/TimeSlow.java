package com.smeshariki.gameplay.character;

import com.smeshariki.gameplay.raft.RaftEntity;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Замедление времени Бараша (клавиша G, тратится вся мана; чем больше маны, тем дольше и шире). Пока оно идёт, все существа в радиусе
 * {@link #RADIUS} × мана (при полной мане 10 блоков, при {@link #MIN_MANA} — 2) от Бараша, кроме игроков, плотов
 * и самого Бараша (и сидящего в нём игрока), делают тик не каждый, а раз в {@link #DIVISOR} тиков: ходят, летят, падают, горят и стареют втрое
 * медленнее. Тик пропускают миксины {@code ServerLevelSlowMixin} (сервер) и {@code ClientLevelSlowMixin} (клиент: только существа, чьё движение
 * клиент считает сам — снаряды, предметы; живых двигает сервер, клиент лишь сглаживает). Частицы не замедляются (область ограничена, а движок частиц один на весь мир).
 * <b>Не замедляется</b>: блоки, жидкости, погода, время суток, другие игроки (их движение считает их клиент), плоты (несут стоящих).
 */
public final class TimeSlow {
	/** Во сколько раз реже тикают замедленные. */
	public static final int DIVISOR = 3;
	/** Сколько тиков длится замедление при полной мане; меньше маны — пропорционально меньше. */
	public static final int MAX_TICKS = 400;
	/** Меньше этого запаса маны замедлить нельзя. */
	public static final float MIN_MANA = 0.2f;
	/** Прирост маны за тик: полная за 90 с. */
	public static final float MANA_REGEN = 1f / 1800;

	/** Радиус области замедления при полной мане, блоков; радиус задаётся при применении и до конца не меняется. */
	public static final float RADIUS = 10f;

	/** Сервер: чьё замедление идёт и когда кончится (тик сервера). */
	private static CharacterEntity owner;
	private static int endTick;
	private static float radius;
	/** Клиент: номер сущности Бараша, чьё замедление идёт, или −1. */
	private static int clientOwner = -1;
	private static float clientRadius;

	private TimeSlow() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(TimeSlowState.TYPE, TimeSlowState.CODEC);
		ServerTickEvents.END_SERVER_TICK.register(TimeSlow::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (owner != null) ServerPlayNetworking.send(handler.getPlayer(), new TimeSlowState(owner.getId(), radius));
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> owner = null);
	}

	/** Сервер: идёт ли замедление этого Бараша (для восстановления маны). */
	public static boolean isOwner(CharacterEntity c) {
		return owner == c;
	}

	/** Клиент получил состояние от сервера. */
	public static void setClient(int ownerId, float areaRadius) {
		clientOwner = ownerId;
		clientRadius = areaRadius;
	}

	/** Идёт ли замедление (для подписи на экране). */
	public static boolean activeClient() {
		return clientOwner >= 0;
	}

	/** Пропустить ли тик этой сущности: идёт замедление, она не из неприкосновенных и сейчас не её тик. */
	public static boolean skips(Entity entity) {
		boolean client = entity.level().isClientSide();
		int id = client ? clientOwner : owner == null ? -1 : owner.getId();
		if (id < 0) return false;
		if (client && entity instanceof LivingEntity) return false;
		if (entity instanceof Player || entity instanceof RaftEntity || entity.getId() == id || entity.getRootVehicle().getId() == id) return false;
		if (entity.level().getGameTime() % DIVISOR == 0) return false;
		for (Entity passenger : entity.getPassengers()) if (passenger instanceof Player) return false;
		// область — шар вокруг Бараша (в другом измерении или незагруженного его нет — не замедляет)
		Entity center = entity.level().getEntity(id);
		float r = client ? clientRadius : radius;
		return center != null && entity.distanceToSqr(center) <= r * r;
	}

	/** Применить: вся мана уходит на замедление. */
	public static void cast(ServerPlayer player, CharacterEntity barash) {
		if (owner != null) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.time_slow.busy"));
			return;
		}
		if (barash.mana() < MIN_MANA) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.time_slow.no_mana"));
			return;
		}
		MinecraftServer server = player.level().getServer();
		owner = barash;
		radius = RADIUS * barash.mana();
		endTick = server.getTickCount() + Math.max(1, Math.round(barash.mana() * MAX_TICKS));
		broadcast(server, barash.getId(), radius);
		barash.level().playSound(null, barash.blockPosition(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.5f, 0.6f);
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.time_slow.start", (endTick - server.getTickCount()) / 20, Math.round(radius)));
	}

	private static void tick(MinecraftServer server) {
		if (owner == null) return;
		int left = endTick - server.getTickCount();
		if (owner.isRemoved() || !owner.isAlive() || left <= 0) {
			if (!owner.isRemoved()) {
				owner.setMana(0f);
				owner.level().playSound(null, owner.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.5f, 1.4f);
			}
			owner = null;
			broadcast(server, -1, 0f);
			return;
		}
		owner.setMana(left / (float) MAX_TICKS);
	}

	private static void broadcast(MinecraftServer server, int ownerId, float areaRadius) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) ServerPlayNetworking.send(p, new TimeSlowState(ownerId, areaRadius));
	}
}
