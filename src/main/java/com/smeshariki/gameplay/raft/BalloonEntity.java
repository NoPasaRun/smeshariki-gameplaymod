package com.smeshariki.gameplay.raft;

import java.util.List;

import com.smeshariki.gameplay.wind.Winds;


import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Воздушный шар: блоки хранятся, рисуются и сталкиваются как у {@link RaftEntity}, отличается только движение. Высоту
 * задаёт игрок (целевая высота), вбок несёт ветер ({@link Winds}) и толчки руля (влево-вправо от носа шара); стоящий на земле
 * шар не сносит. Ветер и цель считает сервер и передаёт в данных сущности, толчки — пакетом; клиент считает то же движение и
 * понемногу подтягивается к присланной позиции, чтобы расхождение не копилось. Нос шара задаётся при сборке и больше не меняется.
 */
public class BalloonEntity extends RaftEntity {
	/** Больше блоков в один шар не собираем: полая сфера радиусом 6 и корзина. */
	public static final int MAX_BLOCKS = 600;
	/** Скорость при полном ветре (в центре маркера), блоков за тик (25 блоков в секунду; было 5, усилено 2026-11-05). К краю зоны маркера ветер слабее, см. {@link Winds#at}. */
	public static final double WIND_SPEED = 25.0 / 20;
	/** Скорость подъёма и спуска, блоков за тик (3 блока в секунду). */
	private static final double CLIMB_SPEED = 3.0 / 20;
	/** В воздухе шар сам не висит: цель высоты опускается на столько за тик (0,5 блока/с). Держать высоту — тратить топливо стропы. */
	public static final double SINK = 0.5 / 20;
	/** На сколько блоков цель двигается за щелчок колеса. */
	public static final double TARGET_STEP = 1.0;
	/** Цель не уходит от шара дальше этого, иначе он долго не остановится после отпускания клавиши. */
	private static final double TARGET_LEAD = 6.0;
	/** Сопротивление воздуха вбок: доля скорости, теряемая за тик, и сближение скорости по высоте с нужной. */
	private static final double WIND_GRIP = 0.03, CLIMB_GRIP = 0.2;
	/** Скорость по высоте на блок до цели. Вместе с CLIMB_GRIP даёт подход без перелёта: нужно CLIMB_GAIN ≤ CLIMB_GRIP / 4. */
	private static final double CLIMB_GAIN = 0.05;
	/** На сколько блоков щелчок колеса сдвигает шар рулём. */
	private static final double NUDGE_DISTANCE = 1.0;
	/**
	 * Толчок, после которого шар проходит {@link #NUDGE_DISTANCE} и останавливается: скорость сначала теряет долю, потом
	 * двигает шар, поэтому путь равен v · (1 - g) / g.
	 */
	private static final double NUDGE_SPEED = NUDGE_DISTANCE * WIND_GRIP / (1 - WIND_GRIP);
	/** Быстрее шар рулём не разогнать: стоящих на нём перенос двигает не дальше полублока за тик. */
	private static final double MAX_SIDE_SPEED = 0.5;
	/** Доля расхождения с сервером, убираемая клиентом за тик. */
	private static final double PULL = 0.02;

