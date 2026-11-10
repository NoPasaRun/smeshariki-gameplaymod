package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Окно «Описание механики» (клавиша {@link GameplayModClient#MECHANICS}): карточка с предметом, который игрок использует, — название, краткое пояснение
 * и разделы «Как пользоваться», «Управление», «Особенности» ({@link ItemMechanics}). Высокий текст прокручивается колесом мыши. Мир за окном не замирает.
 */
final class MechanicsScreen extends Screen {
	private static final int PANEL_MAX_W = 430, MARGIN = 12, PAD = 16, LINE = 11, HEADER_H = 62, FOOT_H = 34, SCROLLBAR_W = 4;
	private static final int PANEL = 0xF2141922, PANEL_EDGE = 0xFF334155, HEADER = 0xFF1C2431, GOLD = 0xFFFFC857, TITLE = 0xFFFFFFFF, MUTED = 0xFF8FA0B5;
	private static final int TEXT = 0xFFE3E8EF, SUMMARY_BG = 0xFF1E2A3A, SUMMARY_BAR = 0xFF4FA3FF, SLOT = 0xFF0E1218, SLOT_EDGE = 0xFF3C4A5E, TRACK = 0xFF232C39, THUMB = 0xFF5B6B80;

	/** Строка прокручиваемого текста. */
	private sealed interface Row permits Heading, Bullet, Summary, Gap {
		int height();
	}

	private record Heading(Component text) implements Row {
		public int height() {
			return LINE + 8;
		}
	}

	private record Bullet(List<FormattedCharSequence> lines) implements Row {
		public int height() {
			return lines.size() * LINE + 3;
		}
	}

	private record Summary(List<FormattedCharSequence> lines) implements Row {
		public int height() {
			return lines.size() * LINE + 12;
		}
	}

	private record Gap(int height) implements Row {
	}

	private final ItemMechanics.Info info;
	private final List<Row> rows = new ArrayList<>();
	private int contentHeight, scroll, panelX, panelY, panelW, panelH, bodyTop, bodyBottom;

	MechanicsScreen(ItemMechanics.Info info) {
		super(Component.translatable("gui.gameplaymod.mechanics.title"));
		this.info = info;
	}

	@Override
	protected void init() {
		panelW = Math.min(PANEL_MAX_W, width - MARGIN * 2);
		int textW = panelW - PAD * 2 - SCROLLBAR_W - 4;
		rows.clear();
		rows.add(new Summary(font.split(info.summary(), textW - 12)));
		for (ItemMechanics.Section section : info.sections()) {
			rows.add(new Gap(4));
			rows.add(new Heading(section.title()));
			for (Component line : section.lines()) rows.add(new Bullet(font.split(line, textW - 12)));
		}
		contentHeight = 0;
		for (Row row : rows) contentHeight += row.height();
		panelH = Math.min(height - MARGIN * 2, HEADER_H + contentHeight + 12 + FOOT_H);
		panelX = (width - panelW) / 2;
		panelY = (height - panelH) / 2;
		bodyTop = panelY + HEADER_H + 6;
		bodyBottom = panelY + panelH - FOOT_H;
		scroll = Math.clamp(scroll, 0, maxScroll());
		addRenderableWidget(Button.builder(Component.translatable("gui.gameplaymod.mechanics.close"), b -> onClose())
				.bounds(panelX + (panelW - 110) / 2, panelY + panelH - FOOT_H + 7, 110, 20).build());
	}

	private int maxScroll() {
		return Math.max(0, contentHeight - (bodyBottom - bodyTop));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (GameplayModClient.MECHANICS.matches(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * LINE * 2, 0, maxScroll());
		return true;
	}

	/** Прямоугольник со срезанными углами. */
	private static void rounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y1, color);
		g.fill(x0, y0 + 1, x1, y1 - 1, color);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		int x = panelX, y = panelY, r = panelX + panelW, b = panelY + panelH;
		rounded(g, x - 1, y - 1, r + 1, b + 1, PANEL_EDGE);
		rounded(g, x, y, r, b, PANEL);
		// шапка с золотой полосой
		g.fill(x + 1, y + 1, r - 1, y + HEADER_H, HEADER);
		g.fill(x + 1, y + 1, r - 1, y + 3, GOLD);
		g.fill(x + 1, y + HEADER_H, r - 1, y + HEADER_H + 1, PANEL_EDGE);
		// предмет в рамке, увеличенный вдвое
		int slot = 40, sx = x + PAD, sy = y + (HEADER_H - slot) / 2 + 1;
		rounded(g, sx - 1, sy - 1, sx + slot + 1, sy + slot + 1, SLOT_EDGE);
		rounded(g, sx, sy, sx + slot, sy + slot, SLOT);
		g.pose().pushMatrix();
		g.pose().translate(sx + (slot - 32) / 2f, sy + (slot - 32) / 2f);
		g.pose().scale(2f, 2f);
		g.fakeItem(info.stack(), 0, 0);
		g.pose().popMatrix();
		int tx = sx + slot + 12, ty = y + 17;
		g.text(font, Component.translatable("gui.gameplaymod.mechanics.label"), tx, ty, MUTED);
		g.pose().pushMatrix();
		g.pose().translate(tx, ty + LINE + 2);
		g.pose().scale(1.5f, 1.5f);
		g.text(font, info.name().copy().withStyle(ChatFormatting.BOLD), 0, 0, TITLE);
		g.pose().popMatrix();
		// прокручиваемое тело
		int textX = x + PAD;
		int textW = panelW - PAD * 2 - SCROLLBAR_W - 4;
		g.enableScissor(x + 1, bodyTop, r - 1, bodyBottom);
		int cy = bodyTop - scroll;
		for (Row row : rows) {
			int h = row.height();
			if (cy + h >= bodyTop && cy <= bodyBottom) drawRow(g, row, textX, cy, textW);
			cy += h;
		}
		g.disableScissor();
		// полоса прокрутки
		int max = maxScroll();
		if (max > 0) {
			int track = bodyBottom - bodyTop, thumb = Math.max(18, track * track / contentHeight);
			int bx = r - PAD / 2 - SCROLLBAR_W / 2 - 2, by = bodyTop + (track - thumb) * scroll / max;
			g.fill(bx, bodyTop, bx + SCROLLBAR_W, bodyBottom, TRACK);
			g.fill(bx, by, bx + SCROLLBAR_W, by + thumb, THUMB);
		}
		g.fill(x + 1, bodyBottom, r - 1, bodyBottom + 1, PANEL_EDGE);
	}

	private void drawRow(GuiGraphicsExtractor g, Row row, int x, int y, int w) {
		switch (row) {
			case Summary summary -> {
				g.fill(x, y, x + w, y + row.height() - 4, SUMMARY_BG);
				g.fill(x, y, x + 2, y + row.height() - 4, SUMMARY_BAR);
				for (int i = 0; i < summary.lines().size(); i++) g.text(font, summary.lines().get(i), x + 9, y + 4 + i * LINE, TEXT);
			}
			case Heading heading -> {
				g.fill(x, y + 4, x + 3, y + 4 + LINE - 3, GOLD);
				g.text(font, heading.text().copy().withStyle(ChatFormatting.BOLD), x + 8, y + 3, GOLD);
				g.fill(x + 8 + font.width(heading.text()) + 8, y + 3 + LINE / 2, x + w, y + 4 + LINE / 2, PANEL_EDGE);
			}
			case Bullet bullet -> {
				g.text(font, "•", x + 2, y, GOLD);
				for (int i = 0; i < bullet.lines().size(); i++) g.text(font, bullet.lines().get(i), x + 12, y + i * LINE, TEXT);
			}
			case Gap gap -> {
			}
		}
	}
}
