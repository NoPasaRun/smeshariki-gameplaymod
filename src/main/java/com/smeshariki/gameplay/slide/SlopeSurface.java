package com.smeshariki.gameplay.slide;

import java.util.List;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.rope.RopeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Наклонные столкновения скатов. Коробка тела опирается на самую высокую точку ската под своим следом, как настоящая коробка на наклонной
 * плоскости: она не заходит ни в скат, ни в блоки под соседними скатами, поэтому нечему выталкивать или останавливать тело, а сервер видит обычное
 * движение. Высота непрерывна, ступеней нет. Встроено в ванильный {@code Entity.collide}, поэтому стояние на земле, прыжок и управление на льду —
 * обычные; отсюда идёт только разгон под уклон.
 */
public final class SlopeSurface {
	/** Выше этого над ногами скат считается стеной, если тело не идёт вверх по нему своим уклоном. */
	private static final double LAND_MARGIN = 0.05;
	/** Прижатие на спуске: насколько ниже расчётного спуска ещё считается, что тело едет по поверхности. */
	private static final double STICK_MARGIN = 0.1, STICK_FACTOR = 1.5;
	/** Самый крутой скат: сколько блоков вниз на блок пути. */
	private static final double MAX_TANGENT = 2.5;
	/** Наибольшая скорость вдоль земли на любом скате, блоков за тик. */
	private static final double HARD_LIMIT = 3.6;
	/** Сопротивление за тик при скольжении, как на льду без трения о воздух. */
	private static final double GLIDE = 0.995;
	private static final double OVERSPEED_DECAY = 0.985, BRAKE = 0.96;
	/** Shift: до какой скорости притормаживает и какой разгон остаётся на ней. */
	private static final double BRAKE_FLOOR = 0.24, BRAKE_ACCEL = 0.03;
	/** Подруливание за тик на скате и в полёте, сопротивление в полёте. */
	private static final double STEER = 0.036, AIR_STEER = 0.012, AIR_DRAG = 0.995;
	/** Тело считается упёршимся ниже этой скорости; на сколько пробовать сдвинуть коробку вниз по склону. */
	private static final double STUCK_SPEED = 0.06, PROBE_STEP = 0.2;
	/** Какая доля скорости остаётся при касании стены за тик. */
	private static final double WALL_KEEP = 0.92;
	/** Обход преграды: шаг и дальность поиска поперёк склона, насколько вперёд должно быть свободно, скорость сноса. */
	private static final double ASIDE_STEP = 0.5, ASIDE_RANGE = 4, ASIDE_AHEAD = 0.5, ASIDE_SPEED = 0.3;
	/** Сервер считает тело лежащим, если скат не дальше этого под ногами. */
	private static final double SERVER_REACH = 0.4;
	/** Обычные ноги после упора кончаются, если тело упало или ушло дальше (в том числе телепорт). */
	private static final double WALK_OUT_FALL = 1.5, WALK_OUT_RANGE = 48;

	private SlopeSurface() {
	}

	/** Скат, найденный под телом. */
	private static final class Probe {
		double floor = Double.NaN, tangent;
		BlockPos pos;
		BlockState state;
		boolean wall;
	}

	/** Что может стоять на скате. Плоты, снаряды и прочее скаты не замечают. */
	private static boolean rides(Entity e) {
		return e instanceof LivingEntity || e instanceof ItemEntity;
	}

	/** Движение тела считает другая сторона: это игрок (или вселённый персонаж), которого на сервере только сверяют с его клиентом. */
	private static boolean followsClient(Entity e) {
		return !e.level().isClientSide() && !e.isLocalInstanceAuthoritative();
	}

	private static Player driver(Entity e) {
		return e instanceof Player p ? p : e.getControllingPassenger() instanceof Player p ? p : null;
	}

	private static boolean isFlying(Entity e) {
		return e instanceof Player p && p.getAbilities().flying;
	}

	private static boolean onRope(Entity e) {
		Player p = driver(e);
		return p != null && RopeEntity.heldBy(p) != null;
	}

