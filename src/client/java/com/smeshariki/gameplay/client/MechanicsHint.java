package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Кнопка «Механика предмета» у правого края экрана (клавиша {@link GameplayModClient#MECHANICS}): видна, пока у предмета, который игрок использует,
 * есть описание ({@link ItemMechanics}). Клавиша открывает {@link MechanicsScreen}; без описания пишет об этом над панелью.
 */
final class MechanicsHint {
	private static final int MARGIN = 6, BUTTON = 0xB0141922, KEY = 0xFF3B4A5F, LABEL = 0xFFD0D6DE;

	private static boolean available;

	private MechanicsHint() {
	}

	static void init() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "mechanics_hint"), MechanicsHint::extract);
		// начало тика, до обработки клавиш в игре: на O по умолчанию сидит ещё и ванильное «Друзья», его нажатие надо успеть убрать
		ClientTickEvents.START_CLIENT_TICK.register(MechanicsHint::keys);
	}

	private static void keys(Minecraft mc) {
		if (mc.player == null) return;
		boolean pressed = false;
		while (GameplayModClient.MECHANICS.consumeClick()) pressed = true;
		if (!pressed || mc.gui.screen() != null) return;
		ItemMechanics.Info info = ItemMechanics.current(mc);
		if (info == null) {
			mc.player.sendOverlayMessage(Component.translatable("message.gameplaymod.mechanics_none"));
			return;
		}
		if (mc.options.keyFriends.same(GameplayModClient.MECHANICS)) while (mc.options.keyFriends.consumeClick()) ;
		mc.gui.setScreen(new MechanicsScreen(info));
	}

	/** Раз в конец тика клиента: показывать ли кнопку (есть ли описание у используемого предмета). */
	static void tick(Minecraft mc) {
		available = mc.player != null && mc.gui.screen() == null && ItemMechanics.current(mc) != null;
	}

	private static void rounded(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0 + 1, y0, x1 - 1, y1, color);
		g.fill(x0, y0 + 1, x1, y1 - 1, color);
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!available) return;
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		Component key = GameplayModClient.MECHANICS.getTranslatedKeyMessage();
		Component label = Component.translatable("gui.gameplaymod.mechanics.button");
		int keyW = font.width(key) + 8, labelW = font.width(label), h = 15;
		int w = keyW + labelW + 14, x = g.guiWidth() - MARGIN - w, y = g.guiHeight() / 2 - h / 2;
		rounded(g, x, y, x + w, y + h, BUTTON);
		rounded(g, x + 2, y + 2, x + 2 + keyW, y + h - 2, KEY);
		g.text(font, key, x + 6, y + 4, 0xFFFFFFFF);
		g.text(font, label, x + keyW + 8, y + 4, LABEL);
	}
}
