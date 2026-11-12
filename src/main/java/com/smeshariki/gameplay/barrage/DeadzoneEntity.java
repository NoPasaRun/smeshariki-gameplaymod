package com.smeshariki.gameplay.barrage;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Мёртвая зона: ящик с центром в позиции сущности, половины сторон {@code HalfX}, {@code HalfY}, {@code HalfZ}. Игрок в зоне гибнет сразу (вселённого убивает его
 * персонаж, игроку выбирают нового); творческий режим и наблюдатели не в счёт. Для игрока воздух в зону не входит: её клетки — непустые блоки ящика, и он в зоне,
 * только если стоит на таком блоке или находится в нём (вода, трава). Остальное живое и брёвна реки — всё, что коснулось ящика (воздух тоже). Стрелами зона не стреляет:
 * это делает {@link BarrageEntity}.
 * <p>
 * Настраивается, например: {@code /data merge entity @e[type=gameplaymod:deadzone,sort=nearest,limit=1] {HalfX:20f,HalfY:10f,HalfZ:5f}}
 */
public class DeadzoneEntity extends FireMarkerEntity {
	private static final float[] SIZES = { 4, 8, 16, 32 };

	private static final EntityDataAccessor<Float> HALF_X = SynchedEntityData.defineId(DeadzoneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HALF_Y = SynchedEntityData.defineId(DeadzoneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HALF_Z = SynchedEntityData.defineId(DeadzoneEntity.class, EntityDataSerializers.FLOAT);

	public DeadzoneEntity(EntityType<? extends DeadzoneEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(HALF_X, 8f);
		builder.define(HALF_Y, 8f);
		builder.define(HALF_Z, 8f);
	}

	public float halfX() {
		return this.entityData.get(HALF_X);
	}

	public float halfY() {
		return this.entityData.get(HALF_Y);
	}

	public float halfZ() {
		return this.entityData.get(HALF_Z);
	}

	public void setHalf(float x, float y, float z) {
		this.entityData.set(HALF_X, Math.max(0.5f, x));
		this.entityData.set(HALF_Y, Math.max(0.5f, y));
		this.entityData.set(HALF_Z, Math.max(0.5f, z));
	}

	/** Ящик зоны, целиком. */
	public AABB box() {
		return new AABB(getX() - halfX(), getY() - halfY(), getZ() - halfZ(), getX() + halfX(), getY() + halfY(), getZ() + halfZ());
	}

	/** Насколько ниже ног ищется блок зоны, на котором стоит тело. */
	private static final double FEET = 0.2;

	/** Клетки зоны: любой блок, часть которого лежит внутри ящика (не только касание грани); нижний и верхний углы включительно. */
	public static BlockPos[] cells(Vec3 center, double hx, double hy, double hz) {
		double e = 1e-6;
		return new BlockPos[] { new BlockPos(Mth.floor(center.x - hx + e), Mth.floor(center.y - hy + e), Mth.floor(center.z - hz + e)),
				new BlockPos(Mth.ceil(center.x + hx - e) - 1, Mth.ceil(center.y + hy - e) - 1, Mth.ceil(center.z + hz - e) - 1) };
	}

	public BlockPos[] cells() {
		return cells(position(), halfX(), halfY(), halfZ());
	}

	/** Тело стоит на непустом блоке зоны или находится в нём; воздух ящика в зону не входит. */
	public boolean contains(AABB body) {
		BlockPos[] c = cells();
		AABB probe = body.expandTowards(0, -FEET, 0).deflate(1e-4);
		int x0 = Math.max(c[0].getX(), Mth.floor(probe.minX)), x1 = Math.min(c[1].getX(), Mth.floor(probe.maxX));
		int y0 = Math.max(c[0].getY(), Mth.floor(probe.minY)), y1 = Math.min(c[1].getY(), Mth.floor(probe.maxY));
		int z0 = Math.max(c[0].getZ(), Mth.floor(probe.minZ)), z1 = Math.min(c[1].getZ(), Mth.floor(probe.maxZ));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++)
			if (!level().getBlockState(pos.set(x, y, z)).isAir()) return true;
		return false;
	}

	/** Поставлен предметом: размер по горизонтали — выбранный игроком колесом ({@link DeadzoneScroll}). */
	@Override
	public void placedBy(Player player, BlockPos pos) {
		super.placedBy(player, pos);
		float half = DeadzoneScroll.placeHalf(player.getUUID());
		setHalf(half, halfY(), half);
	}

	@Override
	protected Item item() {
		return Barrages.DEADZONE_ITEM;
	}

	/** ПКМ: следующий размер по горизонтали. */
	@Override
	protected void turn() {
		float next = SIZES[0];
		for (float size : SIZES) {
			if (size > halfX()) {
				next = size;
				break;
			}
		}
		setHalf(next, halfY(), next);
	}

	/** Ctrl+ПКМ и Ctrl+ЛКМ: высота. */
	@Override
	protected void tune(int sign) {
		setHalf(halfX(), Math.clamp(halfY() + sign * 2f, 2f, 32f), halfZ());
	}

	@Override
	public void announce(Player player) {
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.deadzone_set", Math.round(halfX() * 2), Math.round(halfY() * 2), Math.round(halfZ() * 2)));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putFloat("HalfX", halfX());
		out.putFloat("HalfY", halfY());
		out.putFloat("HalfZ", halfZ());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		setHalf(in.getFloatOr("HalfX", halfX()), in.getFloatOr("HalfY", halfY()), in.getFloatOr("HalfZ", halfZ()));
	}
}
