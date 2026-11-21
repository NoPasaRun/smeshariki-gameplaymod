package com.smeshariki.gameplay.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.smeshariki.gameplay.ball.BallEntity;
import com.smeshariki.gameplay.barrage.FireMarkerEntity;
import com.smeshariki.gameplay.raft.BalloonEntity;
import com.smeshariki.gameplay.raft.LogRaftEntity;
import com.smeshariki.gameplay.raft.RaftEntity;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.rope.RopeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Жерло: маркер на блоке, который вышвыривает персонажей по дуге. Кто ступил в клетку над блоком, улетает по курсу {@code Yaw} на {@code Distance} блоков, поднявшись на
 * {@code Height} над жерлом; падение считается от вершины, как обычное (мимо воды с большой высоты — урон). Жерло всё время чуть дымит. Игрока и вселённого толкает его клиент (их движение считает он), остальных живых сервер:
 * поэтому {@link #tick} работает на обеих сторонах. Весь полёт — инерция (ваниль съела бы горизонталь), и клавиши в полёте копятся
 * в скорость (см. {@link #INERTIA_LOSS}); {@code Distance} — куда упадёт тело, если ничего не нажимать. С маркером в руке: ПКМ — курс +30°,
 * Ctrl+ПКМ и Ctrl+ЛКМ — дальность ±шаг, Shift+ПКМ и Shift+ЛКМ — высота ±шаг, Ctrl+Shift+ПКМ и Ctrl+Shift+ЛКМ — следующий и прежний шаг (угол 5–90°, длина 0,5–10), ЛКМ — убрать. Остальное через {@code /data}.
 */
public class LauncherEntity extends FireMarkerEntity {
	public static final float TURN = 30f;
	private static final float MIN_DISTANCE = 0f, MAX_DISTANCE = 256f, DISTANCE_STEP = 2f;
	/** Шаги правки маркером: угол (градусы) и длина (блоки) идут парами, от мелкого к крупному. По умолчанию {@link #DEFAULT_STEP} — прежние 30° и 2 блока. */
	private static final float[] ANGLE_STEPS = { 5f, 15f, 30f, 45f, 90f };
	private static final float[] LENGTH_STEPS = { 0.5f, 1f, 2f, 5f, 10f };
	private static final int DEFAULT_STEP = 2;
	/** Какой шаг выбрал каждый игрок (только в памяти: это настройка инструмента, а не мира). */
	private static final Map<java.util.UUID, Integer> STEPS = new HashMap<>();
	/** Тяжесть и сопротивление живого тела за тик: по вертикали и по горизонтали в воздухе. */
	private static final double GRAVITY = 0.08, DRAG_Y = 0.98, DRAG_XZ = 0.91;
	/**
	 * Жерло только задаёт начальную скорость: дальше тело летит по инерции, как после тарзанки (теряет {@code INERTIA_LOSS} горизонтали за тик вместо ванильных 9 %),
	 * а нажатые в полёте клавиши копятся в скорость — можно доворачивать, долетать дальше или ближе, весь полёт.
	 */
	private static final double INERTIA_LOSS = RopeEntity.SWING_LOSS;
	private static final int MAX_FLIGHT = 400, SMOKE_INTERVAL = 3;

	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DISTANCE = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.FLOAT);

	/**
	 * Кто сейчас летит из жерла. Полёт ведёт не тик жерла, а тик мира ({@link #tickFlights}): жерло, от которого тело улетело дальше дальности обновления
	 * сущностей, не тикает, и с ним пропадала инерция (журнал полёта 2026-11-20: в ~115 блоках от устья вулкана). По тождеству и отдельно для клиента и сервера:
	 * в одиночной игре статика общая, а {@code Entity.equals} сравнивает номера. Жерла можно ставить цепочкой: бросок другим жерлом забирает тело у прежнего;
	 * только что брошенное ({@link #HANDOFF_TICKS}) другие жерла не ловят, поэтому несколько жерл рядом не перебрасывают тело друг за другом.
	 */
	private static final class Flight {
		final LauncherEntity launcher;
		final long time;
		/** Сервер лишь следит за телом, которое движет клиент игрока (перехват, конец полёта), скорость не трогает. */
		final boolean watchOnly;
		/** Инерция держится; после удара о стену — обычное падение. */
		boolean inertia = true;

		Flight(LauncherEntity launcher, long time, boolean watchOnly) {
			this.launcher = launcher;
			this.time = time;
			this.watchOnly = watchOnly;
		}
	}
	private static final Map<LivingEntity, Flight> CLIENT_FLIGHTS = new java.util.IdentityHashMap<>(), SERVER_FLIGHTS = new java.util.IdentityHashMap<>();
	private static final int HANDOFF_TICKS = 10;

	private static Map<LivingEntity, Flight> flights(Level level) {
		return level.isClientSide() ? CLIENT_FLIGHTS : SERVER_FLIGHTS;
	}

	/** Отладочный журнал полёта (ставит клиент для своего тела; на сервере пусто): тело, событие. */
	public static volatile java.util.function.BiConsumer<LivingEntity, String> debug;

	private void debug(LivingEntity e, String event) {
		var d = debug;
		if (d != null && level().isClientSide()) d.accept(e, String.format(java.util.Locale.ROOT, "%s [жерло %.1f %.1f %.1f курс %.1f дальн %.1f выс %.1f]", event, getX(), getY(), getZ(), yaw(), distance(), height()));
	}

	/** Жерло, которое сейчас ведёт тело (на стороне этого тела), или null. */
	public static LauncherEntity ownerOf(LivingEntity e) {
		Flight f = flights(e.level()).get(e);
		return f == null ? null : f.launcher;
	}

	/** Держится ли у тела инерция полёта (false — не летит или после удара о стену). */
	public static boolean keepsInertia(LivingEntity e) {
		Flight f = flights(e.level()).get(e);
		return f != null && f.inertia;
	}

	public LauncherEntity(EntityType<? extends LauncherEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(YAW, 0f);
		builder.define(DISTANCE, 8f);
		builder.define(HEIGHT, 6f);
	}

	public float yaw() {
		return this.entityData.get(YAW);
	}

	public void setYaw(float yaw) {
		this.entityData.set(YAW, yaw);
	}

	public float distance() {
		return this.entityData.get(DISTANCE);
	}

	public void setDistance(float distance) {
		this.entityData.set(DISTANCE, Math.clamp(distance, MIN_DISTANCE, MAX_DISTANCE));
	}

	public float height() {
		return this.entityData.get(HEIGHT);
	}

	public void setHeight(float height) {
		this.entityData.set(HEIGHT, Math.max(0.5f, height));
	}

	public Vec3 course() {
		double r = Math.toRadians(yaw());
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/**
	 * Как тело одного вида летит за тик: из скорости (по горизонтали — одна ось) сдвиг {@code [0], [1]} и скорость на следующий тик {@code [2], [3]}.
	 * У каждого вида свои тяжесть, сопротивление и порядок шагов, поэтому бросок считается по нему, чтобы все падали в одну точку.
	 */
	private interface Motion {
		double[] step(double vx, double vy);
	}

	/** Живое: по вертикали ваниль, горизонталь — инерция с потерей {@link #INERTIA_LOSS} ({@link #keepFlying}). */
	private static final Motion LIVING = (vx, vy) -> new double[] { vx, vy, vx * (1 - INERTIA_LOSS), (vy - GRAVITY) * DRAG_Y };
	/** Мяч ({@code BallEntity.simulate}): тяжесть, сдвиг, потом сопротивление. */
	private static final Motion BALL = (vx, vy) -> {
		double y = vy - BallEntity.GRAVITY;
		return new double[] { vx, y, vx * BallEntity.AIR_DRAG, y * BallEntity.AIR_DRAG };
	};
	/** Плот в воздухе ({@code RaftEntity.simulate}): тяжесть с пределом, потеря по горизонтали, потом сдвиг. */
	private static final Motion RAFT = (vx, vy) -> {
		double x = vx * (1 - RaftEntity.AIR_LOSS), y = Math.max(vy - RaftEntity.FALL, -RaftEntity.MAX_FALL);
		return new double[] { x, y, x, y };
	};
	/** Свободный конец верёвки ({@code RopeEntity.tickFree}): тяжесть и потеря, потом сдвиг; дальше длины верёвка не пустит. */
	private static final Motion ROPE = (vx, vy) -> {
		double k = 1 - RopeEntity.SWING_LOSS, x = vx * k, y = (vy - RopeEntity.GRAVITY) * k;
		return new double[] { x, y, x, y };
	};

	/** Высота, на которую поднимется тело вида m со скоростью {@code vy}. */
	private static double apex(Motion m, double vy) {
		double y = 0;
		for (int t = 0; t < MAX_FLIGHT; t++) {
			double[] s = m.step(0, vy);
			if (s[1] <= 0) break;
			y += s[1];
			vy = s[3];
		}
		return y;
	}

	/** Начальная скорость вверх, с которой тело вида m поднимется на height. */
	private static double launchSpeed(Motion m, double height) {
		double low = 0, high = 16;
		for (int i = 0; i < 40; i++) {
			double mid = (low + high) / 2;
			if (apex(m, mid) < height) low = mid;
			else high = mid;
		}
		return high;
	}

	/** Путь по горизонтали при начальной скорости 1 до возврата на высоту жерла: начальная скорость для distance — distance / путь. */
	private static double flatPath(Motion m, double vy) {
		double x = 0, y = 0, vx = 1;
		for (int t = 1; t < MAX_FLIGHT; t++) {
			double[] s = m.step(vx, vy);
			x += s[0];
			y += s[1];
			vx = s[2];
			vy = s[3];
			if (y <= 0 && t > 1) break;
		}
		return x;
	}

	/** Скорость броска тела вида m: поднимется на {@link #height}, упадёт через {@link #distance} по курсу. */
	private Vec3 velocity(Motion m) {
		double vy = launchSpeed(m, height()), path = flatPath(m, vy);
		Vec3 flat = course().scale(path > 0 ? distance() / path : 0);
		return new Vec3(flat.x, vy, flat.z);
	}

	/** Начальная скорость вверх живого тела для заданной высоты. */
	public static double launchSpeed(double height) {
		return launchSpeed(LIVING, height);
	}

	/** Точки дуги относительно жерла (без нажатых клавиш): для показа с маркером в руке. */
	public List<Vec3> arc() {
		Vec3 v = velocity(LIVING), dir = course();
		double vx = v.horizontalDistance(), vy = v.y;
		List<Vec3> out = new ArrayList<>();
		Vec3 at = Vec3.ZERO;
		out.add(at);
		for (int t = 1; t < MAX_FLIGHT; t++) {
			double[] s = LIVING.step(vx, vy);
			at = at.add(dir.x * s[0], s[1], dir.z * s[0]);
			vx = s[2];
			vy = s[3];
			out.add(at);
			if (at.y <= 0 && t > 1) break;
		}
		return out;
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide() && this.tickCount % SMOKE_INTERVAL == 0) smoke();
		AABB vent = new AABB(getX() - 0.5, getY(), getZ() - 0.5, getX() + 0.5, getY() + 1, getZ() + 0.5);
		for (Entity e : level().getEntities(this, vent, this::canLaunch)) launch((LivingEntity) e);
		if (!level().isClientSide()) launchThings(vent);
	}

	/** Когда бросили неживое (время мира): оно ещё несколько тиков в жерле, бросать его снова не надо. */
	private final Map<Entity, Long> thrown = new HashMap<>();
	private static final int THROW_COOLDOWN = 20;

	/**
	 * Неживое: мяч, плот, свободный конец верёвки (сервер; плот рассылает бросок клиентам сам, мяч и верёвку двигает сервер). Шар держит
	 * высоту сам, брёвна идут по пути, маркеры — неподвижные инструменты: их не бросает.
	 */
	private void launchThings(AABB vent) {
		long now = level().getGameTime();
		this.thrown.values().removeIf(t -> now - t > THROW_COOLDOWN);
		boolean any = false;
		for (BallEntity ball : level().getEntitiesOfClass(BallEntity.class, vent, b -> !this.thrown.containsKey(b))) {
			ball.launch(velocity(BALL));
			this.thrown.put(ball, now);
			any = true;
		}
		for (RopeEntity rope : level().getEntitiesOfClass(RopeEntity.class, vent, r -> r.mode() == RopeEntity.FREE && !this.thrown.containsKey(r))) {
			rope.setDeltaMovement(velocity(ROPE));
			this.thrown.put(rope, now);
			any = true;
		}
		for (RaftEntity raft : RaftEntity.ALL) {
			if (raft.level() != level() || raft.isRemoved() || raft.destroyed() || raft instanceof BalloonEntity || raft instanceof LogRaftEntity || this.thrown.containsKey(raft) || !raft.touches(vent)) continue;
			raft.fling(velocity(RAFT));
			this.thrown.put(raft, now);
			any = true;
		}
		if (any) level().playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_JUMP, SoundSource.BLOCKS, 1f, 1f);
	}

	private void smoke() {
		double x = getX() + (this.random.nextDouble() - 0.5) * 0.5, z = getZ() + (this.random.nextDouble() - 0.5) * 0.5;
		level().addParticle(ParticleTypes.POOF, x, getY() + 0.05, z, 0, 0.06, 0);
	}

	/** Игрока и его персонажа ведёт его клиент: он и толкает, а сервер запоминает, чтобы при посадке не считать падение. */
	private static boolean clientDriven(Entity e) {
		return e instanceof Player || e.getControllingPassenger() instanceof Player;
	}

	private boolean canLaunch(Entity e) {
		if (!(e instanceof LivingEntity living) || e.noPhysics || e.isSpectator() || e.isPassenger()) return false;
		Flight f = flights(level()).get(living);
		if (f != null && (f.launcher == this || level().getGameTime() - f.time < HANDOFF_TICKS)) return false;
		if (!level().isClientSide()) return true;
		// на клиенте только своё тело: чужие придут пакетами
		return e instanceof Player player ? player.isLocalPlayer() : e.getControllingPassenger() instanceof Player rider && rider.isLocalPlayer();
	}

	private static boolean holdsOrFlies(Entity e) {
		Player driver = e instanceof Player p ? p : e.getControllingPassenger() instanceof Player rider ? rider : null;
		if (driver == null) return false;
		return driver.getAbilities().flying || RopeEntity.heldBy(driver) != null;
	}

	private void launch(LivingEntity e) {
		if (holdsOrFlies(e)) return;
		boolean server = !level().isClientSide();
		Flight previous = flights(level()).put(e, new Flight(this, level().getGameTime(), server && clientDriven(e)));
		if (previous != null && previous.launcher != this) debug(e, "ПЕРЕХВАТ у прежнего жерла");
		// падение считается от броска: на подъёме оно не копится, с вершины — как обычно, и посадка мимо воды бьёт за всю высоту
		e.resetFallDistance();
		if (e.getControllingPassenger() instanceof Player rider) rider.resetFallDistance();
		if (server && clientDriven(e)) {
			level().playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_JUMP, SoundSource.BLOCKS, 1f, 1f);
			return;
		}
		Vec3 v = velocity(LIVING);
		e.setOnGround(false);
		e.setDeltaMovement(v);
		if (server) {
			e.hurtMarked = true;
			level().playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_JUMP, SoundSource.BLOCKS, 1f, 1f);
		}
		debug(e, String.format(java.util.Locale.ROOT, "БРОСОК скорость %.3f %.3f %.3f", v.x, v.y, v.z));
	}

	/**
	 * Тик мира (сервер — {@code END_LEVEL_TICK}, клиент — свой): возвращает телам в полёте то, что съело сопротивление воздуха (инерция). Высоту падения
	 * не сбрасывает (с 2026-11-20, по просьбе: «должен чуть управлять полётом, чтобы попасть в озеро»): урон от посадки — как при обычном падении с вершины.
	 */
	public static void tickFlights(Level level) {
		Iterator<Map.Entry<LivingEntity, Flight>> it = flights(level).entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<LivingEntity, Flight> entry = it.next();
			LivingEntity e = entry.getKey();
			Flight f = entry.getValue();
			if (e.level() != level && !e.isRemoved()) continue;
			boolean ability = e instanceof CharacterEntity character && character.isAbilityActive();
			if (e.isRemoved() || e.onGround() || e.isInWater() || e.isPassenger() || e.isFallFlying() || ability || holdsOrFlies(e) || (e.horizontalCollision && !clientDriven(e))) {
				f.launcher.debug(e, "КОНЕЦ: " + (e.isRemoved() ? "удалено" : e.onGround() ? "на земле" : e.isInWater() ? "в воде" : e.isPassenger() ? "пассажир" : e.isFallFlying() ? "элитры" : ability ? "способность" : holdsOrFlies(e) ? "верёвка или полёт" : "стена"));
				it.remove();
				continue;
			}
			if (f.watchOnly || !f.inertia) continue;
			if (e.horizontalCollision) {
				// упёрлось в стену: дальше обычное падение
				f.launcher.debug(e, "СТЕНА: инерция снята, дальше обычное падение");
				f.inertia = false;
				continue;
			}
			Vec3 v = e.getDeltaMovement();
			double k = (1 - INERTIA_LOSS) / DRAG_XZ;
			e.setDeltaMovement(v.x * k, v.y, v.z * k);
		}
	}

	@Override
	protected Item item() {
		return Launchers.ITEM;
	}

	@Override
	protected void turn() {
		setYaw(Mth.wrapDegrees(Math.round(yaw() / TURN) * TURN + TURN));
	}

	@Override
	protected void tune(int sign) {
		setDistance(distance() + sign * DISTANCE_STEP);
	}

	private static int stepOf(Player player) {
		return STEPS.getOrDefault(player.getUUID(), DEFAULT_STEP);
	}

	/** Следующий (+1) или прежний (−1) шаг, по кругу. */
	private static void cycleStep(Player player, int sign) {
		STEPS.put(player.getUUID(), Math.floorMod(stepOf(player) + sign, ANGLE_STEPS.length));
	}

	/**
	 * ПКМ — курс на шаг угла; Ctrl+ПКМ — дальность +шаг; Shift+ПКМ — высота +шаг; Ctrl+Shift+ПКМ — следующий шаг (угол и длина вместе). Шаг у каждого игрока свой.
	 */
	@Override
	protected void click(Player player) {
		int step = stepOf(player);
		boolean ctrl = holdsCtrl(player), shift = holdsShift(player);
		if (ctrl && shift) cycleStep(player, 1);
		else if (ctrl) setDistance(distance() + LENGTH_STEPS[step]);
		else if (shift) setHeight(height() + LENGTH_STEPS[step]);
		else setYaw(Mth.wrapDegrees(Math.round(yaw() / ANGLE_STEPS[step]) * ANGLE_STEPS[step] + ANGLE_STEPS[step]));
		announce(player);
	}

	/** ЛКМ — убрать; Ctrl+ЛКМ — дальность −шаг; Shift+ЛКМ — высота −шаг; Ctrl+Shift+ЛКМ — прежний шаг. */
	@Override
	protected boolean hit(net.minecraft.server.level.ServerLevel level, Player player) {
		int step = stepOf(player);
		boolean ctrl = holdsCtrl(player), shift = holdsShift(player);
		if (!ctrl && !shift) return super.hit(level, player);
		if (ctrl && shift) cycleStep(player, -1);
		else if (ctrl) setDistance(distance() - LENGTH_STEPS[step]);
		else setHeight(height() - LENGTH_STEPS[step]);
		announce(player);
		return false;
	}

	/** Число без лишнего нуля: 6, 0,5. */
	private static String fmt(float value) {
		return value == Math.rint(value) ? Integer.toString(Math.round(value)) : String.format("%.1f", value);
	}

	@Override
	public void announce(Player player) {
		int step = stepOf(player);
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.launcher_set", fmt(Math.floorMod(Math.round(yaw() * 10f), 3600) / 10f), fmt(distance()), fmt(height()),
				fmt(ANGLE_STEPS[step]), fmt(LENGTH_STEPS[step])));
	}

	/** Жерло стоит на дне клетки, то есть на самом блоке; курс — взгляд. */
	@Override
	public void placedBy(Player player, BlockPos pos) {
		setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		setYaw(Math.round(player.getYRot() / TURN) * TURN);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putFloat("Yaw", yaw());
		out.putFloat("Distance", distance());
		out.putFloat("Height", height());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		setYaw(in.getFloatOr("Yaw", yaw()));
		setDistance(in.getFloatOr("Distance", distance()));
		setHeight(in.getFloatOr("Height", height()));
	}
}
