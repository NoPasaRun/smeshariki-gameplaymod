package com.smeshariki.gameplay.lava;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Прилив лавы: невидимый маркер над озером поднимает лаву слоями от {@code BaseY} до {@code PeakY} и опускает обратно. Слой заполняется от столбца маркера волной по всем
 * соединённым клеткам, поэтому озеро должно быть закрыто со всех сторон на всю высоту прилива. Если слой оказался больше {@link LavaLayer#LIMIT}, значит, есть дыра: прилив
 * останавливается и пишет об этом в лог, а не заливает пещеру. Работает, пока игрок ближе {@code Radius}. Настройка: {@code /data merge entity}.
 */
public class LavaTideEntity extends Entity {
	private int baseY, peakY;
	/** Верхняя клетка лавы сейчас. */
	private int surface;
	private boolean started;
	private float radius = 64f;
	private int holdLow = 600, stepTicks = 160, holdHigh = 400;
	/** Тики цикла идут, только пока рядом игрок. */
	private int time;
	private boolean leaked;
	private final LavaLayer layer = new LavaLayer();
	private boolean queued;
	private int queuedSurface;

	public LavaTideEntity(EntityType<? extends LavaTideEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
		this.setInvulnerable(true);
	}

	@Override
	protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
	}

	/** Где должна быть верхняя клетка лавы на этом тике цикла: внизу, слоями вверх, наверху, слоями вниз. */
	static int target(int time, int base, int peak, int holdLow, int step, int holdHigh) {
		int layers = peak - base, climb = layers * step, period = holdLow + climb + holdHigh + climb;
		int t = Math.floorMod(time, period);
		if (t < holdLow) return base;
		t -= holdLow;
		if (t < climb) return base + t / step + 1;
		t -= climb;
		if (t < holdHigh) return peak;
		t -= holdHigh;
		return peak - t / step - 1;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel server) || this.leaked || this.peakY <= this.baseY) return;
		if (!this.started) {
			this.surface = this.baseY;
			this.started = true;
		}
		if (!server.hasNearbyAlivePlayer(getX(), getY(), getZ(), this.radius)) return;
		if (!this.layer.busy() && !this.queued) {
			this.time++;
			int want = Math.clamp(target(this.time, this.baseY, this.peakY, this.holdLow, this.stepTicks, this.holdHigh), this.baseY, this.peakY);
			if (want > this.surface) plan(server, this.surface + 1, true);
			else if (want < this.surface) plan(server, this.surface, false);
		}
		if (this.queued && this.layer.pour(server)) {
			this.surface = this.queuedSurface;
			this.queued = false;
		}
	}

	/** Слой от столбца маркера: при подъёме воздух и лава, при спуске только лава. */
	private void plan(ServerLevel level, int y, boolean rising) {
		if (!this.layer.plan(level, getBlockX(), y, getBlockZ(), rising, "Прилив лавы у " + blockPosition())) {
			this.leaked = true;
			return;
		}
		this.queued = true;
		this.queuedSurface = rising ? y : y - 1;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putInt("BaseY", this.baseY);
		out.putInt("PeakY", this.peakY);
		out.putInt("Surface", this.queued ? this.queuedSurface : this.surface);
		out.putBoolean("Started", this.started);
		out.putFloat("Radius", this.radius);
		out.putInt("HoldLow", this.holdLow);
		out.putInt("StepTicks", this.stepTicks);
		out.putInt("HoldHigh", this.holdHigh);
		out.putInt("Time", this.time);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		this.baseY = in.getIntOr("BaseY", this.baseY);
		this.peakY = in.getIntOr("PeakY", this.peakY);
		this.surface = in.getIntOr("Surface", this.baseY);
		this.started = in.getBooleanOr("Started", false);
		this.radius = Math.max(1f, in.getFloatOr("Radius", this.radius));
		this.holdLow = Math.max(0, in.getIntOr("HoldLow", this.holdLow));
		this.stepTicks = Math.max(1, in.getIntOr("StepTicks", this.stepTicks));
		this.holdHigh = Math.max(0, in.getIntOr("HoldHigh", this.holdHigh));
		this.time = in.getIntOr("Time", 0);
	}
}
