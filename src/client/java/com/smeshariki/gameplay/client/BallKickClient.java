package com.smeshariki.gameplay.client;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.ball.BallEntity;
import com.smeshariki.gameplay.ball.BallKick;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Удар по мячу на отпускание ЛКМ. Нажатие на мяче (не дальше {@link BallEntity#REACH}) начинает прицел, обычный удар не идёт; пока ЛКМ
 * зажата, от середины мяча тянется стрелка ({@link #arrow}); отпускание бьёт по стрелке — сразу у себя (клиент считает полёт мяча сам) и
 * пакетом {@link BallKick} на сервер. Сила — длина стрелки: {@link #NORMAL} блока — обычный пинок, до {@link #LONGEST} — в
 * {@link BallEntity#MAX_POWER} раза сильнее. Короче {@link #SHORTEST} — щелчок: удар по взгляду обычной силы. Колесо мыши, пока ЛКМ зажата,
 * поднимает стрелку вверх шагами по {@link #LIFT_STEP}° до {@link #LIFT_MAX} шагов.
 */
public final class BallKickClient {
	static final double SHORTEST = 0.5, NORMAL = 2.0, LONGEST = NORMAL * BallEntity.MAX_POWER;
	/** Мяч под прицелом (номер сущности), −1 — прицела нет. */
	private static int aiming = -1;
	/** Наклон стрелки вверх колесом мыши, шагов по {@link #LIFT_STEP}° (0 — по земле); сбрасывается с новым прицелом. */
	private static int lift;
	static final int LIFT_STEP = 15, LIFT_MAX = 5;

	private BallKickClient() {
	}

	public static void init() {
		ClientPreAttackCallback.EVENT.register((mc, player, clickCount) -> {
			if (aiming >= 0) return true;
			if (clickCount == 0 || player.isSpectator() || !(mc.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof BallEntity ball)) return false;
			if (!ball.withinReach(player, 0)) return true; // далёкий мяч: ничего, но и не обычный удар
			aiming = ball.getId();
			lift = 0;
			return true;
		});
		ClientTickEvents.START_CLIENT_TICK.register(BallKickClient::tick);
	}

	private static void tick(Minecraft mc) {
		if (aiming < 0) return;
		if (mc.player == null || mc.level == null || !(mc.level.getEntity(aiming) instanceof BallEntity ball) || mc.gui.screen() != null) {
			aiming = -1;
			return;
		}
		if (mc.options.keyAttack.isDown()) return;
		aiming = -1;
		if (!ball.withinReach(mc.player, 0.5)) return;
		Vec3 arrow = arrow(mc, ball, 1f);
		double len = arrow.length();
		Vec3 dir = len < SHORTEST ? new Vec3(mc.player.getLookAngle().x, 0, mc.player.getLookAngle().z) : arrow;
		double power = len < SHORTEST ? 1 : Math.min(len, LONGEST) / NORMAL;
		dir = tilt(dir);
		ball.kick(dir, power, false);
		ClientPlayNetworking.send(new BallKick(ball.getId(), (float) dir.x, (float) dir.y, (float) dir.z, (float) power));
		mc.player.swing(InteractionHand.MAIN_HAND);
	}

	/** Стрелка под прицелом у этого мяча (от его середины, не длиннее {@link #LONGEST}); {@code null} — его не целят или она короче щелчка. */
	static @Nullable Vec3 aimedArrow(BallEntity ball, float partialTick) {
		if (aiming != ball.getId()) return null;
		Minecraft mc = Minecraft.getInstance();
		Vec3 a = arrow(mc, ball, partialTick);
		if (a.length() < SHORTEST) {
			// щелчок: по взгляду обычной силы; стрелку показываем, только если её подняли колесом
			if (lift == 0) return null;
			Vec3 look = mc.player.getViewVector(partialTick);
			a = new Vec3(look.x, 0, look.z).normalize().scale(NORMAL);
		}
		return tilt(a.length() > LONGEST ? a.normalize().scale(LONGEST) : a);
	}

	/** Поднять горизонтальную стрелку на угол колеса, длина та же. */
	static Vec3 tilt(Vec3 flat) {
		if (lift == 0) return flat;
		double a = Math.toRadians(lift * LIFT_STEP), len = flat.length();
		Vec3 h = new Vec3(flat.x, 0, flat.z);
		if (h.lengthSqr() < 1e-9) return flat;
		return h.normalize().scale(len * Math.cos(a)).add(0, len * Math.sin(a), 0);
	}

	/** Колесо мыши, пока целимся: вперёд — стрелка выше, назад — ниже. Возвращает, съедено ли колесо. */
	public static boolean scroll(double y) {
		Minecraft mc = Minecraft.getInstance();
		if (aiming < 0 || y == 0 || mc.player == null) return false;
		lift = Math.clamp(lift + (y > 0 ? 1 : -1), 0, LIFT_MAX);
		mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.gameplaymod.ball.lift", lift * LIFT_STEP));
		return true;
	}

	/** Сила стрелки (доля обычного удара). */
	static double power(Vec3 arrow) {
		return Math.min(arrow.length(), LONGEST) / NORMAL;
	}

	/**
	 * Стрелка от середины мяча по горизонтали: до точки, где взгляд пересекает горизонталь середины мяча; не пересекает (смотрит выше) —
	 * по взгляду, наибольшей длины. Вверх её поднимает колесо ({@link #tilt}).
	 */
	static Vec3 arrow(Minecraft mc, BallEntity ball, float partialTick) {
		Entity cam = mc.player;
		Vec3 eye = cam.getEyePosition(partialTick), look = cam.getViewVector(partialTick);
		Vec3 center = ball.getPosition(partialTick).add(0, BallEntity.RADIUS, 0);
		if (look.y < -1e-3) {
			double t = (center.y - eye.y) / look.y;
			if (t > 0) {
				Vec3 hit = eye.add(look.scale(t)).subtract(center);
				Vec3 flat = new Vec3(hit.x, 0, hit.z);
				if (flat.length() <= LONGEST * 2) return flat;
			}
		}
		Vec3 flatLook = new Vec3(look.x, 0, look.z);
		return flatLook.lengthSqr() < 1e-6 ? Vec3.ZERO : flatLook.normalize().scale(LONGEST);
	}
}
