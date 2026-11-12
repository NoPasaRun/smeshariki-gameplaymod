package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.character.CharacterChoice;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Possession;
import com.smeshariki.ui.Ui;
import com.smeshariki.ui.UiScreen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;

/** Выбор персонажа для вселения в режиме выживания. Закрыть без выбора нельзя; закрывается само, если выбирать больше не нужно. */
final class CharacterChoiceScreen extends UiScreen {
	private static final int CARD_W = 80, CARD_H = 110, GAP = 8;

	private final List<Integer> allowed;
	private final List<CharacterEntity> previews = new ArrayList<>();

	CharacterChoiceScreen(List<Integer> allowed) {
		super(Component.translatable("gui.gameplaymod.choose_character"));
		this.allowed = allowed;
	}

	boolean shows(List<Integer> list) {
		return allowed.equals(list);
	}

	private int cardX(int index) {
		int count = allowed.size();
		return (width - count * CARD_W - (count - 1) * GAP) / 2 + index * (CARD_W + GAP);
	}

	private int cardY() {
		return (height - CARD_H) / 2;
	}

	@Override
	protected void init() {
		previews.clear();
		for (int i = 0; i < allowed.size(); i++) {
			int index = allowed.get(i);
			var type = Possession.choices().get(index);
			previews.add(type.create(minecraft.level, EntitySpawnReason.LOAD));
			addRenderableWidget(Button.builder(type.getDescription(), b -> {
				ClientPlayNetworking.send(new CharacterChoice(index));
				onClose();
			}).bounds(cardX(i), cardY() + CARD_H - 20, CARD_W, 20).build());
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		if (minecraft.player == null || minecraft.player.getVehicle() instanceof CharacterEntity || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL) onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, cardY() - 16, Ui.TEXT);
		for (int i = 0; i < previews.size(); i++) {
			CharacterEntity character = previews.get(i);
			if (character == null) continue;
			int x = cardX(i), y = cardY();
			float f = (float) Math.atan((x + CARD_W / 2f - mouseX) / 40.0);
			CharacterRenderer.beginPreview(180f + f * 20f, 180f + f * 40f);
			try {
				InventoryScreen.extractEntityInInventoryFollowsMouse(g, x, y, x + CARD_W, y + CARD_H - 24, 32, 0.0625f, mouseX, mouseY, character);
			} finally {
				CharacterRenderer.endPreview();
			}
		}
	}
}
