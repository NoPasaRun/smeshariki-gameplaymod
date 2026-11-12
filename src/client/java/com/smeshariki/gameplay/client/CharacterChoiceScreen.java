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
	private static final int CARD_W = 80, CARD_H = 110, GAP = 8, EDGE = 8, TOP = 28, BUTTON_H = 20;
	private static final int PREVIEW_SCALE = 32;

	private final List<Integer> allowed;
	private final List<CharacterEntity> previews = new ArrayList<>();
	/** Сетка карточек, пересчитывается при каждом изменении размера окна: столбцов, размер карточки, верх сетки, масштаб (1 — полный размер). */
	private int cols = 1, cardW = CARD_W, cardH = CARD_H, gridTop = TOP;
	private float cardScale = 1f;

	CharacterChoiceScreen(List<Integer> allowed) {
		super(Component.translatable("gui.gameplaymod.choose_character"));
		this.allowed = allowed;
	}

	boolean shows(List<Integer> list) {
		return allowed.equals(list);
	}

	/**
	 * Раскладывает карточки сеткой: из всех разбиений на строки берётся то, где карточки получаются крупнее (но не больше {@link #CARD_W}×{@link #CARD_H}),
	 * при равенстве — с меньшим числом строк. Неполная последняя строка стоит по центру.
	 */
	private void layoutGrid() {
		int n = Math.max(1, allowed.size());
		float best = -1f;
		for (int c = n; c >= 1; c--) { // от одной строки к столбцу: при равных размерах выигрывает меньше строк
			int rows = (n + c - 1) / c;
			float w = (width - 2f * EDGE - (c - 1) * GAP) / c, h = (height - TOP - EDGE - (rows - 1) * GAP) / (float) rows;
			float scale = Math.min(1f, Math.min(w / CARD_W, h / CARD_H));
			// крупнее — лучше; при равенстве меньше строк, а при равных строках — меньше столбцов (строки ровнее: 4+3, а не 5+2)
			int bestRows = (n + cols - 1) / cols;
			if (scale > best + 1e-4f || Math.abs(scale - best) <= 1e-4f && rows == bestRows && c < cols) {
				best = scale;
				cols = c;
			}
		}
		cardScale = Math.max(0.3f, best);
		cardW = Math.round(CARD_W * cardScale);
		cardH = Math.round(CARD_H * cardScale);
		int rows = (n + cols - 1) / cols, total = rows * cardH + (rows - 1) * GAP;
		gridTop = Math.max(TOP, TOP + (height - TOP - EDGE - total) / 2);
	}

	private int cardX(int index) {
		int row = index / cols, inRow = Math.min(cols, allowed.size() - row * cols);
		return (width - inRow * cardW - (inRow - 1) * GAP) / 2 + (index % cols) * (cardW + GAP);
	}

	private int cardY(int index) {
		return gridTop + (index / cols) * (cardH + GAP);
	}

	@Override
	protected void init() {
		previews.clear();
		layoutGrid();
		for (int i = 0; i < allowed.size(); i++) {
			int index = allowed.get(i);
			var type = Possession.choices().get(index);
			previews.add(type.create(minecraft.level, EntitySpawnReason.LOAD));
			addRenderableWidget(Button.builder(type.getDescription(), b -> {
				ClientPlayNetworking.send(new CharacterChoice(index));
				onClose();
			}).bounds(cardX(i), cardY(i) + cardH - BUTTON_H, cardW, BUTTON_H).build());
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
		g.centeredText(font, title, width / 2, Math.max(4, gridTop - 16), Ui.TEXT);
		for (int i = 0; i < previews.size(); i++) {
			CharacterEntity character = previews.get(i);
			if (character == null) continue;
			int x = cardX(i), y = cardY(i);
			float f = (float) Math.atan((x + cardW / 2f - mouseX) / 40.0);
			CharacterRenderer.beginPreview(180f + f * 20f, 180f + f * 40f);
			try {
				InventoryScreen.extractEntityInInventoryFollowsMouse(g, x, y, x + cardW, y + cardH - BUTTON_H - 4, Math.max(8, Math.round(PREVIEW_SCALE * cardScale)), 0.0625f, mouseX, mouseY, character);
			} finally {
				CharacterRenderer.endPreview();
			}
		}
	}
}
