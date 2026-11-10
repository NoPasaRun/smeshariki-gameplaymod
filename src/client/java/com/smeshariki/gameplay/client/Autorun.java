package com.smeshariki.gameplay.client;

import java.util.UUID;

import com.smeshariki.gameplay.LeafPhase;
import com.smeshariki.gameplay.character.AutorunControl;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.raft.RaftEntity;
import com.smeshariki.gameplay.slide.SlopeSurface;
import com.smeshariki.gameplay.wind.WindMarkerItem;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * Автобег: бег вперёд без удержания W, влево-вправо можно подруливать. Включает и выключает только уровень (команда с сервера,
 * механика {@code gameplaymod:autorun}); своей клавиши у игрока нет, «назад» не останавливает. Когда игрок ступил на плот, шар или скат, автобег встаёт на паузу и возвращается, как только тело оказалось
 * на земле или в воде вне них; прыжок на палубе паузу не снимает. В воздухе и в воде автобег не жмёт «вперёд» (тело летит и
 * тонет по инерции) и сам продолжается, когда тело снова на земле, без сообщений. Пока открыт
 * экран, игрок стоит, как с отпущенными клавишами, а затем бежит дальше. На автобеге листва не задевает тело; после выключения
 * она остаётся проходимой, пока тело из неё не выйдет, иначе оно застрянет между блоками.
 */
public final class Autorun {
	private static boolean on;
	/** В прошлый тик стоял на палубе или скате: выключаемся только в момент, когда ступил на них. */
	private static boolean wasOnDeck;
	/** Автобег выключен шагом на палубу или скат и вернётся после схода с них. */
	private static boolean paused;
	/** Кого пропускает листва на этом клиенте; null — никого. */
	private static UUID phaseOwner;

	private Autorun() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(AutorunControl.Command.TYPE, (msg, ctx) -> {
			paused = false;
			if (on != msg.on() && ctx.client().player != null) set(ctx.client(), msg.on());
		});
	}

	static void tick(Minecraft mc) {
		if (mc.player == null) {
			on = paused = false;
			if (phaseOwner != null) LeafPhase.set(phaseOwner, false);
			phaseOwner = null;
			return;
		}
		if (!mc.player.isAlive()) {
			on = paused = false;
			setPhase(mc, false);
			return;
		}
		Entity body = CharacterEntity.body(mc.player);
		boolean onDeck = RaftEntity.onDeck(body) || SlopeSurface.isSliding(body) || SlopeSurface.supportingSlope(body) != null;
		boolean stepped = onDeck && !wasOnDeck;
		wasOnDeck = onDeck;
		// включает и выключает только уровень (переключатель механики): своей клавиши и выключения «назад» у игрока нет
		if (on && stepped) {
			paused = true;
			set(mc, false);
		} else if (paused && !onDeck && (body.onGround() || body.isInWater())) {
			paused = false;
			set(mc, true);
		}
		setPhase(mc, on || phaseOwner != null && LeafPhase.insideLeaves(CharacterEntity.body(mc.player)));
	}

	private static void setPhase(Minecraft mc, boolean phase) {
		if (phase == (phaseOwner != null)) return;
		phaseOwner = phase ? mc.player.getUUID() : null;
		LeafPhase.set(mc.player.getUUID(), phase);
		ClientPlayNetworking.send(new LeafPhase.Packet(phase));
	}

	private static void set(Minecraft mc, boolean value) {
		on = value;
		mc.player.sendOverlayMessage(Component.translatable(on ? "message.gameplaymod.autorun_on" : "message.gameplaymod.autorun_off"));
	}

	/** Сейчас игрок должен бежать сам: автобег включён, экран не открыт, тело на земле и не в воде. */
	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.gui.screen() != null || mc.player == null) return false;
		Entity body = CharacterEntity.body(mc.player);
		return body.onGround() && !body.isInWater();
	}

	/** С маркером ветра в руке клавиша бега — наклон стрелки (сервер смотрит на неё в пакете ввода), поэтому только идём. */
	public static boolean sprints() {
		var player = Minecraft.getInstance().player;
		return player != null && !(player.getMainHandItem().getItem() instanceof WindMarkerItem);
	}
}
