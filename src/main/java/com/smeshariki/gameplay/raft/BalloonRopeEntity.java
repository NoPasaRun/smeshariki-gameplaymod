package com.smeshariki.gameplay.raft;

import com.smeshariki.gameplay.rope.RopeEntity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Стропа управления шаром: тарзанка с ручкой-треугольником на конце. Висит, качается, хватается и отпускается как обычная
 * верёвка; пока игрок её держит, колесо мыши управляет шаром, к которому она привязана (см. {@code Balloons}). Каждый
 * сработавший щелчок тратит единицу топлива; пустая стропа шаром не управляет. Держа стропу, ПКМ топливом печи заправляет её.
 */
public class BalloonRopeEntity extends RopeEntity {
	private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(BalloonRopeEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Integer> FUEL = SynchedEntityData.defineId(BalloonRopeEntity.class, EntityDataSerializers.INT);
	/** Сколько тиков горения печи даёт единицу топлива: уголь (1600) — 16 единиц. */
	public static final int BURN_PER_FUEL = 100;

	public BalloonRopeEntity(EntityType<? extends BalloonRopeEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(KIND, (byte) BalloonRopeItem.Kind.LIFT.ordinal());
		builder.define(FUEL, BalloonRopeItem.MAX_FUEL);
	}

	public int fuel() {
		return this.entityData.get(FUEL);
	}

	public void setFuel(int fuel) {
		this.entityData.set(FUEL, Math.clamp(fuel, 0, BalloonRopeItem.MAX_FUEL));
	}

	@Override
	public boolean takesItem(ItemStack stack) {
		return level().fuelValues().isFuel(stack);
	}

	/** Заправка: берёт по одному предмету за щелчок, пока он помещается в запас целиком или запас не полон. */
	@Override
	public void giveItem(Player player, ItemStack stack) {
		int units = Math.max(1, level().fuelValues().burnDuration(stack) / BURN_PER_FUEL);
		if (fuel() >= BalloonRopeItem.MAX_FUEL) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.balloon_fuel_full", fuel(), BalloonRopeItem.MAX_FUEL));
			return;
		}
		setFuel(fuel() + units);
		if (!player.hasInfiniteMaterials()) {
			var remainder = stack.getItem().getCraftingRemainder();
			stack.shrink(1);
			// ведро лавы оставляет пустое ведро
			if (remainder != null) {
				ItemStack rest = remainder.create();
				if (!rest.isEmpty() && !player.getInventory().add(rest)) player.drop(rest, false);
			}
		}
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.balloon_fuel", fuel(), BalloonRopeItem.MAX_FUEL));
	}

	public BalloonRopeItem.Kind kind() {
		return BalloonRopeItem.Kind.values()[Math.floorMod(this.entityData.get(KIND), BalloonRopeItem.Kind.values().length)];
	}

	public void setKind(BalloonRopeItem.Kind kind) {
		this.entityData.set(KIND, (byte) kind.ordinal());
	}

	/** Шар, к которому привязана стропа, или null, если он не загружен. */
	public BalloonEntity balloon() {
		return anchorRaft() instanceof BalloonEntity b ? b : null;
	}

	@Override
	protected ItemStack dropStack() {
		return BalloonRopeItem.withFuel(new ItemStack(kind() == BalloonRopeItem.Kind.LIFT ? Balloons.LIFT_ROPE : Balloons.HELM_ROPE), fuel());
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putInt("Kind", kind().ordinal());
		out.putInt("Fuel", fuel());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		this.entityData.set(KIND, (byte) in.getIntOr("Kind", 0));
		setFuel(in.getIntOr("Fuel", BalloonRopeItem.MAX_FUEL));
	}
}
