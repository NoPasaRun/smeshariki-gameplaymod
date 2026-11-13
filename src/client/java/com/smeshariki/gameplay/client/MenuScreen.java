package com.smeshariki.gameplay.client;

import java.util.List;

import com.smeshariki.ui.UiScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Простое меню из кнопок в несколько колонок (сколько влезет по высоте): окна умений Лосяша и Нюши. */
final class MenuScreen extends UiScreen {
	record Entry(Component label, Runnable run) {
	}

	private static final int ROW = 22, TOP = 34, GAP = 6, MAX_W = 160;

	private final List<Entry> entries;

	MenuScreen(Component title, Screen parent, List<Entry> entries) {
		super(title, parent);
		this.entries = entries;
	}

	@Override
	protected void init() {
		int perColumn = Math.max(1, (height - TOP - 34) / ROW);
		int columns = Math.max(1, (entries.size() + perColumn - 1) / perColumn);
		int w = Math.min(MAX_W, (width - 16 - (columns - 1) * GAP) / columns);
		int x0 = (width - (columns * w + (columns - 1) * GAP)) / 2;
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			addButton(entry.label(), x0 + (i / perColumn) * (w + GAP), TOP + (i % perColumn) * ROW, w, entry.run());
		}
		addBack(width / 2 - 40, height - 26, 80);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawHeader(g, null);
	}
}
