package com.smeshariki.gameplay.lava;

import com.smeshariki.gameplay.barrage.FireMarkerEntity;
import com.smeshariki.gameplay.character.CharacterEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Волна лавы: маркер у берега лавового озера. Маркер с группой {@code Group} запускает волну только по команде {@link LavaWaves#launch} (триггер LevelMod), а когда
 * она кончилась (обрушилась и слой лавы залит), сообщает {@link LavaWaves#FINISHED}: так уровень сам чередует волны с разных берегов. Маркер без группы пускает
 * волны сам раз в {@code Interval} тиков (первая через {@code Delay}), пока игрок ближе {@code Radius}. От маркера по курсу {@code Yaw} идёт волна высотой {@code Height} со скоростью {@code Speed} блоков/с, на всю ширину озера (не дальше
 * {@code HalfWidth} в стороны, стены пещеры её обрезают). Пройдя {@code Length} блоков, она обрушивается и поднимает лаву озера на слой, пока верх лавы ниже {@code PeakY}.
 * Внутри волны тело горит, как в лаве.
 * <p>
 * Про то, что на ней плавает, волна не знает: она только поверхность ({@link #swellAt}) — насколько лава поднята над уровнем и куда её склоны увлекают
 * плавающее (с гребнем вперёд и к гребню). Плоты спрашивают это сами, как поле течений.
 * <p>
 * Волна — не блоки: её форму считают сервер (урон) и клиент (рисунок) по времени начала {@code START} и уровню лавы {@code BASE}, которые идут в данных сущности.
 * С маркером в руке: ПКМ — курс +30°, Ctrl+ПКМ и Ctrl+ЛКМ — путь ±2, ЛКМ — убрать. Остальное через {@code /data}.
 */
public class LavaWaveEntity extends FireMarkerEntity {
	public static final float TURN = 30f;
	/** Верх лавы-источника над низом клетки. */
	public static final double LAVA_TOP = 8.0 / 9.0;
	/** Сколько тиков гребень поднимается в начале и опадает при обрушении. */
	public static final int RISE_TICKS = 20, CRASH_TICKS = 12;
	private static final float MIN_LENGTH = 2f, MAX_LENGTH = 128f, LENGTH_STEP = 2f;
	/** Насколько выше лавы ищем, где начинается её поверхность от маркера. */
	private static final int SEARCH_UP = 8, SEARCH_DOWN = 24;

	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> LENGTH = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HALF_WIDTH = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	/** Время мира в начале текущей волны, −1 — волны не было. */
	private static final EntityDataAccessor<Integer> START = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.INT);
	/** Верхняя клетка лавы в начале волны. */
	private static final EntityDataAccessor<Integer> BASE = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.INT);

	private int peakY = Integer.MIN_VALUE;
	private int interval = 200, delay = 0;
	private float radius = 64f;
	/** Живые волны обеих сторон: их спрашивает плавающее на лаве ({@link #swellAt}). */
	public static final List<LavaWaveEntity> ALL = new CopyOnWriteArrayList<>();
	/** Склон тянет плавающее к гребню: доля расстояния до него за тик и предел, блоков за тик. */
	private static final double PULL = 0.12, MAX_PULL = 0.25;

	private long nextStart;
	private boolean wasActive, crashed;
	/** Запуск заказан командой, но волна ещё не пошла: идёт своя волна или заливается слой. */
	private boolean launchRequested;
	/** Волна пошла, а о её конце ещё не сообщили. */
	private boolean finishPending;
	/**
	 * Волна возвращена из снимка уровня посреди хода и ждёт, пока игрок отстоит ожидание после сброса ({@link #release}): пока ждёт, не идёт, не сообщает о конце и
	 * не запускает другую. В сохранение мира не пишется. {@code heldElapsed} — сколько тиков волна уже шла (−1 — не шла), {@code heldSince} — когда её придержали.
	 */
	private boolean held;
	private int heldElapsed = -1;
	private long heldSince;
	/** Придержанная волна сама отпускается через столько тиков, если уровень её не отпустил: волна не должна зависнуть навсегда. */
	private static final int HOLD_LIMIT = 1200;
	private final LavaLayer layer = new LavaLayer();
	/** Сколько слоёв поднимает одно обрушение ({@code Rise}, 1–8) и сколько ещё залить после текущего. */
	private int rise = 1, riseLeft = 0;

	public LavaWaveEntity(EntityType<? extends LavaWaveEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(YAW, 0f);
		builder.define(LENGTH, 32f);
		builder.define(HEIGHT, 3f);
		builder.define(SPEED, 4f);
		builder.define(HALF_WIDTH, 16f);
		builder.define(START, -1);
		builder.define(BASE, 0);
	}

	// ---------------------------------------------------------------- параметры

	public float yaw() {
		return this.entityData.get(YAW);
	}

	public float length() {
		return this.entityData.get(LENGTH);
	}

	public float height() {
		return this.entityData.get(HEIGHT);
	}

	/** Блоков за тик. */
	public double speedPerTick() {
		return this.entityData.get(SPEED) / 20.0;
	}

	public float halfWidth() {
		return this.entityData.get(HALF_WIDTH);
	}

	public int base() {
		return this.entityData.get(BASE);
	}

	public int start() {
		return this.entityData.get(START);
	}

	/** Высота поверхности лавы, от которой поднимается волна. */
	public double surfaceY() {
		return base() + LAVA_TOP;
	}

	/** Куда идёт волна. */
	public Vec3 course() {
		double r = Math.toRadians(yaw());
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** Поперёк курса, вправо от него. */
	public Vec3 side() {
		Vec3 c = course();
		return new Vec3(-c.z, 0, c.x);
	}

	public float peakY() {
		return this.peakY;
	}

	// ---------------------------------------------------------------- форма волны

	/** Сколько тиков гребень идёт до противоположного берега. */
	public int travelTicks() {
		return Math.max(1, Mth.ceil(length() / Math.max(1.0e-3, speedPerTick())));
	}

	/** Тиков от начала волны, или −1, если её нет. */
	public double waveTime(float partialTick) {
		int start = start();
		if (start < 0) return -1;
		return (int) level().getGameTime() - start + partialTick;
	}

	public boolean running(double t) {
		return t >= 0 && t < travelTicks() + CRASH_TICKS;
	}

	/** Где гребень: путь от маркера по курсу. */
	public double crest(double t) {
		return Math.min(t, travelTicks()) * speedPerTick();
	}

	/** Насколько гребень поднялся из лавы в начале, от 0 до 1. */
	public double rise(double t) {
		double x = Math.clamp(t / RISE_TICKS, 0, 1);
		return x * x * (3 - 2 * x);
	}

	/** Высота гребня долей от {@code Height}: растёт в начале, опадает при обрушении. */
	public double amplitude(double t) {
		double crash = t > travelTicks() ? Math.clamp(1 - (t - travelTicks()) / CRASH_TICKS, 0, 1) : 1;
		return rise(t) * crash;
	}

	/** Длина склона волны перед гребнем и за ним: фронт круче. */
	public double front() {
		return 1 + 0.6 * height();
	}

	public double back() {
		return 2 + 1.4 * height();
	}

	/** Высота волны над лавой на расстоянии d от гребня по курсу (вперёд положительно). */
	public double heightAt(double d, double amplitude) {
		double half = d >= 0 ? front() : back();
		if (Math.abs(d) >= half) return 0;
		double c = Math.cos(Math.PI / 2 * d / half);
		return height() * amplitude * c * c;
	}

	/** Путь по курсу и сдвиг поперёк точки мира от маркера. */
	public double along(double x, double z) {
		Vec3 c = course();
		return (x - getX()) * c.x + (z - getZ()) * c.z;
	}

	public double across(double x, double z) {
		Vec3 s = side();
		return (x - getX()) * s.x + (z - getZ()) * s.z;
	}

	/**
	 * Сколько места над лавой у столбца (x, z): NaN, если это не озеро (сверху лавы блок или лавы нет), иначе высота до первого блока над ней, чтобы
	 * волна не проходила сквозь выступы и площадки над озером.
	 */
	public double columnRoom(int x, int z) {
		int base = base();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(x, base, z);
		Level level = level();
		if (!level.getFluidState(at).is(FluidTags.LAVA)) return Double.NaN;
		int top = base + 2 + Mth.ceil(height());
		for (int y = base + 1; y <= top; y++) {
			at.setY(y);
			BlockState state = level.getBlockState(at);
			boolean solid = !state.getCollisionShape(level, at).isEmpty();
			if (y == base + 1 && (solid || state.getFluidState().is(FluidTags.LAVA))) return Double.NaN;
			if (solid) return y - surfaceY();
		}
		return Double.POSITIVE_INFINITY;
	}

	/** Верх волны над точкой мира в момент t; NaN, где волны нет. */
	public double topAt(double x, double z, double t) {
		if (Math.abs(across(x, z)) > halfWidth()) return Double.NaN;
		double h = heightAt(along(x, z) - crest(t), amplitude(t));
		if (h <= 0) return Double.NaN;
		double room = columnRoom(Mth.floor(x), Mth.floor(z));
		if (Double.isNaN(room)) return Double.NaN;
		return surfaceY() + Math.min(h, room);
	}

	/** Насколько волна поднимает лаву над уровнем в точке мира в момент t (0, где волны нет). */
	public double liftAt(double x, double z, double t) {
		double top = topAt(x, z, t);
		return Double.isNaN(top) ? 0 : top - surfaceY();
	}

	/** Поверхность всех волн мира в точке: подъём над уровнем лавы и куда склон увлекает плавающее, блоков за тик. */
	/** {@code crash} — волна над точкой обрушивается: плоты под ней уходят под лаву ({@code RaftEntity}). */
	public record Swell(double lift, double vx, double vz, boolean crash) {
		public static final Swell NONE = new Swell(0, 0, 0, false);
	}

	public static Swell swellAt(Level level, double x, double z) {
		if (ALL.isEmpty()) return Swell.NONE;
		double lift = 0, vx = 0, vz = 0;
		boolean crash = false;
		for (LavaWaveEntity w : ALL) {
			if (w.level() != level || w.isRemoved()) continue;
			double t = w.waveTime(0);
			if (!w.running(t)) continue;
			double h = w.liftAt(x, z, t);
			if (h <= 0 || h <= lift) continue;
			lift = h;
			crash = t >= w.travelTicks();
			// пока гребень идёт, склон несёт с ним и тянет к нему; при обрушении никуда не несёт
			double v = 0;
			if (t < w.travelTicks()) v = w.speedPerTick() + Math.clamp((w.crest(t) - w.along(x, z)) * PULL, -MAX_PULL, MAX_PULL);
			Vec3 c = w.course();
			vx = c.x * v;
			vz = c.z * v;
		}
		return lift <= 0 ? Swell.NONE : new Swell(lift, vx, vz, crash);
	}

	/** Ящик, в котором может быть волна в момент t. */
	public AABB band(double t) {
		Vec3 c = course(), s = side();
		double crest = crest(t), w = halfWidth();
		double a0 = crest - back(), a1 = crest + front();
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (double a : new double[] { a0, a1 }) for (double u : new double[] { -w, w }) {
			double x = getX() + c.x * a + s.x * u, z = getZ() + c.z * a + s.z * u;
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		return new AABB(minX, surfaceY() - 1, minZ, maxX, surfaceY() + height() + 1, maxZ);
	}

	// ---------------------------------------------------------------- ход

	@Override
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel server) serverTick(server);
		else clientTick();
	}

	private void clientTick() {
		double t = waveTime(0);
		if (!running(t)) return;
		Vec3 c = course(), s = side();
		for (int i = 0; i < 2; i++) {
			double u = (this.random.nextDouble() * 2 - 1) * halfWidth(), a = crest(t) + (this.random.nextDouble() - 0.5) * 2;
			double x = getX() + c.x * a + s.x * u, z = getZ() + c.z * a + s.z * u;
			double top = topAt(x, z, t);
			if (!Double.isNaN(top)) level().addParticle(ParticleTypes.LAVA, x, top, z, 0, 0, 0);
		}
	}

	private void serverTick(ServerLevel level) {
		if (this.held) {
			if (level.getGameTime() - this.heldSince > HOLD_LIMIT) release(level);
			return;
		}
		if (this.layer.busy()) this.layer.pour(level);
		// обрушение поднимает несколько слоёв: следующий, когда залит предыдущий
		if (!this.layer.busy() && this.riseLeft > 0) {
			int y = findSurface(getBlockX(), getBlockY() + this.rise, getBlockZ());
			if (y != Integer.MIN_VALUE && y < this.peakY && this.layer.plan(level, getBlockX(), y + 1, getBlockZ(), true, "Волна лавы у " + blockPosition())) this.riseLeft--;
			else this.riseLeft = 0;
		}
		long now = level.getGameTime();
		double t = waveTime(0);
		if (running(t)) {
			if (!this.crashed && t >= travelTicks()) crash(level, t);
			burn(level, t);
			return;
		}
		// слой обрушения ещё заливается: следующая волна пошла бы от старого уровня
		if (this.layer.busy() || this.riseLeft > 0) return;
		if (this.finishPending) {
			this.finishPending = false;
			LavaWaves.FINISHED.invoker().finished(this, group());
		}
		if (this.launchRequested) {
			this.launchRequested = false;
			begin(level, now);
			return;
		}
		if (!group().isEmpty()) return;
		boolean active = active(level);
		if (active && !this.wasActive) this.nextStart = now + this.delay;
		this.wasActive = active;
		if (active && now >= this.nextStart) {
			this.nextStart = now + Math.max(1, this.interval);
			begin(level, now);
		}
	}

	private boolean active(ServerLevel level) {
		double r2 = (double) this.radius * this.radius;
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator()) continue;
			if (CharacterEntity.body(player).distanceToSqr(this) <= r2) return true;
		}
		return false;
	}

	/**
	 * Верхняя клетка лавы в столбце маркера или MIN_VALUE, если лавы нет. Ищет и до {@code PeakY}: маркер поздней волны ждёт внизу, пока другие
	 * волны поднимают лаву на десятки блоков (прежде окно в 8 блоков вверх не находило её, и цепочка волн вставала).
	 */
	private int findSurface(int x, int y, int z) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int top = y + SEARCH_UP;
		if (this.peakY != Integer.MIN_VALUE) top = Math.max(top, this.peakY + 1);
		for (int yy = top; yy >= y - SEARCH_DOWN; yy--) {
			if (level().getFluidState(at.set(x, yy, z)).is(FluidTags.LAVA) && !level().getFluidState(at.set(x, yy + 1, z)).is(FluidTags.LAVA)) return yy;
		}
		return Integer.MIN_VALUE;
	}

	private void begin(ServerLevel level, long now) {
		int surface = findSurface(getBlockX(), getBlockY(), getBlockZ());
		if (surface == Integer.MIN_VALUE) return;
		// поставлен через /summon без PeakY
		if (this.peakY == Integer.MIN_VALUE) this.peakY = surface + 8;
		this.entityData.set(BASE, surface);
		// лава поднялась: маркер держится у её поверхности, иначе поиск ушёл бы из окна
		if (getBlockY() != surface + 1) setPos(getX(), surface + 1, getZ());
		this.entityData.set(START, (int) now);
		this.crashed = false;
		this.finishPending = true;
		level.playSound(null, getX(), surfaceY(), getZ(), SoundEvents.LAVA_POP, SoundSource.BLOCKS, 2f, 0.6f);
	}

	/** Гребень дошёл до противоположного берега: брызги, звук и слой лавы. */
	private void crash(ServerLevel level, double t) {
		this.crashed = true;
		Vec3 c = course(), s = side();
		double a = crest(t);
		for (double u = -halfWidth(); u <= halfWidth(); u += 1.5) {
			double x = getX() + c.x * a + s.x * u, z = getZ() + c.z * a + s.z * u;
			if (Double.isNaN(columnRoom(Mth.floor(x), Mth.floor(z)))) continue;
			level.sendParticles(ParticleTypes.LAVA, x, surfaceY() + height() * 0.5, z, 6, 0.6, 0.4, 0.6, 0.1);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, x, surfaceY() + height(), z, 2, 0.4, 0.3, 0.4, 0.02);
		}
		Vec3 mid = new Vec3(getX() + c.x * a, surfaceY(), getZ() + c.z * a);
		level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 3f, 0.5f);
		level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 2f, 0.6f);
		if (base() < this.peakY && !this.layer.busy() && this.layer.plan(level, getBlockX(), base() + 1, getBlockZ(), true, "Волна лавы у " + blockPosition()))
			this.riseLeft = Math.min(this.rise, this.peakY - base()) - 1;
	}

	/** Внутри волны тело горит, как в лаве. Вселённого бьёт только его персонаж, игрока в выживании без персонажа (он в окне выбора) волна не трогает. */
	private void burn(ServerLevel level, double t) {
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, band(t))) {
			if (e instanceof ServerPlayer player && (player.isPassenger() || player.gameMode() == GameType.SURVIVAL)) continue;
			if (e.isSpectator() || e.isRemoved()) continue;
			double top = topAt(e.getX(), e.getZ(), t);
			if (Double.isNaN(top) || e.getBoundingBox().minY >= top - 0.05 || e.getY() < surfaceY() - 1.5) continue;
			e.lavaIgnite();
			e.lavaHurt();
		}
	}

	// ---------------------------------------------------------------- маркер

	/** Придержана ли волна после возврата из снимка. */
	public boolean isHeld() {
		return this.held;
	}

	/** Отпустить придержанную волну: она идёт с того места, где была в снимке. */
	public void release(ServerLevel level) {
		if (!this.held) return;
		this.held = false;
		if (this.heldElapsed >= 0) this.entityData.set(START, (int) level.getGameTime() - this.heldElapsed);
		this.heldElapsed = -1;
	}

	/** Заказать волну: пойдёт, как только кончится идущая и зальётся слой. Повторный заказ до её начала ничего не добавляет. */
	public void requestLaunch() {
		this.launchRequested = true;
	}

	@Override
	protected Item item() {
		return LavaWaves.ITEM;
	}

	@Override
	protected void turn() {
		this.entityData.set(YAW, Mth.wrapDegrees(Math.round(yaw() / TURN) * TURN + TURN));
	}

	@Override
	protected void tune(int sign) {
		this.entityData.set(LENGTH, Math.clamp(length() + sign * LENGTH_STEP, MIN_LENGTH, MAX_LENGTH));
	}

	@Override
	public void announce(Player player) {
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.lava_wave_set", Math.floorMod(Math.round(yaw()), 360), Math.round(length()), Math.round(height()), this.peakY));
	}

	/** Маркер встаёт над поверхностью лавы в столбце под прицелом (клик по лаве проходит до дна); курс — взгляд. Предел лавы по умолчанию на 8 слоёв выше. */
	@Override
	public void placedBy(Player player, BlockPos pos) {
		int surface = findSurface(pos.getX(), pos.getY(), pos.getZ());
		int y = surface == Integer.MIN_VALUE ? pos.getY() : surface + 1;
		setPos(pos.getX() + 0.5, y, pos.getZ() + 0.5);
		this.entityData.set(YAW, Math.round(player.getYRot() / TURN) * TURN);
		if (this.peakY == Integer.MIN_VALUE) this.peakY = y - 1 + 8;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 160 * 160;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putFloat("Yaw", yaw());
		out.putFloat("Length", length());
		out.putFloat("Height", height());
		out.putFloat("Speed", this.entityData.get(SPEED));
		out.putFloat("HalfWidth", halfWidth());
		out.putInt("PeakY", this.peakY == Integer.MIN_VALUE ? getBlockY() - 1 + 8 : this.peakY);
		out.putInt("Interval", this.interval);
		out.putInt("Rise", this.rise);
		out.putInt("RiseLeft", this.riseLeft);
		out.putInt("Delay", this.delay);
		out.putFloat("Radius", this.radius);
		// прерванная сохранением волна после загрузки пойдёт заново, иначе уровень не дождался бы её конца
		out.putBoolean("Launch", this.launchRequested || this.finishPending);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		this.entityData.set(YAW, in.getFloatOr("Yaw", yaw()));
		this.entityData.set(LENGTH, Math.clamp(in.getFloatOr("Length", length()), MIN_LENGTH, MAX_LENGTH));
		this.entityData.set(HEIGHT, Math.clamp(in.getFloatOr("Height", height()), 0.5f, 16f));
		this.entityData.set(SPEED, Math.clamp(in.getFloatOr("Speed", this.entityData.get(SPEED)), 0.5f, 40f));
		this.entityData.set(HALF_WIDTH, Math.clamp(in.getFloatOr("HalfWidth", halfWidth()), 0.5f, 64f));
		this.peakY = in.getIntOr("PeakY", this.peakY);
		this.interval = Math.max(1, in.getIntOr("Interval", this.interval));
		this.rise = Math.clamp(in.getIntOr("Rise", this.rise), 1, 8);
		this.riseLeft = Math.clamp(in.getIntOr("RiseLeft", this.riseLeft), 0, 7);
		this.delay = Math.max(0, in.getIntOr("Delay", this.delay));
		this.radius = Math.max(1f, in.getFloatOr("Radius", this.radius));
		this.launchRequested = in.getBooleanOr("Launch", this.launchRequested);
		// прерванная сохранением волна не продолжается, а начинается заново (Launch)
		if (!level().isClientSide()) this.entityData.set(START, -1);
		// снимок уровня (чекпоинт): волна продолжается с того же места, а не начинается заново (см. progress)
		int elapsed = in.getIntOr("ResumeElapsed", Integer.MIN_VALUE);
		if (elapsed != Integer.MIN_VALUE && !level().isClientSide()) {
			this.launchRequested = in.getBooleanOr("ResumeLaunch", false);
			this.finishPending = in.getBooleanOr("ResumeFinish", false);
			this.crashed = in.getBooleanOr("ResumeCrashed", false);
			if (elapsed >= 0) this.entityData.set(BASE, in.getIntOr("ResumeBase", base()));
			// ход не продолжается сразу: игрок после сброса ещё стоит на ожидании, волна идёт после него (release)
			if (elapsed >= 0 || this.launchRequested || this.finishPending) {
				this.held = true;
				this.heldElapsed = elapsed;
				this.heldSince = level().getGameTime();
			}
		}
	}

	/**
	 * Ход волны для снимка уровня: сколько тиков идёт (−1 — не идёт), от какой лавы начата, обрушилась ли, ждёт ли сообщения о конце и заказанный запуск.
	 * В обычное сохранение мира это не пишется (там прерванная волна начинается заново): только в снимок чекпоинта, чтобы возврат вернул волну в то же место.
	 */
	public net.minecraft.nbt.CompoundTag progress() {
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		double t = waveTime(0);
		tag.putInt("ResumeElapsed", running(t) ? (int) t : -1);
		tag.putInt("ResumeBase", base());
		tag.putBoolean("ResumeCrashed", this.crashed);
		tag.putBoolean("ResumeFinish", this.finishPending);
		tag.putBoolean("ResumeLaunch", this.launchRequested);
		return tag;
	}

	/** Волна никого не толкает и не задевается ничем, кроме прицела с маркером. */
	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}
}
