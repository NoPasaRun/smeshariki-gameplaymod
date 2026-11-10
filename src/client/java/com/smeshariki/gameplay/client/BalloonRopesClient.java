package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.raft.BalloonEntity;
import com.smeshariki.gameplay.raft.BalloonNudged;
import com.smeshariki.gameplay.raft.BalloonRopeAction;
import com.smeshariki.gameplay.raft.BalloonRopeEntity;
import com.smeshariki.gameplay.raft.BalloonRopeItem;
import com.smeshariki.gameplay.rope.RopeEntity;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Управление шаром колесом мыши, пока игрок держит стропу. Высота: вперёд — вверх, назад — вниз. Руль: клавиша смены оси
 * переключает два состояния: вперёд-назад и вправо-влево. Схватить и отпустить стропу — ЛКМ, как у тарзанки.
 */
final class BalloonRopesClient {
	/** Накопленная прокрутка: тачпад присылает доли щелчка. */
	private static double scrolled;
	/** Ось руля: true — вправо-влево, false — вперёд-назад. */
	private static boolean sideways;

	private BalloonRopesClient() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(BalloonNudged.TYPE, (msg, ctx) -> {
			if (ctx.client().level != null && ctx.client().level.getEntity(msg.entityId()) instanceof BalloonEntity b) b.nudge(msg.forward(), msg.left());
		});
	}

	/** Клавишу смены оси разбираем всегда: нажатие без стропы не должно сработать потом. */
	static void tick(Minecraft mc) {
		while (GameplayModClient.BALLOON_AXIS.consumeClick()) {
			if (!(RopeEntity.heldBy(mc.player) instanceof BalloonRopeEntity rope) || rope.kind() != BalloonRopeItem.Kind.HELM) continue;
			sideways = !sideways;
			mc.player.sendOverlayMessage(Component.translatable(sideways ? "message.gameplaymod.balloon_axis_side" : "message.gameplaymod.balloon_axis_forward"));
		}
	}

	/** Ось руля сейчас: true — вправо-влево, false — вперёд-назад. */
	static boolean sideways() {
		return sideways;
	}

	static boolean scroll(double delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.screen() != null || !(RopeEntity.heldBy(mc.player) instanceof BalloonRopeEntity rope) || rope.balloon() == null) {
			scrolled = 0;
			return false;
		}
		scrolled += delta;
		while (Math.abs(scrolled) >= 1) {
			boolean forward = scrolled > 0;
			scrolled -= Math.signum(scrolled);
			ClientPlayNetworking.send(new BalloonRopeAction(action(rope.kind(), forward)));
		}
		return true;
	}

	private static int action(BalloonRopeItem.Kind kind, boolean forward) {
		if (kind == BalloonRopeItem.Kind.LIFT) return forward ? BalloonRopeAction.UP : BalloonRopeAction.DOWN;
		if (sideways) return forward ? BalloonRopeAction.RIGHT : BalloonRopeAction.LEFT;
		return forward ? BalloonRopeAction.FORWARD : BalloonRopeAction.BACK;
	}
}
