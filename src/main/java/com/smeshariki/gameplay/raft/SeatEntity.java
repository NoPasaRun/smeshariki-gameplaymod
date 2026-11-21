package com.smeshariki.gameplay.raft;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Невидимое сиденье на плоту, шаре или бревне: пассажир плота в точке его палубы ({@link #local}, в системе плота), на нём сидит игрок
 * или вселённый персонаж. Плот ставит сиденье сам ({@link RaftEntity#positionRider}), ваниль — сидящего на сиденье. Опустевшее или
 * оставшееся без плота исчезает; в мир не сохраняется. Shift сидящего (у вселённого — его игрока) встаёт.
 */
public class SeatEntity extends Entity {
	private static final EntityDataAccessor<Vector3fc> LOCAL = SynchedEntityData.defineId(SeatEntity.class, EntityDataSerializers.VECTOR3);

	public SeatEntity(EntityType<? extends SeatEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setInvulnerable(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(LOCAL, new Vector3f());
	}

	public Vec3 local() {
		Vector3fc v = this.entityData.get(LOCAL);
		return new Vec3(v.x(), v.y(), v.z());
	}

	public void setLocal(Vec3 local) {
		this.entityData.set(LOCAL, new Vector3f((float) local.x, (float) local.y, (float) local.z));
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) return;
		if (!(getVehicle() instanceof RaftEntity raft) || raft.destroyed() || getPassengers().isEmpty()) {
			discard();
			return;
		}
		for (Entity rider : getPassengers()) {
			Player driver = rider instanceof Player p ? p : rider.getControllingPassenger() instanceof Player p ? p : null;
			if (driver instanceof ServerPlayer sp && (sp.isShiftKeyDown() || sp.getLastClientInput().shift())) rider.stopRiding();
		}
	}

	/** Сидящий — сам игрок или вселённый персонаж; кто сидит у этого игрока. */
	public static Entity riderOf(Player player) {
		return player.getVehicle() instanceof CharacterEntity c ? c : player;
	}

	/** Сиденье, на котором сидит игрок (или его вселённый персонаж), или null. */
	public static SeatEntity of(Player player) {
		return riderOf(player).getVehicle() instanceof SeatEntity seat ? seat : null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// точка сиденья — поверхность палубы: у игрока точка крепления на высоте таза, ноги в позе сидя идут вперёд
		return Vec3.ZERO;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
	}
}