	/**
	 * Скаты под следом {@code box}, сдвинутым на ({@code dx}, {@code dz}), в полосе высот от {@code low} до верха тела. Выше ног на шаг — стена,
	 * если только тело уже не стояло в этом блоке (тогда он не мешает выйти).
	 */
	private static Probe probe(Entity e, AABB box, double dx, double dz, double low) {
		Probe p = new Probe();
		Level level = e.level();
		double feet = box.minY, head = box.maxY, step = Math.hypot(dx, dz);
		double climb = e.onGround() ? e.maxUpStep() : LAND_MARGIN;
		double x0 = box.minX + dx, x1 = box.maxX + dx, z0 = box.minZ + dz, z1 = box.maxZ + dz;
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int yTop = (int) Math.floor(head), yLow = (int) Math.floor(low - 1.4);
		for (int bx = (int) Math.floor(x0), bxEnd = (int) Math.floor(x1 - 1e-7); bx <= bxEnd; bx++)
			for (int bz = (int) Math.floor(z0), bzEnd = (int) Math.floor(z1 - 1e-7); bz <= bzEnd; bz++)
				for (int by = yTop; by >= yLow; by--) {
					BlockState state = level.getBlockState(at.set(bx, by, bz));
					if (!(state.getBlock() instanceof SlideBlock block)) continue;
					double h = block.highest(state, x0 - bx, x1 - bx, z0 - bz, z1 - bz);
					if (Double.isNaN(h)) continue;
					double top = by + h;
					if (top <= low) break; // ниже в этом столбце искать нечего
					double was = block.highest(state, box.minX - bx, box.maxX - bx, box.minZ - bz, box.maxZ - bz);
					if (!Double.isNaN(was) && by + was > feet + Math.max(climb, 0.6)) continue; // уже внутри: пусть выходит
					double tangent = block.tangent();
					if (top > feet + climb + tangent * step + 1e-6) {
						if (by < head - 1e-6) p.wall = true;
						continue;
					}
					if (Double.isNaN(p.floor) || top > p.floor) {
						p.floor = top;
						p.tangent = tangent;
						p.pos = at.immutable();
						p.state = state;
					}
				}
		return p;
	}

	/** Результат ванильного {@code collide} с учётом скатов. */
	public static Vec3 collide(Entity e, Vec3 wanted, Vec3 got) {
		if (!rides(e)) return got;
		AABB box = e.getBoundingBox();
		double feet = box.minY, dx = got.x, dz = got.z, dy = got.y;
		double reach = e.onGround() ? MAX_TANGENT * STICK_FACTOR * Math.hypot(dx, dz) + STICK_MARGIN : 0;
		double low = Math.min(feet + dy, feet - reach);
		if (followsClient(e)) { // считал клиент: сервер только сбрасывает высоту падения, обычное столкновение здесь ничему не мешает
			Probe p = probe(e, box, dx, dz, low);
			if (!Double.isNaN(p.floor) && feet + dy - p.floor < 0.2) resetFall(e);
			return got;
		}
		Probe p = probe(e, box, dx, dz, low);
		if (p.wall) {
			Probe alongX = probe(e, box, dx, 0, low), alongZ = alongX.wall ? probe(e, box, 0, dz, low) : null;
			if (!alongX.wall) {
				dz = 0;
				p = alongX;
			} else if (!alongZ.wall) {
				dx = 0;
				p = alongZ;
			} else {
				dx = dz = 0;
				p = probe(e, box, 0, 0, low);
			}
		}
		if (Double.isNaN(p.floor)) return dx == got.x && dz == got.z ? got : new Vec3(dx, dy, dz);
		double y = feet + dy;
		if (p.floor >= y) { // встаёт на скат или поднимается по нему
			double rise = p.floor - feet;
			if (rise > 0 && !e.level().noCollision(e, box.move(dx, rise, dz))) { // сверху потолок: дальше не пройти
				dx = dz = 0;
				p = probe(e, box, 0, 0, low);
				rise = Double.isNaN(p.floor) ? dy : Math.max(dy, p.floor - feet);
			}
			dy = rise;
			resetFall(e);
		} else if (e.onGround() && wanted.y <= 0 && !isFlying(e) && !onRope(e)
				&& y - p.floor <= stickTangent(e, box, p) * STICK_FACTOR * Math.hypot(dx, dz) + STICK_MARGIN) {
			// на спуске поверхность уходит вниз быстрее тяжести: тело держится её, а не летит над ней; блок на пути остановит раньше
			dy += Entity.collideBoundingBox(e, new Vec3(0, p.floor - y, 0), box.move(dx, dy, dz), e.level(), List.of()).y;
			resetFall(e);
		}
		return new Vec3(dx, dy, dz);
	}

