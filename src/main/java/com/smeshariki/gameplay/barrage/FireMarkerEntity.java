package com.smeshariki.gameplay.barrage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Общее у обстрела и мёртвой зоны: невидимый маркер, виден и задевается прицелом, только пока в руке маркер обстрела или зоны. С маркером в
 * руке: ПКМ — {@link #turn}, Ctrl+ПКМ и Ctrl+ЛКМ — {@link #tune}, ЛКМ — убрать. Всё остальное задаётся данными сущности ({@code /data}).
 */
public abstract class FireMarkerEntity extends Entity implements com.smeshariki.gameplay.api.Grouped {
	protected FireMarkerEntity(EntityType<? extends FireMarkerEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
		this.setInvulnerable(true);
	}

	/** Имя группы: непустую стреляет, только пока её включил для игрока {@link Barrages#setActiveGroups} (триггеры LevelMod). Пустая — стреляет всегда. */
	private String group = "";

	@Override
	public String group() {
		return this.group;
	}

	@Override
	public void setGroup(String group) {
		this.group = group;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putString("Group", this.group);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		this.group = in.getStringOr("Group", this.group);
	}

	/** Только что поставлен предметом: своё место и курс, по умолчанию клетка под прицелом. */
	public void placedBy(Player player, BlockPos pos) {
		setPos(pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5);
	}

	protected abstract Item item();

	/** ПКМ маркером. */
	protected abstract void turn();

	/** Ctrl+ПКМ (+1) и Ctrl+ЛКМ (−1). */
	protected abstract void tune(int sign);

	/** Подпись над панелью с текущими настройками. */
	public abstract void announce(Player player);

	private boolean holdsMarker(Player player) {
		return Barrages.holdsMarker(player);
	}

	protected static boolean holdsCtrl(Player player) {
		return player instanceof ServerPlayer sp && sp.getLastClientInput().sprint();
	}

	protected static boolean holdsShift(Player player) {
		return player instanceof ServerPlayer sp && sp.getLastClientInput().shift();
	}

	/** ПКМ маркером по этому маркеру (сервер); своё управление у маркера может быть переопределено. */
	protected void click(Player player) {
		if (holdsCtrl(player)) tune(1);
		else turn();
		announce(player);
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (hand != InteractionHand.MAIN_HAND || !holdsMarker(player)) return InteractionResult.PASS;
		if (!level().isClientSide()) click(player);
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		if (!(source.getEntity() instanceof Player player) || !holdsMarker(player)) return false;
		return hit(level, player);
	}

	/** ЛКМ маркером по этому маркеру (сервер): Ctrl — {@link #tune} −1, иначе убрать. Возвращает, убран ли. */
	protected boolean hit(ServerLevel level, Player player) {
		if (holdsCtrl(player)) {
			tune(-1);
			announce(player);
			return false;
		}
		if (!player.hasInfiniteMaterials()) spawnAtLocation(level, new ItemStack(item()));
		discard();
		return true;
	}

	@Override
	public boolean isPickable() {
		return !level().isClientSide() || Barrages.holdsMarkerOnClient.getAsBoolean();
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 128 * 128;
	}
}
