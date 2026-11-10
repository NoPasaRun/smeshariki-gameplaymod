package com.smeshariki.gameplay.raft;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Стропа управления шаром в инвентаре: ПКМ по шару вешает на него {@link BalloonRopeEntity}, второй конец остаётся в руке.
 * Запас топлива стропы — её прочность: полоска показывает остаток, снятая стропа возвращает тот запас, что был.
 */
public class BalloonRopeItem extends Item {
	public enum Kind { LIFT, HELM }

	/** Полный запас: столько щелчков колеса. */
	public static final int MAX_FUEL = 256;

	public static int fuel(ItemStack stack) {
		return stack.getMaxDamage() - stack.getDamageValue();
	}

	public static ItemStack withFuel(ItemStack stack, int fuel) {
		stack.setDamageValue(stack.getMaxDamage() - Math.clamp(fuel, 0, stack.getMaxDamage()));
		return stack;
	}

	private final Kind kind;

	public BalloonRopeItem(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}
}
