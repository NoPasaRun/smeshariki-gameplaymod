package com.smeshariki.gameplay.character;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Инвентарь вселённого игрока: четыре слота одежды персонажа (голова, глаза, руки, ноги — {@link CharacterEntity#GEAR_HEAD} и
 * далее) и обычный инвентарь игрока (27 слотов и 9 на панели быстрого доступа), как в ванильном окне.
 */
public class CharacterMenu extends AbstractContainerMenu {
	public static final int GEAR_SLOTS = 4;

	/** Клиентская сторона: содержимое слотов одежды придёт от сервера. */
	public CharacterMenu(int id, Inventory inventory) {
		this(id, inventory, new SimpleContainer(GEAR_SLOTS));
	}

	public CharacterMenu(int id, Inventory inventory, Container gear) {
		super(Characters.GEAR_MENU, id);
		for (int i = 0; i < GEAR_SLOTS; i++) {
			int gearSlot = i;
			addSlot(new Slot(gear, i, 8, 18 + i * 18) {
				@Override
				public int getMaxStackSize() {
					return 1;
				}

				/** Только аксессуар, рассчитанный на этот слот. */
				@Override
				public boolean mayPlace(ItemStack stack) {
					return stack.getItem() instanceof AccessoryItem accessory && accessory.slot() == gearSlot;
				}
			});
		}
		addStandardInventorySlots(inventory, 8, 102);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		// из слота одежды — в инвентарь, из инвентаря — в свободный слот одежды
		if (index < GEAR_SLOTS ? !moveItemStackTo(stack, GEAR_SLOTS, this.slots.size(), true) : !moveItemStackTo(stack, 0, GEAR_SLOTS, false)) return ItemStack.EMPTY;
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		return CharacterEntity.isPossessing(player) && player.getVehicle().isAlive();
	}
}
