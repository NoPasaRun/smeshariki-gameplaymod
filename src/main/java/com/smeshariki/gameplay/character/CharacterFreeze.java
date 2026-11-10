package com.smeshariki.gameplay.character;

/**
 * Вселённый персонаж на этом клиенте не может двигаться (ждёт после сброса задачи); камеру игрок крутит по-прежнему. Движение вселённого считает его
 * собственный клиент, поэтому состояние держит клиент — тот, кто ждёт (LevelMod ведёт отсчёт и ставит его по пакету). Сервер и чужие клиенты его не видят.
 */
public final class CharacterFreeze {
	private static volatile boolean frozen;

	private CharacterFreeze() {
	}

	public static void set(boolean value) {
		frozen = value;
	}

	public static boolean frozen() {
		return frozen;
	}
}
