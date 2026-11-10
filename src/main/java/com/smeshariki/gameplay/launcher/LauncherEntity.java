package com.smeshariki.gameplay.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.smeshariki.gameplay.barrage.FireMarkerEntity;
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
 * {@code Height} над жерлом, и приземляется без урона от падения. Жерло всё время чуть дымит. Игрока и вселённого толкает его клиент (их движение считает он), остальных живых сервер:
 * поэтому {@link #tick} работает на обеих сторонах. В полёте горизонтальная скорость не гаснет: ваниль съела бы её и дуга стала бы кривой. С маркером в руке: ПКМ — курс +30°,
 * Ctrl+ПКМ и Ctrl+ЛКМ — дальность ±шаг, Shift+ПКМ и Shift+ЛКМ — высота ±шаг, Ctrl+Shift+ПКМ и Ctrl+Shift+ЛКМ — следующий и прежний шаг (угол 5–90°, длина 0,5–10), ЛКМ — убрать. Остальное через {@code /data}.
 */
public class LauncherEntity extends FireMarkerEntity {
	public static final float TURN = 30f;
	private static final float MIN_DISTANCE = 0f, MAX_DISTANCE = 64f, DISTANCE_STEP = 2f;
	/** Шаги правки маркером: угол (градусы) и длина (блоки) идут парами, от мелкого к крупному. По умолчанию {@link #DEFAULT_STEP} — прежние 30° и 2 блока. */
	private static final float[] ANGLE_STEPS = { 5f, 15f, 30f, 45f, 90f };
	private static final float[] LENGTH_STEPS = { 0.5f, 1f, 2f, 5f, 10f };
	private static final int DEFAULT_STEP = 2;
	/** Какой шаг выбрал каждый игрок (только в памяти: это настройка инструмента, а не мира). */
	private static final Map<java.util.UUID, Integer> STEPS = new HashMap<>();
	/** Тяжесть и сопротивление живого тела за тик: по вертикали и по горизонтали в воздухе. */
	private static final double GRAVITY = 0.08, DRAG_Y = 0.98, DRAG_XZ = 0.91;
	private static final int MAX_FLIGHT = 400, SMOKE_INTERVAL = 3;

	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DISTANCE = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(LauncherEntity.class, EntityDataSerializers.FLOAT);

	/** Кто сейчас летит от этого жерла и его горизонтальная скорость. У тел, которых ведёт клиент, на сервере здесь ноль: важно только, что они ещё не сели. */
	private final Map<LivingEntity, Vec3> flying = new HashMap<>();

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

	/** Высота, на которую поднимется тело со скоростью {@code vy}. */
	private static double apex(double vy) {
		double y = 0;
		while (vy > 0) {
			y += vy;
			vy = (vy - GRAVITY) * DRAG_Y;
		}
		return y;
	}

	/** Начальная скорость вверх для заданной высоты. */
	public static double launchSpeed(double height) {
		double low = 0, high = 16;
		for (int i = 0; i < 40; i++) {
			double mid = (low + high) / 2;
			if (apex(mid) < height) low = mid;
			else high = mid;
		}
		return high;
	}

	/** Сколько тиков тело летит, пока не вернётся на высоту жерла. */
	public static int flightTicks(double vy) {
		double y = 0;
		for (int t = 1; t < MAX_FLIGHT; t++) {
			y += vy;
			vy = (vy - GRAVITY) * DRAG_Y;
			if (y <= 0 && t > 1) return t;
		}
		return MAX_FLIGHT;
	}

	/** Точки дуги относительно жерла: для показа с маркером в руке. */
	public List<Vec3> arc() {
		double vy = launchSpeed(height());
		int flight = flightTicks(vy);
		Vec3 step = course().scale(distance() / flight);
		List<Vec3> out = new ArrayList<>();
		Vec3 at = Vec3.ZERO;
		out.add(at);
		for (int t = 0; t < flight; t++) {
			at = at.add(step.x, vy, step.z);
			vy = (vy - GRAVITY) * DRAG_Y;
			out.add(at);
		}
		return out;
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide() && this.tickCount % SMOKE_INTERVAL == 0) smoke();
		keepFlying();
		AABB vent = new AABB(getX() - 0.5, getY(), getZ() - 0.5, getX() + 0.5, getY() + 1, getZ() + 0.5);
		for (Entity e : level().getEntities(this, vent, this::canLaunch)) launch((LivingEntity) e);
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
		if (!(e instanceof LivingEntity living) || this.flying.containsKey(living) || e.noPhysics || e.isSpectator() || e.isPassenger()) return false;
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
		if (server && clientDriven(e)) {
			this.flying.put(e, Vec3.ZERO);
			level().playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_JUMP, SoundSource.BLOCKS, 1f, 1f);
			return;
		}
		double vy = launchSpeed(height());
		Vec3 flat = course().scale(distance() / flightTicks(vy));
		e.setOnGround(false);
		e.setDeltaMovement(flat.x, vy, flat.z);
		e.resetFallDistance();
		if (server) {
			e.hurtMarked = true;
			level().playSound(null, getX(), getY(), getZ(), SoundEvents.BREEZE_JUMP, SoundSource.BLOCKS, 1f, 1f);
		}
		this.flying.put(e, flat);
	}

	/** Возвращает то, что съело сопротивление воздуха, и сбрасывает высоту падения, пока тело не село. */
	private void keepFlying() {
		Iterator<Map.Entry<LivingEntity, Vec3>> it = this.flying.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<LivingEntity, Vec3> entry = it.next();
			LivingEntity e = entry.getKey();
			e.resetFallDistance();
			if (e.getControllingPassenger() instanceof Player rider) rider.resetFallDistance();
			boolean ability = e instanceof CharacterEntity character && character.isAbilityActive();
			if (e.isRemoved() || e.onGround() || e.isInWater() || e.isPassenger() || e.isFallFlying() || ability || holdsOrFlies(e) || (e.horizontalCollision && !clientDriven(e))) {
				it.remove();
				continue;
			}
			if (e.horizontalCollision) {
				// упёрлось в стену: дальше обычное падение, но без урона, пока не сядет
				entry.setValue(Vec3.ZERO);
				continue;
			}
			e.setDeltaMovement(e.getDeltaMovement().add(entry.getValue().scale(1 - DRAG_XZ)));
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
