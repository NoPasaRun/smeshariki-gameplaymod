package com.smeshariki.gameplay.character;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;

/**
 * Вселение обязательно в режиме выживания: игрока без персонажа сервер раз в полсекунды просит выбрать его (клиент держит окно без
 * выхода), поэтому не важно, как он оказался без персонажа: сменил режим, вошёл в мир, возродился или потерял персонажа.
 * <p>
 * Смешарик в мире один ({@link Singletons}): выбор переносит игрока туда, где смешарик стоит, и вселяет; прежнее тело остаётся на месте и
 * гуляет. Занятого (другим игроком, скриптом) выбрать нельзя. Нет смешарика в мире — появляется рядом с игроком. Уровень может выбрать за
 * игрока сам ({@link #setAutoChoice}): тогда смешарик приходит к игроку (после гибели в уровне персонаж возвращается к началу задачи вместе с ним).
 */
public final class Possession {
	private static final int ASK_INTERVAL = 10;

	/** Какие персонажи сейчас доступны игроку (например, только разрешённые его уровнем). */
	@FunctionalInterface
	public interface ChoiceFilter {
		boolean allows(ServerPlayer player, EntityType<CharacterEntity> type);
	}

	/** Выбор за игрока: {@code null} — спросить его самого. Выбранный смешарик приходит к игроку. */
	@FunctionalInterface
	public interface AutoChoice {
		@Nullable EntityType<CharacterEntity> pick(ServerPlayer player);
	}

	private static ChoiceFilter filter = (player, type) -> true;
	private static AutoChoice autoChoice = player -> null;
	/** Чей выбор ждёт загрузки смешарика из незагруженного чанка: не спрашивать снова. */
	private static final Set<UUID> WAITING = new HashSet<>();

	private Possession() {
	}

	/** Кого можно выбрать. */
	public static List<EntityType<CharacterEntity>> choices() {
		return Characters.all();
	}

	/** Другие моды ограничивают выбор отсюда. */
	public static void setChoiceFilter(ChoiceFilter choiceFilter) {
		filter = choiceFilter;
	}

	public static void setAutoChoice(AutoChoice choice) {
		autoChoice = choice;
	}

	/** Номера доступных персонажей: разрешённые и не занятые. Если фильтр запретил всех, игрок всё равно должен кем-то стать: доступны все свободные. */
	private static List<Integer> allowed(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < choices().size(); i++) if (filter.allows(player, choices().get(i)) && !Singletons.busy(server, choices().get(i), player)) indices.add(i);
		if (!indices.isEmpty()) return indices;
		for (int i = 0; i < choices().size(); i++) if (!Singletons.busy(server, choices().get(i), player)) indices.add(i);
		return indices;
	}

	static void init() {
		PayloadTypeRegistry.clientboundPlay().register(ChooseCharacter.TYPE, ChooseCharacter.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CharacterChoice.TYPE, CharacterChoice.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(CharacterChoice.TYPE, (msg, ctx) -> choose(ctx.player(), msg.index()));
		ServerTickEvents.END_SERVER_TICK.register(Possession::tick);
	}

	private static boolean needsCharacter(ServerPlayer player) {
		return player.gameMode() == GameType.SURVIVAL && player.isAlive() && !CharacterEntity.isPossessing(player);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % ASK_INTERVAL != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!needsCharacter(player) || WAITING.contains(player.getUUID())) continue;
			EntityType<CharacterEntity> auto = autoChoice.pick(player);
			if (auto != null && !Singletons.busy(server, auto, player)) take(player, auto, true);
			else ServerPlayNetworking.send(player, new ChooseCharacter(allowed(player)));
		}
	}

	private static void choose(ServerPlayer player, int index) {
		if (!needsCharacter(player) || !allowed(player).contains(index)) return;
		take(player, choices().get(index), false);
	}

	/** Сменить персонажа в режиме выживания: выйти нельзя, поэтому сменой ведает тот, кто её разрешает. Прежний остаётся на месте со своей одеждой. */
	public static void swapTo(ServerPlayer player, EntityType<CharacterEntity> type) {
		if (player.gameMode() != GameType.SURVIVAL || player.getVehicle() instanceof CharacterEntity current && current.getType() == type) return;
		take(player, type, false);
	}

	/**
	 * Вселить игрока в смешарика {@code type}: {@code bring} — смешарик приходит к игроку, иначе игрок переносится к смешарику. Смешарика нет
	 * в мире — появляется рядом с игроком.
	 */
	private static void take(ServerPlayer player, EntityType<CharacterEntity> type, boolean bring) {
		MinecraftServer server = player.level().getServer();
		if (Singletons.busy(server, type, player)) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.character_busy", type.getDescription()));
			return;
		}
		WAITING.add(player.getUUID());
		Singletons.fetch(server, type, found -> {
			WAITING.remove(player.getUUID());
			if (player.isRemoved() || !player.isAlive()) return;
			Entity root = player.getRootVehicle();
			CharacterEntity body = found;
			if (body == null) {
				body = Singletons.create(player.level(), type, root.position(), player.getYRot());
			} else if (body.isScripted() || body.isVehicle() && !body.hasPassenger(player)) {
				player.sendOverlayMessage(Component.translatable("message.gameplaymod.character_busy", type.getDescription()));
				return;
			}
			if (body.hasPassenger(player)) return;
			// прежнее тело остаётся, где стоит, со своей одеждой
			player.stopRiding();
			if (bring) {
				Singletons.move(body, player.level(), player.position(), player.getYRot());
				body = Singletons.find(server, type);
				if (body == null) return;
			} else {
				ServerLevel to = (ServerLevel) body.level();
				player.teleportTo(to, body.getX(), body.getY(), body.getZ(), Set.of(), body.getYRot(), player.getXRot(), true);
			}
			body.possess(player);
		});
	}
}
