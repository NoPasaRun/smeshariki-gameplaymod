package com.smeshariki.gameplay.character;

import net.minecraft.world.item.Item;

/** Аксессуар для персонажа-шарика: надевается только в свой слот одежды ({@link CharacterEntity#GEAR_HEAD} и далее). */
public class AccessoryItem extends Item {
	private final int slot;

	public AccessoryItem(Properties properties, int slot) {
		super(properties);
		this.slot = slot;
	}

	/** В какой слот одежды (номер из {@code CharacterEntity.GEAR_*}) надевается. */
	public int slot() {
		return this.slot;
	}
}
