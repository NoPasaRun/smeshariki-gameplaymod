package com.smeshariki.gameplay.wind;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Маркер ветра: ветер вдоль стрелки в {@link #RADIUS} блоках от центра, сильнее к центру ({@link #strength}). Сам ветер виден всегда, стрелка — пока в
 * руке маркер. С маркером в руке: ПКМ — поворот вокруг Y на {@link #TURN} градусов, ЛКМ — убрать; с Ctrl стрелка вместе с
 * плоскостью наклоняется на {@link #TURN} градусов: ПКМ — вверх, ЛКМ — вниз, до отвесной ({@link #MAX_PITCH}).
 */
public class WindMarkerEntity extends Entity {
	public static final float TURN = 30f;
	/** Загруженные маркеры сервера; у клиента ветра нет, он получает его с сервера в данных шара. */
	static final List<WindMarkerEntity> SERVER_MARKERS = new CopyOnWriteArrayList<>();

	/** Половина длины и ширины плоскости ветра и половина её толщины, блоков. */
	/** Зона ветра: до стольких блоков от центра маркера (2026-11-05; было плоскость 3×3×1). */
	public static final double RADIUS = 5.0;
	/** Сила на краю зоны, доля от полной (5 блоков/с из 25). */
	public static final double EDGE_STRENGTH = 0.2;
	/** Позиция сущности — низ её клетки (хитбокс на всю клетку, чтобы было легко попасть), плоскость проходит через середину. */
	public static final double CENTER_Y = 0.5;
	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(WindMarkerEntity.class, EntityDataSerializers.FLOAT);
	public static final float MAX_PITCH = 90f;
	/** Наклон стрелки, градусов: больше нуля — вверх. */
	private static final EntityDataAccessor<Float> PITCH = SynchedEntityData.defineId(WindMarkerEntity.class, EntityDataSerializers.FLOAT);

	public WindMarkerEntity(EntityType<? extends WindMarkerEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
		this.setInvulnerable(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(YAW, 0f);
		builder.define(PITCH, 0f);
	}

	public float yaw() {
		return this.entityData.get(YAW);
	}

	/** Единичный вектор ветра вдоль стрелки (курс отсчитывается как у взгляда игрока). */
	public Vec3 direction() {
		double r = Math.toRadians(yaw()), p = Math.toRadians(pitch());
		return new Vec3(-Math.sin(r) * Math.cos(p), Math.sin(p), Math.cos(r) * Math.cos(p));
	}

	/** Единичный вектор вбок по плоскости ветра: всегда горизонтальный, поперёк курса. */
	public Vec3 side() {
		double r = Math.toRadians(yaw());
		return new Vec3(-Math.cos(r), 0, -Math.sin(r));
	}

	public float pitch() {
		return this.entityData.get(PITCH);
	}

	public void setPitch(float pitch) {
		this.entityData.set(PITCH, Math.clamp(Math.round(pitch / TURN) * TURN, -MAX_PITCH, MAX_PITCH));
	}

	/**
	 * Сила ветра для ящика, доля от {@link com.smeshariki.gameplay.raft.BalloonEntity#WIND_SPEED}: в центре маркера 1, к краю зоны
	 * ({@link #RADIUS} от центра до ближайшей точки ящика) линейно до {@link #EDGE_STRENGTH}; дальше 0.
	 */
	public double strength(AABB box) {
		Vec3 c = position().add(0, CENTER_Y, 0);
		double dx = Math.max(Math.max(box.minX - c.x, 0), c.x - box.maxX);
		double dy = Math.max(Math.max(box.minY - c.y, 0), c.y - box.maxY);
		double dz = Math.max(Math.max(box.minZ - c.z, 0), c.z - box.maxZ);
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		return d > RADIUS ? 0 : 1 - (1 - EDGE_STRENGTH) * d / RADIUS;
	}

	/** Зажат ли у игрока Ctrl; сервер знает клавиши из пакета ввода. */
	private static boolean holdsCtrl(Player player) {
		return player instanceof ServerPlayer sp && sp.getLastClientInput().sprint();
	}

	public void setYaw(float yaw) {
		this.entityData.set(YAW, Math.round(yaw / TURN) * TURN);
	}

	private static boolean holdsMarker(Player player) {
		return player.getMainHandItem().getItem() instanceof WindMarkerItem;
	}

	/** Поворот на {@link #TURN}. */
	public void turn(Player player) {
		if (holdsCtrl(player)) setPitch(pitch() + TURN);
		else setYaw(yaw() + TURN);
		announce(player);
	}

	void announce(Player player) {
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.wind_set", Math.floorMod(Math.round(yaw()), 360), Math.round(pitch())));
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (hand != InteractionHand.MAIN_HAND || !holdsMarker(player)) return InteractionResult.PASS;
		if (!level().isClientSide()) turn(player);
		return InteractionResult.SUCCESS;
	}

	/** ЛКМ с маркером в руке: убрать маркер. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (!(source.getEntity() instanceof Player player) || !holdsMarker(player)) return false;
		if (holdsCtrl(player)) {
			setPitch(pitch() - TURN);
			announce(player);
			return false;
		}
		if (!player.hasInfiniteMaterials()) spawnAtLocation(level, new ItemStack(Winds.ITEM));
		discard();
		return true;
	}

	/** Чтобы маркер не мешал бить и копать, на клиенте он «твёрдый» для прицела, только пока в руке маркер. */
	@Override
	public boolean isPickable() {
		return !level().isClientSide() || Winds.holdsMarkerOnClient.getAsBoolean();
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 64 * 64;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putFloat("Yaw", yaw());
		out.putFloat("Pitch", pitch());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		setYaw(in.getFloatOr("Yaw", 0f));
		setPitch(in.getFloatOr("Pitch", 0f));
	}
}
