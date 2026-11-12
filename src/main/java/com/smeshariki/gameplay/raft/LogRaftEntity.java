package com.smeshariki.gameplay.raft;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Бревно на реке: плот, который идёт по пути реки ({@link Logs.Path}, его даёт WorldMod), а не по полю течений. Вдоль реки идёт с постоянной скоростью,
 * поперёк качается синусоидой вокруг середины своего ряда. Положение зависит только от времени старта и параметров синусоиды,
 * поэтому сервер и клиент считают его одинаково. В конце маршрута разрушается ({@link RaftEntity#destroy}). В файл мира
 * не сохраняется ({@code noSave}): при выгрузке чанков брёвна пропадают, поток создаётся заново.
 */
public class LogRaftEntity extends RaftEntity {
	/** При таком расхождении с расчётным путём клиент догоняет его рывком, блоков. */
	private static final double RESYNC = 3.0;
	/** Скорость бревна по маршруту, блоков за тик (3,33 блока/с); у плотов на течении своя, {@link RaftEntity#SPEED}. */
	public static final double LOG_SPEED = 3.33 / 20;

	private static final EntityDataAccessor<Integer> START = SynchedEntityData.defineId(LogRaftEntity.class, EntityDataSerializers.INT);
	/** Номер маршрута реки (его знает WorldMod). */
	private static final EntityDataAccessor<Integer> ROUTE = SynchedEntityData.defineId(LogRaftEntity.class, EntityDataSerializers.INT);
	/** Середина ряда и качание вокруг неё (доли размаха реки), длина волны в блоках вдоль реки и начальная фаза. */
	private static final EntityDataAccessor<Float> CENTER = SynchedEntityData.defineId(LogRaftEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> SWAY = SynchedEntityData.defineId(LogRaftEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> WAVELENGTH = SynchedEntityData.defineId(LogRaftEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> PHASE = SynchedEntityData.defineId(LogRaftEntity.class, EntityDataSerializers.FLOAT);
	/** Сдвиг ряда поперёк русла, блоков: плюс — к правому берегу (по течению). */
	private static final EntityDataAccessor<Float> SHIFT = SynchedEntityData.defineId(LogRaftEntity.class, EntityDataSerializers.FLOAT);

	private double s;
	private boolean running;
	private final double[] point = new double[3];

	public LogRaftEntity(EntityType<? extends LogRaftEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(START, 0);
		builder.define(ROUTE, -1);
		builder.define(CENTER, 0f);
		builder.define(SWAY, 0f);
		builder.define(WAVELENGTH, 1f);
		builder.define(PHASE, 0f);
		builder.define(SHIFT, 0f);
	}

	/** Сервер: ставит бревно в начало маршрута. */
	void launch(int route, float center, float sway, float wavelength, float phase, float shift) {
		this.entityData.set(ROUTE, route);
		this.entityData.set(START, (int) level().getGameTime());
		this.entityData.set(CENTER, center);
		this.entityData.set(SWAY, sway);
		this.entityData.set(WAVELENGTH, wavelength);
		this.entityData.set(PHASE, phase);
		this.entityData.set(SHIFT, shift);
		route().at(0, lane(0), this.entityData.get(SHIFT), point);
		setPos(point[0], point[1], point[2]);
	}

	/** Положение поперёк русла после пути s: от −1 (один берег) до 1 (другой). */
	private double lane(double s) {
		return this.entityData.get(CENTER) + this.entityData.get(SWAY) * Math.sin(2.0 * Math.PI * s / this.entityData.get(WAVELENGTH) + this.entityData.get(PHASE));
	}

	/** Позицию считает простая формула, присланное сервером клиенту не нужно. */
	@Override
	protected void reconcile() {
	}

	private double expected() {
		return ((int) level().getGameTime() - this.entityData.get(START)) * LOG_SPEED;
	}

	private Logs.Path route() {
		return Logs.path(level(), this.entityData.get(ROUTE));
	}

	@Override
	protected void simulate() {
		Logs.Path route = route();
		// клиенту маршруты ещё не пришли, на сервере мир без них: стоим
		if (route == null) {
			if (!level().isClientSide()) discard();
			return;
		}
		if (!running) {
			s = expected();
			running = true;
		}
		s += LOG_SPEED;
		if (Math.abs(s - expected()) > RESYNC) s = expected();
		route.at(s, lane(s), this.entityData.get(SHIFT), point);
		place(point[0], point[1], point[2]);
		if (s >= route.length() && !level().isClientSide()) destroy();
	}
}
