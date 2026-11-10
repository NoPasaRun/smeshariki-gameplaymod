package com.smeshariki.gameplay.smoke;

import com.smeshariki.gameplay.barrage.FireMarkerEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Дым: столб от позиции сущности вверх на {@code Height} блоков, радиус {@code Radius}. Клиент сам пускает в нём клубы густого дыма
 * ({@link Smokes#PARTICLE}, текстура {@code thick_smoke}): {@code Rate} клубов в секунду, появляются по всему столбу, медленно ползут
 * вверх, проявляются и тают, поэтому столбы друг над другом сливаются в один дым. Ничего не делает с игроками и блоками.
 * С маркером в руке: ПКМ — радиус, Ctrl+ПКМ и Ctrl+ЛКМ — высота ±2, ЛКМ — убрать.
 * Настраивается: {@code /data merge entity @e[type=gameplaymod:smoke,sort=nearest,limit=1] {Radius:4f,Height:8f,Rate:10f}}
 */
public class SmokeEntity extends FireMarkerEntity {
	private static final float[] RADII = { 1, 2, 3, 4, 6, 8 };
	/** Клубы появляются, только если игрок ближе этого: дальше их всё равно почти не видно, а частиц много. */
	private static final double VIEW = 128;
	/** Скорость подъёма клуба, блоков за тик. */
	public static final double RISE = 0.04;

	private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(SmokeEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(SmokeEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> RATE = SynchedEntityData.defineId(SmokeEntity.class, EntityDataSerializers.FLOAT);

	private double due;

	public SmokeEntity(EntityType<? extends SmokeEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(RADIUS, 2f);
		builder.define(HEIGHT, 6f);
		builder.define(RATE, 6f);
	}

	public float radius() {
		return this.entityData.get(RADIUS);
	}

	public float height() {
		return this.entityData.get(HEIGHT);
	}

	public float rate() {
		return this.entityData.get(RATE);
	}

	public void setShape(float radius, float height, float rate) {
		this.entityData.set(RADIUS, Math.clamp(radius, 0.5f, 32f));
		this.entityData.set(HEIGHT, Math.clamp(height, 1f, 64f));
		this.entityData.set(RATE, Math.clamp(rate, 0f, 200f));
	}

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide()) return;
		Player near = level().getNearestPlayer(getX(), getY() + height() / 2, getZ(), VIEW, false);
		if (near == null) return;
		this.due += rate() / 20.0;
		while (this.due >= 1) {
			this.due -= 1;
			puff();
		}
	}

	/** Клуб в случайной точке столба: размер — около радиуса, чтобы несколько клубов закрывали сечение. */
	private void puff() {
		double a = this.random.nextDouble() * Math.PI * 2, r = radius() * Math.sqrt(this.random.nextDouble()) * 0.7;
		double x = getX() + Math.cos(a) * r, z = getZ() + Math.sin(a) * r;
		double y = getY() + this.random.nextDouble() * height();
		double size = radius() * (0.9 + this.random.nextDouble() * 0.6) + 0.6;
		// параметры частицы: dx — размер клуба, dy — скорость подъёма (см. client/SmokeParticle)
		level().addAlwaysVisibleParticle(Smokes.PARTICLE, true, x, y, z, size, RISE * (0.7 + this.random.nextDouble() * 0.6), 0);
	}

	@Override
	public void placedBy(Player player, BlockPos pos) {
		setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
	}

	@Override
	protected Item item() {
		return Smokes.ITEM;
	}

	@Override
	protected void turn() {
		float next = RADII[0];
		for (float r : RADII) {
			if (r > radius() + 1e-3f) {
				next = r;
				break;
			}
		}
		setShape(next, height(), rate());
	}

	@Override
	protected void tune(int sign) {
		setShape(radius(), height() + sign * 2f, rate());
	}

	@Override
	public void announce(Player player) {
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.smoke_set", Math.round(radius() * 10) / 10f, Math.round(height()), Math.round(rate())));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putFloat("Radius", radius());
		out.putFloat("Height", height());
		out.putFloat("Rate", rate());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		setShape(in.getFloatOr("Radius", radius()), in.getFloatOr("Height", height()), in.getFloatOr("Rate", rate()));
	}
}
