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
 * Мёртвая зона: ящик с центром в позиции сущности, половины сторон {@code HalfX}, {@code HalfY}, {@code HalfZ}. Воздух в зону не входит: её клетки — непустые
 * блоки ящика (клетки, чей центр внутри него), и игрок в зоне, только если стоит на таком блоке или находится в нём (вода, трава). Игрока в зоне стрелы бьют со всех сторон с
 * удаления {@code Distance}, с упреждением, {@code Rate} стрел в секунду со скоростью {@code Speed} и разбросом {@code Spread} блоков. Обстрел по кольцу на него в это время не действует. Так
 * ставятся границы маршрута: вокруг коридора обстрел пугает, а за край коридора лучше не ходить.
 * <p>
 * Настраивается, например: {@code /data merge entity @e[type=gameplaymod:deadzone,sort=nearest,limit=1] {HalfX:20f,HalfY:10f,HalfZ:5f,Rate:6f,Damage:3f}}
 */
public class DeadzoneEntity extends FireMarkerEntity {
	private static final float[] SIZES = { 4, 8, 16, 32 };

	private static final EntityDataAccessor<Float> HALF_X = SynchedEntityData.defineId(DeadzoneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HALF_Y = SynchedEntityData.defineId(DeadzoneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HALF_Z = SynchedEntityData.defineId(DeadzoneEntity.class, EntityDataSerializers.FLOAT);

	private float rate = 4f, spread = 0.5f, damage = 2f, distance = 12f, speed = 1.8f;
	/** Зона гибели ({@code Kill:1b}): не стреляет; всё живое, коснувшееся ящика, гибнет, брёвна реки исчезают. Ящик целиком, воздух тоже. */
	private boolean kill;
	private double due;

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

	public boolean isKill() {
		return this.kill;
	}

	/** Ящик зоны, целиком (для зоны гибели). */
	public AABB box() {
		return new AABB(getX() - halfX(), getY() - halfY(), getZ() - halfZ(), getX() + halfX(), getY() + halfY(), getZ() + halfZ());
	}

	public float spread() {
		return this.spread;
	}

	public float damage() {
		return this.damage;
	}

	public float distance() {
		return this.distance;
	}

	public float speed() {
		return this.speed;
	}

	/** Насколько ниже ног ищется блок зоны, на котором стоит тело. */
	private static final double FEET = 0.2;

	/** Клетки зоны (центр внутри ящика): нижний и верхний углы включительно. */
	public static BlockPos[] cells(Vec3 center, double hx, double hy, double hz) {
		return new BlockPos[] { new BlockPos(Mth.ceil(center.x - hx - 0.5), Mth.ceil(center.y - hy - 0.5), Mth.ceil(center.z - hz - 0.5)),
				new BlockPos(Mth.floor(center.x + hx - 0.5), Mth.floor(center.y + hy - 0.5), Mth.floor(center.z + hz - 0.5)) };
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

	/** Сколько стрел на этот тик; зона одна на всех, стрелы идут по очереди по стоящим внутри. */
	int dueShots(int players) {
		this.due += this.rate * players / 20.0;
		int shots = (int) this.due;
		this.due -= shots;
		return shots;
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
		out.putBoolean("Kill", this.kill);
		out.putFloat("Rate", this.rate);
		out.putFloat("Spread", this.spread);
		out.putFloat("Damage", this.damage);
		out.putFloat("Distance", this.distance);
		out.putFloat("Speed", this.speed);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		setHalf(in.getFloatOr("HalfX", halfX()), in.getFloatOr("HalfY", halfY()), in.getFloatOr("HalfZ", halfZ()));
		this.kill = in.getBooleanOr("Kill", this.kill);
		this.rate = Math.max(0f, in.getFloatOr("Rate", this.rate));
		this.spread = Math.max(0f, in.getFloatOr("Spread", this.spread));
		this.damage = Math.max(0f, in.getFloatOr("Damage", this.damage));
		this.distance = Math.max(3f, in.getFloatOr("Distance", this.distance));
		this.speed = Math.max(0.2f, in.getFloatOr("Speed", this.speed));
	}
}