	/** Уклон для прижатия: на переломе задняя часть следа ещё едет по крутому скату, с которого сходит, поэтому берётся больший из двух. */
	private static double stickTangent(Entity e, AABB box, Probe next) {
		Probe now = probe(e, box, 0, 0, box.minY - 0.02);
		return Double.isNaN(now.floor) ? next.tangent : Math.max(now.tangent, next.tangent);
	}

	private static void resetFall(Entity e) {
		e.resetFallDistance();
		if (e.getControllingPassenger() instanceof Player p) p.resetFallDistance();
	}

	/** Скат, на котором тело стоит, или null. */
	private static Probe contact(Entity e) {
		AABB box = e.getBoundingBox();
		Probe p = probe(e, box, 0, 0, box.minY - 0.02);
		return Double.isNaN(p.floor) || p.floor < box.minY - 0.02 ? null : p;
	}

	/** Блок ската, который задаёт трение и скорость вместо блока под ногами; null, если тело стоит не на скате. */
	public static BlockPos supportingSlope(Entity e) {
		if (!rides(e) || !e.onGround()) return null;
		Probe p = contact(e);
		return p == null ? null : p.pos;
	}

	/** Под следом сдвинутой коробки есть скат не дальше {@code depth} вниз: Shift не должен считать это обрывом. */
	public static boolean supportsWithin(Entity e, double dx, double dz, double depth) {
		AABB box = e.getBoundingBox();
		return !Double.isNaN(probe(e, box, dx, dz, box.minY - depth).floor);
	}

	/** Живое тело скользит: своё движение вместо ходьбы, лёжа на спине, без шагов и прыжка. Клубок Ёжика катится по своим правилам. */
	public static boolean isSliding(Entity e) {
		if (!(e instanceof LivingEntity) || e.noPhysics || e.isInWater() || e.isPassenger() || isFlying(e) || onRope(e)) return false;
		if (e instanceof CharacterEntity c && c.isRolling()) return false;
		if (followsClient(e)) { // движение считает клиент, скорости здесь нет: для позы достаточно, что поверхность рядом
			AABB box = e.getBoundingBox();
			return !Double.isNaN(probe(e, box, 0, 0, box.minY - SERVER_REACH).floor);
		}
		Probe p = e.onGround() ? contact(e) : null;
		SlideRider rider = (SlideRider) e;
		if (rider.gameplaymod$walkingOut()) { // упёрся и получил ноги: скользит снова, только сойдя со ската
			Vec3 spot = rider.gameplaymod$walkOutFrom();
			boolean left = p == null && e.onGround(), fell = e.fallDistance > WALK_OUT_FALL, far = spot == null || spot.distanceToSqr(e.position()) > WALK_OUT_RANGE * WALK_OUT_RANGE;
			if (!left && !fell && !far) return false;
			rider.gameplaymod$setWalkingOut(false);
		}
		if (p == null) return false;
		if (stuck(e, p)) {
			rider.gameplaymod$setWalkingOut(true);
			rider.gameplaymod$setWalkOutFrom(e.position());
			return false;
		}
		return true;
	}

	/** Вниз по склону не пройти (стена в конце ската) и тело почти стоит: возвращаются обычные ноги, иначе отсюда не выбраться. */
	private static boolean stuck(Entity e, Probe p) {
		Vec3 v = e.getDeltaMovement(), d = ((SlideBlock) p.state.getBlock()).downhill(p.state);
		if (Math.hypot(v.x, v.z) > STUCK_SPEED) return false;
		return blockedDownhill(e, d) && aside(e, d) == null;
	}

	private static boolean blockedDownhill(Entity e, Vec3 d) {
		return blocked(e, d.x, d.z) && (Math.abs(d.x) < 0.01 || blocked(e, d.x, 0)) && (Math.abs(d.z) < 0.01 || blocked(e, 0, d.z));
	}

	/** Преграду вроде ствола можно обойти: сторона поперёк склона, куда идти, чтобы снова можно было ехать вниз; null, если обхода нет (настоящий тупик). */
	private static Vec3 aside(Entity e, Vec3 d) {
		for (int side = 1; side >= -1; side -= 2) {
			double sx = -d.z * side, sz = d.x * side;
			for (double w = ASIDE_STEP; w <= ASIDE_RANGE; w += ASIDE_STEP) {
				AABB at = e.getBoundingBox().move(sx * w, 0.1, sz * w);
				if (!e.level().noCollision(e, at)) break;
				if (e.level().noCollision(e, at.move(d.x * ASIDE_AHEAD, 0, d.z * ASIDE_AHEAD))) return new Vec3(sx, 0, sz);
			}
		}
		return null;
	}

