package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.raft.BalloonRopeEntity;
import com.smeshariki.gameplay.raft.BalloonRopeItem;
import com.smeshariki.gameplay.rope.CalmRopeEntity;
import com.smeshariki.gameplay.rope.PullRopeEntity;
import com.smeshariki.gameplay.rope.RopeEntity;
import com.smeshariki.gameplay.rope.Ropes;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Подсказка управления внизу экрана: первые {@link #SHOW_TICKS} (15 секунд) после того, как игрок схватил верёвку — тарзанку, верёвку или
 * стропу шара. Список действий свой у каждого вида; пропадает сразу, когда верёвку отпустили. Текст — {@code hint.gameplaymod.*}.
 */
final class RopeHints {
	/** Сколько тиков держится подсказка (15 секунд). */
	static final int SHOW_TICKS = 300;
	/** Последние тики подсказка тает. */
	private static final int FADE_TICKS = 20;
	private static final int LINE_HEIGHT = 11;
	/** Нижняя строка подсказки над панелью быстрого доступа и строкой над ней (сообщения над панелью остаются видны). */
	private static final int BOTTOM_OFFSET = 80;

	/** Номер верёвки в руках и сколько тиков с её захвата. */
	private static int heldId = -1, ticks;

	private RopeHints() {
	}

	static void init() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "rope_hints"), RopeHints::extract);
	}

	/** Раз в тик клиента: новая верёвка в руках — отсчёт заново, отпустили — подсказки нет. */
	static void tick(Minecraft mc) {
		RopeEntity rope = mc.player == null || mc.level == null ? null : RopeEntity.heldBy(mc.player);
		if (rope == null) {
			heldId = -1;
			return;
		}
		if (rope.getId() != heldId) {
			heldId = rope.getId();
			ticks = 0;
		} else if (ticks < SHOW_TICKS) ticks++;
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || heldId < 0 || ticks >= SHOW_TICKS) return;
		RopeEntity rope = RopeEntity.heldBy(mc.player);
		if (rope == null) return;
		List<Component> lines = lines(mc, rope);
		float left = SHOW_TICKS - ticks - delta.getGameTimeDeltaPartialTick(false);
		int alpha = left >= FADE_TICKS ? 255 : Math.max(0, (int) (255 * left / FADE_TICKS));
		if (alpha < 8) return;
		int x = g.guiWidth() / 2;
		int y = g.guiHeight() - BOTTOM_OFFSET - lines.size() * LINE_HEIGHT;
		for (int i = 0; i < lines.size(); i++) {
			// заголовок жёлтый, остальное белое
			int rgb = i == 0 ? 0xFFDD55 : 0xFFFFFF;
			g.centeredText(mc.font, lines.get(i), x, y + i * LINE_HEIGHT, alpha << 24 | rgb);
		}
	}

	private static List<Component> lines(Minecraft mc, RopeEntity rope) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("hint.gameplaymod.rope_title", rope.getType().getDescription()));
		if (rope instanceof BalloonRopeEntity balloon) {
			boolean lift = balloon.kind() == BalloonRopeItem.Kind.LIFT;
			if (lift) lines.add(Component.translatable("hint.gameplaymod.balloon_lift"));
			else {
				lines.add(Component.translatable(BalloonRopesClient.sideways() ? "hint.gameplaymod.balloon_helm_side" : "hint.gameplaymod.balloon_helm_forward"));
				lines.add(Component.translatable("hint.gameplaymod.balloon_axis", Component.keybind("key.gameplaymod.balloon_axis")));
			}
			lines.add(Component.translatable("hint.gameplaymod.balloon_fuel", balloon.fuel(), BalloonRopeItem.MAX_FUEL));
		}
		lines.add(Component.translatable("hint.gameplaymod.rope_release"));
		boolean creative = mc.player.getAbilities().instabuild;
		if (!creative && Ropes.lengthFixed(rope)) lines.add(Component.translatable("hint.gameplaymod.rope_fixed"));
		else {
			lines.add(Component.translatable("hint.gameplaymod.rope_length", Component.keybind("key.gameplaymod.rope_longer"), Component.keybind("key.gameplaymod.rope_shorter")));
			if (!creative && rope.anchoredToBlock()) lines.add(Component.translatable("hint.gameplaymod.rope_locked"));
		}
		lines.add(Component.translatable("hint.gameplaymod.rope_slide"));
		lines.add(Component.translatable("hint.gameplaymod.rope_attach"));
		if (rope instanceof CalmRopeEntity) lines.add(Component.translatable("hint.gameplaymod.rope_calm"));
		if (rope instanceof PullRopeEntity) lines.add(Component.translatable("hint.gameplaymod.rope_pull"));
		return lines;
	}
}
