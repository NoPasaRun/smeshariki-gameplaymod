package com.smeshariki.gameplay.current;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.phys.Vec3;

/**
 * Маркер в клетке воды, задающий направление течения. Сам ничего не двигает — течение считает {@link CurrentField}.
 * Рисуется только пока в руке маркер. ПКМ с маркером в руке поворачивает его на {@link #TURN} градусов, ЛКМ убирает.
 */
public class CurrentMarkerEntity extends Entity {
	/** Курс привязывается к этому шагу, и на столько ЛКМ поворачивает маркер, градусов. */
	public static final float TURN = 30f;

	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(CurrentMarkerEntity.class, EntityDataSerializers.FLOAT);

	public CurrentMarkerEntity(EntityType<? extends CurrentMarkerEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
		this.setInvulnerable(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(YAW, 0f);
	}

	public float yaw() {
		return this.entityData.get(YAW);
	}

	/** Единичный горизонтальный вектор течения (курс отсчитывается как у взгляда игрока). */
	public Vec3 direction() {
		double r = Math.toRadians(yaw());
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	public static float snap(float yaw) {
		return Math.round(yaw / TURN) * TURN;
	}

	public void setYaw(float yaw) {
		this.entityData.set(YAW, snap(yaw));
	}

	private static boolean holdsMarker(Player player) {
		return player.getMainHandItem().getItem() instanceof CurrentMarkerItem;
	}

	private void announce(Player player) {
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.current_set", Math.floorMod(Math.round(yaw()), 360)));
	}

	/** ПКМ маркером по маркеру: повернуть на {@link #TURN}. */
	public void turn(Player player) {
		setYaw(yaw() + TURN);
		announce(player);
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
		if (!player.hasInfiniteMaterials()) spawnAtLocation(level, new ItemStack(Currents.ITEM));
		discard();
		return true;
	}

	/** Чтобы маркер не мешал бить и копать, на клиенте он «твёрдый» для прицела, только пока в руке маркер. */
	@Override
	public boolean isPickable() {
		return !level().isClientSide() || Currents.holdsMarkerOnClient.getAsBoolean();
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
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		setYaw(in.getFloatOr("Yaw", 0f));
	}
}
