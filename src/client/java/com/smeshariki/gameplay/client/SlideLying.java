package com.smeshariki.gameplay.client;

/** Дополнение состояния отрисовки игрока: лежит на скате. Наклон NaN — не лежит. */
public interface SlideLying {
	float gameplaymod$tilt();

	float gameplaymod$drop();

	void gameplaymod$set(float tilt, float drop);
}
