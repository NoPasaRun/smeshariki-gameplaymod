package com.smeshariki.gameplay.api;

/**
 * Сущность, которой можно задать группу: события и действия механик находят её по имени группы (поле {@code Group} в NBT). Окно групп LevelMod ставит и снимает
 * группу через этот интерфейс, не зная типов сущностей: новому виду достаточно его реализовать.
 */
public interface Grouped {
	/** Имя группы; пусто — без группы. */
	String group();

	void setGroup(String group);
}
