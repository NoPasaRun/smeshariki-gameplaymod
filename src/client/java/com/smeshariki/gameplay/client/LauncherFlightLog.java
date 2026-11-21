package com.smeshariki.gameplay.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.launcher.LauncherEntity;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Отладочный журнал полёта из жерла: {@code <папка игры>/logs/gameplay_launcher.log} (заводится заново при запуске игры). Каждый тик полёта своего тела
 * и ещё {@link #AFTER} тиков после него: клавиши, ввод игры ({@code zza}/{@code xxa}), положение, скорость, касания, какое жерло ведёт; события жерла (бросок,
 * перехват, удар о стену, конец полёта с причиной). Временный — убрать, когда разберёмся с управлением в полёте.
 */
public final class LauncherFlightLog {
	private static final int AFTER = 40;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static BufferedWriter out;
	private static int tail = -1;
	private static long tick;

	private LauncherFlightLog() {
	}

	public static void init() {
		try {
			Path file = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("gameplay_launcher.log");
			Files.createDirectories(file.getParent());
			out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			org.slf4j.LoggerFactory.getLogger("gameplaymod").warn("Журнал жерла не открыт", ex);
			return;
		}
		LauncherEntity.debug = (e, event) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || e != CharacterEntity.body(mc.player)) return;
			write("* " + event);
			tail = AFTER;
		};
		ClientTickEvents.END_CLIENT_TICK.register(LauncherFlightLog::tick);
	}

	private static void tick(Minecraft mc) {
		tick++;
		if (mc.player == null || mc.level == null) return;
		Entity body = CharacterEntity.body(mc.player);
		LauncherEntity owner = body instanceof LivingEntity living ? LauncherEntity.ownerOf(living) : null;
		if (owner == null) {
			if (tail < 0) return;
			tail--;
		} else tail = AFTER;
		var o = mc.options;
		Vec3 p = body.position(), v = body.getDeltaMovement();
		String keys = (o.keyUp.isDown() ? "W" : "-") + (o.keyLeft.isDown() ? "A" : "-") + (o.keyDown.isDown() ? "S" : "-") + (o.keyRight.isDown() ? "D" : "-")
				+ (o.keyJump.isDown() ? " прыж" : "") + (o.keySprint.isDown() ? " бег" : "") + (o.keyShift.isDown() ? " shift" : "");
		String lead = owner == null ? "нет" : String.format(Locale.ROOT, "%.1f %.1f %.1f%s", owner.getX(), owner.getY(), owner.getZ(), LauncherEntity.keepsInertia((LivingEntity) body) ? "" : " (без инерции)");
		write(String.format(Locale.ROOT, "t%d клавиши[%s] zza %.2f xxa %.2f взгляд %.0f/%.0f | %s pos %.2f %.2f %.2f v %.3f %.3f %.3f гор %.3f | земля %s стенаГ %s стенаВ %s вода %s окно %s | жерло %s",
				tick, keys, mc.player.zza, mc.player.xxa, mc.player.getYRot(), mc.player.getXRot(), body == mc.player ? "игрок" : "персонаж", p.x, p.y, p.z, v.x, v.y, v.z,
				v.horizontalDistance(), body.onGround(), body.horizontalCollision, body.verticalCollision, body.isInWater(), mc.gui.screen() == null ? "-" : mc.gui.screen().getClass().getSimpleName(), lead));
	}

	private static void write(String line) {
		if (out == null) return;
		try {
			out.write(LocalTime.now().format(TIME) + " " + line);
			out.newLine();
			out.flush();
		} catch (IOException ignored) {
		}
	}
}