	private static boolean blocked(Entity e, double dx, double dz) {
		return !e.level().noCollision(e, e.getBoundingBox().move(dx * PROBE_STEP, 0.1, dz * PROBE_STEP));
	}

	/**
	 * Движение вместо ванильного {@code travel}: только инерция. Вперёд и назад игрок не управляет, влево-вправо слабо подруливает поперёк хода, Shift притормаживает, но
	 * не останавливает. Съехавшее тело летит дальше почти без потери скорости, пока не приземлится. Возвращает true, если движение выполнено здесь.
	 */
	public static boolean travel(LivingEntity e) {
		if (followsClient(e)) return false; // считает клиент, здесь только сверка
		SlideRider rider = (SlideRider) e;
		if (e instanceof CharacterEntity frozen && frozen.frozenHere()) {
			// ожидание после сброса: никакого скольжения
			rider.gameplaymod$setLaunched(false);
			return false;
		}
		if (e instanceof CharacterEntity c && c.isRolling()) {
			rider.gameplaymod$setLaunched(false);
			return false;
		}
		if (isSliding(e)) {
			rider.gameplaymod$setLaunched(true);
			glideOnSlope(e);
			return true;
		}
		if (!rider.gameplaymod$launched()) return false;
		if (e.isInWater() || e.noPhysics || e.isPassenger() || isFlying(e) || onRope(e) || rider.gameplaymod$walkingOut()) {
			rider.gameplaymod$setLaunched(false);
			return false;
		}
		// на краю ската тело ещё тик числится на земле: ваниль сразу съела бы половину скорости трением, поэтому посадка — только второй такой тик
		if (e.onGround() && rider.gameplaymod$landedTick()) {
			rider.gameplaymod$setLandedTick(false);
			rider.gameplaymod$setLaunched(false);
			return false;
		}
		rider.gameplaymod$setLandedTick(e.onGround());
		flyOff(e);
		return true;
	}

	private static void glideOnSlope(LivingEntity e) {
		Probe p = contact(e);
		SlideBlock block = (SlideBlock) p.state.getBlock();
		Vec3 v = e.getDeltaMovement();
		double vx = v.x, vz = v.z, speed = Math.hypot(vx, vz), max = block.maxSpeed(p.state);
		Player driver = driver(e);
		if (driver != null && driver.isShiftKeyDown()) {
			if (speed > BRAKE_FLOOR) {
				double k = Math.max(BRAKE_FLOOR, speed * BRAKE) / speed;
				vx *= k;
				vz *= k;
			} else {
				Vec3 down = block.downhill(p.state);
				vx += down.x * BRAKE_ACCEL;
				vz += down.z * BRAKE_ACCEL;
			}
		} else if (speed > max) {
			double k = Math.max(max, speed * OVERSPEED_DECAY) / speed;
			vx *= k;
			vz *= k;
		} else {
			Vec3 down = block.downhill(p.state);
			double a = block.accel(p.state);
			vx += down.x * a;
			vz += down.z * a;
			double after = Math.hypot(vx, vz);
			if (after > max) {
				vx *= max / after;
				vz *= max / after;
			}
		}
		Vec3 steered = steer(driver, vx, vz, STEER);
		vx = steered.x * GLIDE;
		vz = steered.z * GLIDE;
		Vec3 slopeDown = block.downhill(p.state);
		if (blockedDownhill(e, slopeDown)) { // уткнулся в ствол: сносит вбок, дальше снова вниз
			Vec3 side = aside(e, slopeDown);
			if (side != null) {
				double keep = Math.max(ASIDE_SPEED, Math.hypot(vx, vz) * WALL_KEEP);
				vx = side.x * keep;
				vz = side.z * keep;
			}
		}
		double now = Math.hypot(vx, vz);
		if (now > HARD_LIMIT) {
			vx *= HARD_LIMIT / now;
			vz *= HARD_LIMIT / now;
		}
		double before = Math.hypot(vx, vz);
		e.setDeltaMovement(vx, v.y, vz);
		e.move(net.minecraft.world.entity.MoverType.SELF, e.getDeltaMovement());
		Vec3 after = e.getDeltaMovement();
		double ax = after.x, az = after.z;
		if (e.horizontalCollision && before > STUCK_SPEED) { // скользит вдоль стены, а не бьётся о её ступени: скорость уходит вдоль склона
			Vec3 down = block.downhill(p.state);
			double dx = ax + down.x * before * 0.5, dz = az + down.z * before * 0.5, d = Math.hypot(dx, dz);
			double ux = d > 1e-4 ? dx / d : 0, uz = d > 1e-4 ? dz / d : 0;
			if (blocked(e, ux, uz)) { // прямо нельзя: вдоль той оси, что свободна; обе закрыты — тело стоит
				if (Math.abs(ux) > 0.01 && !blocked(e, Math.signum(ux), 0)) {
					ux = Math.signum(ux);
					uz = 0;
				} else if (Math.abs(uz) > 0.01 && !blocked(e, 0, Math.signum(uz))) {
					uz = Math.signum(uz);
					ux = 0;
				} else ux = uz = 0;
			}
			ax = ux * before * WALL_KEEP;
			az = uz * before * WALL_KEEP;
		}
		e.setDeltaMovement(ax, (after.y - e.getGravity()) * 0.98, az);
	}

