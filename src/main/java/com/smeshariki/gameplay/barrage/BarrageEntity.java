package com.smeshariki.gameplay.barrage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Обстрел: пока игрок ближе {@code Radius} к маркеру, стрелы появляются вокруг него на удалении {@code Distance} ± {@code Spread} и летят почти горизонтально со
 * скоростью {@code Speed} блоков за тик туда, где он сейчас, с промахом до {@code Miss} блоков. Упреждения нет. Стрелы идут только пока игрок наказан: его скорость вдоль
 * курса ниже {@code MinSpeed} (блоков в секунду; стоит, тормозит, идёт назад или слишком круто вбок) дольше {@code Grace} тиков. Бегущий по курсу не обстреливается совсем. Откуда
 * сколько стрел, задаёт {@code Density}: стрел в секунду на сектор, секторы равные и идут по часовой стрелке (если смотреть сверху) от курса маркера {@code Yaw}, нулевой
 * сектор — по центру на курсе, то есть там, куда надо бежать. Длина списка задаёт ширину сектора: 12 значений — по 30°, 36 — по 10°. Игрока обстреливает только
 * ближайший к нему маркер, поэтому участки маршрута сменяют друг друга.
 * <p>
 * Настраивается, например: {@code /data merge entity @e[type=gameplaymod:barrage,sort=nearest,limit=1] {Yaw:90f,Distance:10f,Radius:40f,Speed:1.5f,Density:[0f,0f,0f,1f,1f,1f,1f,1f,1f,1f,0f,0f]}}
 */
