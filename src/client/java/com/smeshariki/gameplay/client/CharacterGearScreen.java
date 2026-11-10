package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.CharacterMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * Окно инвентаря вселённого игрока (клавиша E): слева слоты одежды персонажа (голова, глаза, руки, ноги) с подписями, справа
 * сам персонаж, внизу обычный инвентарь и панель быстрого доступа.
 */
public final class CharacterGearScreen extends AbstractContainerScreen<CharacterMenu> {
	private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
	private static final Component[] LABELS = { Component.translatable("gui.gameplaymod.gear.head"), Component.translatable("gui.gameplaymod.gear.eyes"),
			Component.translatable("gui.gameplaymod.gear.arms"), Component.translatable("gui.gameplaymod.gear.legs") };

	public CharacterGearScreen(CharacterMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 184);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		int x = this.leftPos, y = this.topPos, w = this.imageWidth, h = this.imageHeight;
		// панель в стиле ванильных окон
		g.fill(x, y, x + w, y + h, 0xFF000000);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
		g.fill(x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
		g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
		for (Slot slot : this.menu.slots) g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, x + slot.x - 1, y + slot.y - 1, 18, 18);
		for (int i = 0; i < LABELS.length; i++) g.text(this.font, LABELS[i], x + 30, y + 18 + i * 18 + 5, 0xFF404040, false);
		if (this.minecraft.player != null && this.minecraft.player.getVehicle() instanceof CharacterEntity character) {
			// как в ванили: лицом к зрителю (180°), следит за мышью
			float f = (float) Math.atan((x + 133 - mouseX) / 40.0);
			CharacterRenderer.beginPreview(180f + f * 20f, 180f + f * 40f);
			try {
				InventoryScreen.extractEntityInInventoryFollowsMouse(g, x + 98, y + 14, x + 168, y + 78, 22, 0.0625f, mouseX, mouseY, character);
			} finally {
				CharacterRenderer.endPreview();
			}
		}
	}
}
