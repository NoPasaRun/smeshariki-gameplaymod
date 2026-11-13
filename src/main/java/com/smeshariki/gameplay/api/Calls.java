package com.smeshariki.gameplay.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Что Нюша может сделать со смешариком, которого позвала (клавиша G): выбрав смешарика, игрок выбирает действие, а у некоторых — ещё и вариант
 * (какой приказ). Реестр общий для обеих сторон: действия регистрируются при запуске мода (GameplayMod — «позвать к себе», BehaviorMod — «поговорить»
 * и «приказать»), клиент берёт из него списки для окна, сервер — исполнение.
 */
public final class Calls {
	public record Option(String id, Component label) {
	}

	public interface Action {
		/** Подпись действия в окне. */
		Component label();

		/** Варианты (какой приказ); пусто — действие без выбора. Одни и те же у клиента и сервера. */
		default List<Option> options() {
			return List.of();
		}

		/**
		 * Выполнить на сервере. {@code caller} — игрок в Нюше, {@code target} — вызванный смешарик (он уже в мире, не в игроке); {@code option} — id
		 * варианта (пусто, если вариантов нет). {@code null} — выполнено; иначе причина отказа (строка над панелью).
		 */
		@Nullable Component run(ServerPlayer caller, CharacterEntity target, String option);

		/** Нужно ли, чтобы смешарик уже был в мире (иначе его только создадут и перенесут, как делает «позвать»). */
		default boolean needsPresent() {
			return true;
		}
	}

	private static final Map<String, Action> ACTIONS = new LinkedHashMap<>();

	private Calls() {
	}

	public static void register(String id, Action action) {
		ACTIONS.put(id, action);
	}

	public static @Nullable Action get(String id) {
		return ACTIONS.get(id);
	}

	/** Действия в порядке регистрации. */
	public static Map<String, Action> all() {
		return Collections.unmodifiableMap(ACTIONS);
	}
}