public class BarrageEntity extends FireMarkerEntity {
	public static final float TURN = 30f, MIN_DISTANCE = 2f, MAX_DISTANCE = 32f, DISTANCE_STEP = 2f;
	/** Спереди по курсу в 90° стрел нет, остальное равномерно, ближе к курсу реже: всего около 4 стрел в секунду. */
	private static final float[] DEFAULT_DENSITY = { 0, 0, 0.2f, 0.4f, 0.6f, 0.6f, 0.6f, 0.6f, 0.6f, 0.4f, 0.2f, 0 };

	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(BarrageEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(BarrageEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DISTANCE = SynchedEntityData.defineId(BarrageEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> SPREAD = SynchedEntityData.defineId(BarrageEntity.class, EntityDataSerializers.FLOAT);
	/** Плотность для показа клиенту: числа через запятую. */
	private static final EntityDataAccessor<String> DENSITY = SynchedEntityData.defineId(BarrageEntity.class, EntityDataSerializers.STRING);

	private float damage = 1f, speed = 1.2f, miss = 1f;
	/** Скорость вдоль курса (блоков в секунду), ниже которой игрок наказывается; 0 — стрелы идут всегда. */
	private float minSpeed = 5f;
	/** Сколько тиков подряд игрок должен идти медленнее {@link #minSpeed}, прежде чем стрелы начнутся. */
	private int grace = 10;
	private float[] density;
	/** Накопленные доли стрелы у каждого игрока по секторам: стрелы идут ровно с заданной частотой, сколько бы она ни была мала. */
	private final Map<UUID, double[]> due = new HashMap<>();

	public BarrageEntity(EntityType<? extends BarrageEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(YAW, 0f);
		builder.define(RADIUS, 32f);
		builder.define(DISTANCE, 8f);
		builder.define(SPREAD, 2f);
		builder.define(DENSITY, encode(DEFAULT_DENSITY));
	}

	private static String encode(float[] values) {
		StringBuilder out = new StringBuilder();
		for (float v : values) out.append(out.isEmpty() ? "" : ",").append(v);
		return out.toString();
	}

	public float yaw() {
		return this.entityData.get(YAW);
	}

	public void setYaw(float yaw) {
		this.entityData.set(YAW, yaw);
	}

	public float radius() {
		return this.entityData.get(RADIUS);
	}

	public void setRadius(float radius) {
		this.entityData.set(RADIUS, Math.max(1f, radius));
	}

	public float distance() {
		return this.entityData.get(DISTANCE);
	}

	public void setDistance(float distance) {
		this.entityData.set(DISTANCE, Math.max(0f, distance));
	}

	public float spread() {
		return this.entityData.get(SPREAD);
	}

	public void setSpread(float spread) {
		this.entityData.set(SPREAD, Math.max(0f, spread));
	}

	public float damage() {
		return this.damage;
	}

	public void setDamage(float damage) {
		this.damage = Math.max(0f, damage);
	}

	public float speed() {
		return this.speed;
	}

	public float miss() {
		return this.miss;
	}

	/** Наименьшая скорость вдоль курса, блоков в секунду: тормозящий, стоящий, идущий назад или слишком вбок получает стрелы. 0 — стрелы идут всегда. */
	public float minSpeed() {
		return this.minSpeed;
	}

	/** Отсрочка в тиках: короткая остановка или поворот не наказываются. */
	public int grace() {
		return this.grace;
	}

	/** Стрел в секунду по секторам; новый массив не менять. */
	public float[] density() {
		if (this.density == null) {
			String[] parts = this.entityData.get(DENSITY).split(",");
			float[] parsed = new float[parts.length];
			for (int i = 0; i < parts.length; i++) parsed[i] = Float.parseFloat(parts[i]);
			this.density = parsed;
		}
		return this.density;
	}

	public void setDensity(float[] values) {
		float[] copy = new float[values.length];
		for (int i = 0; i < values.length; i++) copy[i] = Math.max(0f, values[i]);
		this.entityData.set(DENSITY, encode(copy));
		this.density = copy;
		this.due.clear();
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (DENSITY.equals(key)) this.density = null;
	}

	/** Единичное направление по курсу плюс {@code degrees} по часовой стрелке (так же растёт курс взгляда игрока). */
	public Vec3 direction(double degrees) {
		double r = Math.toRadians(yaw() + degrees);
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** Сколько стрел пришло за этот тик: сектор на каждую стрелу. */
	List<Integer> dueSectors(UUID player) {
		float[] rates = density();
		double[] left = this.due.computeIfAbsent(player, id -> {
			// случайное начало: иначе секторы с равной плотностью стреляют залпом, а первая стрела приходит только через целый период
			double[] start = new double[rates.length];
			for (int i = 0; i < start.length; i++) start[i] = this.random.nextDouble();
			return start;
		});
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < rates.length; i++) {
			left[i] += rates[i] / 20.0;
			while (left[i] >= 1) {
				left[i] -= 1;
				out.add(i);
			}
		}
		return out;
	}

	@Override
	public void placedBy(Player player, BlockPos pos) {
		super.placedBy(player, pos);
		setYaw(Math.round(player.getYRot() / TURN) * TURN);
	}

	@Override
	protected Item item() {
		return Barrages.BARRAGE_ITEM;
	}

	@Override
	protected void turn() {
		setYaw(Mth.wrapDegrees(Math.round(yaw() / TURN) * TURN + TURN));
	}

	@Override
	protected void tune(int sign) {
		setDistance(Math.clamp(distance() + sign * DISTANCE_STEP, MIN_DISTANCE, MAX_DISTANCE));
	}

	@Override
	public void announce(Player player) {
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.barrage_set", Math.floorMod(Math.round(yaw()), 360), Math.round(distance())));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putFloat("Yaw", yaw());
		out.putFloat("Radius", radius());
		out.putFloat("Distance", distance());
		out.putFloat("Spread", spread());
		out.putFloat("Damage", this.damage);
		out.putFloat("Speed", this.speed);
		out.putFloat("Miss", this.miss);
		out.putFloat("MinSpeed", this.minSpeed);
		out.putInt("Grace", this.grace);
		List<Float> list = new ArrayList<>();
		for (float v : density()) list.add(v);
		out.store("Density", Codec.FLOAT.listOf(), list);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		setYaw(in.getFloatOr("Yaw", yaw()));
		setRadius(in.getFloatOr("Radius", radius()));
		setDistance(in.getFloatOr("Distance", distance()));
		setSpread(in.getFloatOr("Spread", spread()));
		this.damage = Math.max(0f, in.getFloatOr("Damage", this.damage));
		this.speed = Math.max(0.2f, in.getFloatOr("Speed", this.speed));
		this.miss = Math.max(0f, in.getFloatOr("Miss", this.miss));
		this.minSpeed = Math.max(0f, in.getFloatOr("MinSpeed", this.minSpeed));
		this.grace = Math.max(0, in.getIntOr("Grace", this.grace));
		in.read("Density", Codec.FLOAT.listOf()).filter(list -> !list.isEmpty()).ifPresent(list -> {
			float[] values = new float[list.size()];
			for (int i = 0; i < values.length; i++) values[i] = list.get(i);
			setDensity(values);
		});
	}
}