	private static void flyOff(LivingEntity e) {
		Vec3 v = e.getDeltaMovement();
		Vec3 steered = steer(driver(e), v.x, v.z, AIR_STEER);
		e.setDeltaMovement(steered.x * AIR_DRAG, v.y, steered.z * AIR_DRAG);
		e.move(net.minecraft.world.entity.MoverType.SELF, e.getDeltaMovement());
		Vec3 after = e.getDeltaMovement();
		e.setDeltaMovement(after.x, (after.y - e.getGravity()) * 0.98, after.z);
	}

	/** Подруливание: клавиши влево-вправо, по взгляду, но только поперёк скорости; разогнаться или затормозить ими нельзя. */
	private static Vec3 steer(Player driver, double vx, double vz, double force) {
		if (driver == null || driver.xxa == 0) return new Vec3(vx, 0, vz);
		double yaw = Math.toRadians(driver.getYRot()), ax = Math.cos(yaw) * Math.signum(driver.xxa), az = Math.sin(yaw) * Math.signum(driver.xxa);
		double speed = Math.hypot(vx, vz);
		if (speed > 1e-4) {
			double along = (ax * vx + az * vz) / speed;
			ax -= along * vx / speed;
			az -= along * vz / speed;
		}
		return new Vec3(vx + ax * force, 0, vz + az * force);
	}

	/** Для предметов, которые не ходят: разгон под уклон после шага. */
	public static void slideItem(Entity e) {
		if (!(e instanceof ItemEntity) || followsClient(e) || !e.onGround() || e.isInWater()) return;
		Probe p = contact(e);
		if (p == null) return;
		SlideBlock block = (SlideBlock) p.state.getBlock();
		Vec3 v = e.getDeltaMovement(), down = block.downhill(p.state);
		double vx = v.x + down.x * block.accel(p.state), vz = v.z + down.z * block.accel(p.state), speed = Math.hypot(vx, vz), max = block.maxSpeed(p.state);
		if (speed > max) {
			vx *= max / speed;
			vz *= max / speed;
		}
		e.setDeltaMovement(vx, v.y, vz);
	}

	/** Наклон ската под телом (тангенс) для рисунка лежащего тела; 0, если ската нет. */
	public static double slopeUnder(Entity e) {
		Probe p = contact(e);
		return p == null ? 0 : ((SlideBlock) p.state.getBlock()).slope(p.state);
	}

	/** Куда смотрят ноги лежащего: по скорости, а почти стоя — вниз по склону. */
	public static Vec3 slideDirection(Entity e) {
		Vec3 v = e.getDeltaMovement();
		if (Math.hypot(v.x, v.z) > 0.03) return new Vec3(v.x, 0, v.z).normalize();
		Probe p = contact(e);
		return p == null ? Vec3.ZERO : ((SlideBlock) p.state.getBlock()).downhill(p.state);
	}

	/** На сколько коробка висит над скатом под центром тела: чтобы рисовать ноги на поверхности. */
	public static double gapUnderCenter(Entity e, double x, double y, double z) {
		if (!e.onGround()) return 0;
		Level level = e.level();
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int by = (int) Math.floor(y), low = by - 2; by >= low; by--) {
			BlockState state = level.getBlockState(at.set(bx, by, bz));
			if (state.getBlock() instanceof SlideBlock block) {
				double h = block.surface(state, x - bx, z - bz);
				if (!Double.isNaN(h)) return Math.clamp(y - (by + h), 0, 1);
			} else if (!level.getBlockState(at).isAir()) return 0;
		}
		return 0;
	}
}