	private static final EntityDataAccessor<Float> TARGET_Y = SynchedEntityData.defineId(BalloonEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> WIND_X = SynchedEntityData.defineId(BalloonEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> WIND_Y = SynchedEntityData.defineId(BalloonEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> WIND_Z = SynchedEntityData.defineId(BalloonEntity.class, EntityDataSerializers.FLOAT);
	/** Нос шара: четверти оборота по часовой стрелке от курса 0 (+z); задаётся при сборке. */
	private static final EntityDataAccessor<Integer> HEADING = SynchedEntityData.defineId(BalloonEntity.class, EntityDataSerializers.INT);

	/** Группа шара для событий (NBT {@code Group}, ставится {@code /data merge entity}); без группы событий нет. */
	private String group = "";
	/** Сел без топлива при прошлой проверке: события шлются только при смене. */
	private boolean stranded;
	/** Как часто сервер проверяет, сел ли шар без топлива, тиков. */
	private static final int STRANDED_CHECK = 10;

	public BalloonEntity(EntityType<? extends BalloonEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(TARGET_Y, 0f);
		builder.define(WIND_X, 0f);
		builder.define(WIND_Y, 0f);
		builder.define(WIND_Z, 0f);
		builder.define(HEADING, 0);
	}

	public double targetY() {
		return this.entityData.get(TARGET_Y);
	}

	public void setTargetY(double y) {
		this.entityData.set(TARGET_Y, (float) y);
	}

	public int heading() {
		return this.entityData.get(HEADING);
	}

	public void setHeading(int quarters) {
		this.entityData.set(HEADING, Math.floorMod(quarters, 4));
	}

	/** Единичный вектор влево от носа шара. */
	public Vec3 left() {
		double r = Math.toRadians(heading() * 90.0);
		return new Vec3(Math.cos(r), 0, Math.sin(r));
	}

	/** Поворачивает смещение от центра шара на столько четвертей по часовой стрелке. */
	public static Vec3 rotated(Vec3 v, int quarters) {
		return switch (Math.floorMod(quarters, 4)) {
			case 1 -> new Vec3(-v.z, v.y, v.x);
			case 2 -> new Vec3(-v.x, v.y, -v.z);
			case 3 -> new Vec3(v.z, v.y, -v.x);
			default -> v;
		};
	}

	@Override
	public Vec3 toLocal(Vec3 world) {
		return rotated(super.toLocal(world), -heading());
	}

	@Override
	public Vec3 fromLocal(Vec3 local, float partialTick) {
		return super.fromLocal(rotated(local, heading()), partialTick);
	}

	/** Единичный вектор вперёд от носа шара. */
	public Vec3 front() {
		double r = Math.toRadians(heading() * 90.0);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** Толчок рулём на обеих сторонах: forward = 1 вперёд, -1 назад; left = 1 влево, -1 вправо. false, если шар стоит на блоках: стоящий рулём не сдвинуть. */
	public boolean nudge(int forward, int left) {
		if (!free(getX(), getY() - 0.05, getZ())) return false;
		Vec3 push = front().scale(forward).add(left().scale(left)).scale(NUDGE_SPEED);
		vx = Math.clamp(vx + push.x, -MAX_SIDE_SPEED, MAX_SIDE_SPEED);
		vz = Math.clamp(vz + push.z, -MAX_SIDE_SPEED, MAX_SIDE_SPEED);
		return true;
	}

	/** Стоит на блоках и восходящий ветер его не поднимает. */
	public boolean resting() {
		return wind().y <= 0 && !free(getX(), getY() - 0.05, getZ());
	}

	/** Двигает цель вверх (dir = 1) или вниз (-1), не дальше {@link #TARGET_LEAD} от шара и не за пределы мира. */
	public void climb(int dir) {
		double next = targetY() + Integer.signum(dir) * TARGET_STEP;
		next = Math.clamp(next, getY() - TARGET_LEAD, getY() + TARGET_LEAD);
		setTargetY(Math.clamp(next, minY(), maxY()));
	}

	private double minY() {
		return level().getMinY() + 1.0;
	}

	private double maxY() {
		return level().getMaxY() - (double) sy();
	}

	/** Единичный ветер у шара или нули в штиль. */
	public Vec3 wind() {
		return new Vec3(this.entityData.get(WIND_X), this.entityData.get(WIND_Y), this.entityData.get(WIND_Z));
	}

	@Override
	protected double snapDistance() {
		return 16.0;
	}

	/** Ветер и цель приходят с задержкой, поэтому клиент понемногу подтягивается к серверу. Стоящих на шаре сдвинет {@code carry}. */
	@Override
	protected void reconcile() {
		super.reconcile();
		// микроскопические шаги стоящий на шаре не повторил бы и понемногу оказался бы внутри палубы (см. RaftEntity.simulate)
		if (serverPos == null || position().distanceToSqr(serverPos) < 1.0e-6) return;
		double x = getX() + (serverPos.x - getX()) * PULL, y = getY() + (serverPos.y - getY()) * PULL, z = getZ() + (serverPos.z - getZ()) * PULL;
		if (free(x, y, z)) place(x, y, z);
	}

	public String group() {
		return group;
	}

	/** Топливо всех строп высоты на этом шаре (загруженных). */
	public int liftFuel() {
		java.util.UUID id = getUUID();
		int fuel = 0;
		for (BalloonRopeEntity rope : level().getEntitiesOfClass(BalloonRopeEntity.class, getBoundingBox().inflate(64), r -> id.equals(r.anchorRaftId()) && r.kind() == BalloonRopeItem.Kind.LIFT)) fuel += rope.fuel();
		return fuel;
	}

	/**
	 * Сел и сам не поднимется: стоит на блоках, а в стропах высоты топлива нет. Заправиться ещё можно, поэтому событие
	 * {@code gameplaymod:balloon_stranded:группа} — только повод, а что делать, решает уровень; {@code gameplaymod:balloon_lifted:группа} — снова может лететь.
	 */
	private void checkStranded() {
		boolean now = resting() && liftFuel() <= 0;
		if (now == stranded) return;
		stranded = now;
		if (!group.isEmpty() && level() instanceof net.minecraft.server.level.ServerLevel server) {
			com.smeshariki.gameplay.api.Mechanics.emit(server.getServer(), (now ? Balloons.EVENT_STRANDED : Balloons.EVENT_LIFTED) + ":" + group);
		}
	}

	@Override
	protected void simulate() {
		if (!level().isClientSide() && tickCount % STRANDED_CHECK == 0) checkStranded();
		if (!level().isClientSide()) {
			Vec3 wind = Winds.at(level(), new AABB(originX(), getY(), originZ(), originX() + sx(), getY() + sy(), originZ() + sz()));
			this.entityData.set(WIND_X, wind == null ? 0f : (float) wind.x);
			this.entityData.set(WIND_Y, wind == null ? 0f : (float) wind.y);
			this.entityData.set(WIND_Z, wind == null ? 0f : (float) wind.z);
		}
		double x = getX(), y = getY(), z = getZ();
		Vec3 wind = wind();
		// стоящий шар ветер не сносит, но восходящий поднимает
		boolean landed = wind.y <= 0 && !free(x, y - 0.05, z);
		double wantY = Math.clamp((targetY() - y) * CLIMB_GAIN, -CLIMB_SPEED, CLIMB_SPEED);
		if (!level().isClientSide()) {
			// стоящий не копит цель под землёй: первый щелчок вверх сразу поднимает; в воздухе цель опускается
			if (landed) {
				if (targetY() < y) setTargetY(y);
			} else setTargetY(Math.max(minY(), targetY() - SINK));
		}
		if (!landed) {
			wantY += wind.y * WIND_SPEED;
			// цель едет с ветром: шар остаётся на новой высоте, а стропа работает как обычно
			if (wind.y != 0 && !level().isClientSide()) setTargetY(Math.clamp(targetY() + wind.y * WIND_SPEED, minY(), maxY()));
		}
		vy += (wantY - vy) * CLIMB_GRIP;
		if (landed) {
			vx *= 0.5;
			vz *= 0.5;
		} else {
			vx += (wind.x * WIND_SPEED - vx) * WIND_GRIP;
			vz += (wind.z * WIND_SPEED - vz) * WIND_GRIP;
		}
		if (Math.abs(vx) < 1.0e-4) vx = 0;
		if (Math.abs(vy) < 1.0e-4) vy = 0;
		if (Math.abs(vz) < 1.0e-4) vz = 0;
		if (vx != 0) {
			if (free(x + vx, y, z)) x += vx;
			else vx = 0;
		}
		if (vz != 0) {
			if (free(x, y, z + vz)) z += vz;
			else vz = 0;
		}
		if (vy != 0) {
			if (free(x, y + vy, z)) y = Math.clamp(y + vy, minY(), Math.max(minY(), maxY()));
			else vy = 0;
		}
		if (x != getX() || y != getY() || z != getZ()) place(x, y, z);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putDouble("TargetY", targetY());
		out.putInt("Heading", heading());
		if (!group.isEmpty()) out.putString("Group", group);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		setTargetY(in.getDoubleOr("TargetY", getY()));
		setHeading(in.getIntOr("Heading", 0));
		group = in.getStringOr("Group", "");
	}
}
