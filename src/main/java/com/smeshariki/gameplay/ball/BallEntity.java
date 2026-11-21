package com.smeshariki.gameplay.ball;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.api.Mechanics;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Мяч: летит, отскакивает от блоков, катится, плавает. Движение считает только сервер, клиент сглаживает присланные позиции.
 * <ul>
 * <li>Локация ({@code Place}, имя локации-области LevelMod через {@link Mechanics#place}): вылетев за её контур, мяч встаёт на границу (на
 * то место, где пересёк её); в воздухе падает отвесно вдоль неё и замирает на земле или в воде — без скорости и тяжести, пока его не пнут или не поднимут. Пустая — границ нет. Ставится командой
 * {@code /ball place}, {@code /data merge entity} или переходит из предмета.</li>
 * <li>Коснувшись паутины, лопается (с частицами и звуком), предмета не остаётся.</li>
 * <li>ПКМ — поднять в предмет (локация переходит в предмет); ЛКМ — удар при отпускании, пока зажата, от мяча тянется стрелка направления и силы ({@link BallKick}, клиент {@code BallKickClient}); набежавшее существо толкает его. ЛКМ мячом в руке по блоку — поставить в точку ({@link BallPlace}).</li>
 * </ul>
 */
public class BallEntity extends Entity implements ItemSupplier {
	private static final EntityDataAccessor<Boolean> FROZEN = SynchedEntityData.defineId(BallEntity.class, EntityDataSerializers.BOOLEAN);
	/** Вылетел за границу в воздухе: падает отвесно на ней, у земли или воды замрёт (синхронизируется: клиент считает полёт сам). */
	private static final EntityDataAccessor<Boolean> DROPPING = SynchedEntityData.defineId(BallEntity.class, EntityDataSerializers.BOOLEAN);
	/** Дальше этого (зазор между коробками игрока и мяча, блоков) мяч нельзя ни поднять, ни ударить. */
	public static final double REACH = 2.0;

	public static final double GRAVITY = 0.04;
	public static final double AIR_DRAG = 0.99;
	/** Доля скорости поперёк поверхности после отскока. */
	static final double BOUNCE = 0.65;
	/** Ниже этой скорости по вертикали мяч не подпрыгивает, а ложится. */
	static final double REST_VY = 0.08;
	/** Качение по земле: доля скорости за тик. */
	static final double ROLL = 0.92;
	static final double WATER_DRAG = 0.85;
	static final double BUOYANCY = 0.06;
	public static final double KICK = 0.3;
	static final double KICK_UP = 0.12;
	/** Толчок от набежавшего существа: доля его скорости и наименьший. */
	static final double BUMP = 1.4;
	static final double BUMP_MIN = 0.15;
	/** Шаг поиска места выхода за границу, блоков. */
	private static final double EDGE_STEP = 0.1;
	/** Как часто перечитывать локацию (её могут перерисовать). */
	private static final int PLACE_REFRESH = 20;

	private String place = "";
	private @Nullable Mechanics.Area area;
	private int areaAge = PLACE_REFRESH;
	/** Радиус шара (половина коробки). */
	public static final float RADIUS = 0.3f;
	/** Поворот качения: только клиент, для рисунка; {@link #spinO} — на прошлом тике. */
	public final org.joml.Quaternionf spin = new org.joml.Quaternionf(), spinO = new org.joml.Quaternionf();

	public BallEntity(EntityType<? extends BallEntity> type, Level level) {
		super(type, level);
	}

	public String place() {
		return place;
	}

	public void setPlace(String place) {
		this.place = place == null ? "" : place;
		this.areaAge = PLACE_REFRESH;
		setDropping(false);
		setFrozen(false);
	}

	public boolean frozen() {
		return entityData.get(FROZEN);
	}

	void setFrozen(boolean frozen) {
		entityData.set(FROZEN, frozen);
	}

	private boolean dropping() {
		return entityData.get(DROPPING);
	}

	private void setDropping(boolean dropping) {
		entityData.set(DROPPING, dropping);
	}

	/** Зазор между коробками тела игрока (или его персонажа) и мяча не больше {@link #REACH} (+ {@code slack}). */
	public boolean withinReach(Entity player, double slack) {
		AABB a = player.getRootVehicle().getBoundingBox().minmax(player.getBoundingBox()), b = getBoundingBox();
		double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
		double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
		double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
		return dx * dx + dy * dy + dz * dz <= (REACH + slack) * (REACH + slack);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(FROZEN, false);
		builder.define(DROPPING, false);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putString("Place", place);
		out.putBoolean("Frozen", frozen());
		out.putBoolean("Dropping", dropping());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		place = in.getStringOr("Place", "");
		areaAge = PLACE_REFRESH;
		setFrozen(in.getBooleanOr("Frozen", false));
		setDropping(in.getBooleanOr("Dropping", false));
	}

	@Override
	public ItemStack getItem() {
		return new ItemStack(Balls.ITEM);
	}

	@Override
	public void tick() {
		super.tick();
		// полёт считают обе стороны одинаково (как у предметов): иначе удар и бросок видны с задержкой на путь до сервера и обратно;
		// сервер поправляет клиента позициями, а граница, паутина и толчки — только на сервере
		Vec3 before = position();
		simulate();
		if (level().isClientSide()) roll(position().subtract(before));
	}

	private void simulate() {
		ServerLevel level = level() instanceof ServerLevel server ? server : null;
		if (level != null && touchesWeb(getBoundingBox())) {
			pop(level);
			return;
		}
		if (frozen()) return;
		if (level != null) bump();
		Vec3 start = position();
		Vec3 v = getDeltaMovement();
		boolean inWater = isInWater();
		if (inWater) {
			v = v.scale(WATER_DRAG).add(0, BUOYANCY - GRAVITY * 0.5, 0);
		} else {
			v = v.add(0, -GRAVITY, 0);
		}
		boolean dropping = dropping();
		if (dropping) v = new Vec3(0, Math.min(v.y, 0), 0);
		Vec3 wanted = v;
		// паутину проверяем на всём пути: быстрый мяч иначе пролетит её за тик
		if (level != null && touchesWeb(getBoundingBox().expandTowards(wanted))) {
			pop(level);
			return;
		}
		move(MoverType.SELF, wanted);
		Vec3 moved = position().subtract(start);
		double vx = wanted.x, vy = wanted.y, vz = wanted.z;
		if (Math.abs(moved.x - wanted.x) > 1e-6) vx = -wanted.x * BOUNCE;
		if (Math.abs(moved.z - wanted.z) > 1e-6) vz = -wanted.z * BOUNCE;
		if (Math.abs(moved.y - wanted.y) > 1e-6) {
			vy = Math.abs(wanted.y) > REST_VY ? -wanted.y * BOUNCE : 0;
			if (level != null && Math.abs(wanted.y) > 0.2) playSound(SoundEvents.SLIME_JUMP_SMALL, (float) Math.min(1, Math.abs(wanted.y)), 1.4f);
		}
		if (onGround()) {
			vx *= ROLL;
			vz *= ROLL;
		} else if (!inWater) {
			vx *= AIR_DRAG;
			vy *= AIR_DRAG;
			vz *= AIR_DRAG;
		}
		if (Math.abs(vx) < 1e-3) vx = 0;
		if (Math.abs(vz) < 1e-3) vz = 0;
		setDeltaMovement(vx, vy, vz);
		if (dropping) {
			setDeltaMovement(0, getDeltaMovement().y, 0);
			if (level != null && (onGround() || isInWater())) settle();
		}
		if (level != null) keepInside(level, start);
	}

	/** Катится без проскальзывания: ось — поперёк хода, угол — путь / радиус. */
	private void roll(Vec3 moved) {
		spinO.set(spin);
		double len = Math.hypot(moved.x, moved.z);
		if (len < 1e-4) return;
		spin.premul(new org.joml.Quaternionf().rotateAxis((float) (len / RADIUS), (float) (moved.z / len), 0f, (float) (-moved.x / len)));
		spin.normalize();
	}

	private boolean touchesWeb(AABB box) {
		return level().getBlockStates(box).anyMatch(state -> state.is(Blocks.COBWEB));
	}

	/** Мяч лопнул: звук, клочья, пропал. */
	public void pop(ServerLevel level) {
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.WOOL_BREAK, SoundSource.NEUTRAL, 1.0f, 1.6f);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.CHICKEN_EGG, SoundSource.NEUTRAL, 0.8f, 0.5f);
		level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, Balls.ITEM), getX(), getY() + getBbHeight() / 2, getZ(), 16, 0.2, 0.2, 0.2, 0.15);
		level.sendParticles(ParticleTypes.POOF, getX(), getY() + getBbHeight() / 2, getZ(), 6, 0.15, 0.15, 0.15, 0.02);
		discard();
	}

	private @Nullable Mechanics.Area area(ServerLevel level) {
		if (place.isEmpty()) return null;
		if (++areaAge >= PLACE_REFRESH) {
			areaAge = 0;
			area = Mechanics.place(level.getServer(), place);
		}
		return area != null && area.dimension() == level.dimension() ? area : null;
	}

	/** Вылетел за контур локации — на границу, где пересёк её, и замереть. */
	private void keepInside(ServerLevel level, Vec3 start) {
		Mechanics.Area area = area(level);
		if (area == null || area.contains(position())) return;
		Vec3 edge;
		if (area.contains(start)) {
			Vec3 path = position().subtract(start);
			int steps = Math.max(1, (int) Math.ceil(path.length() / EDGE_STEP));
			double inside = 0, outside = 1;
			for (int i = 1; i <= steps; i++) {
				double t = (double) i / steps;
				if (!area.contains(start.add(path.scale(t)))) {
					outside = t;
					inside = (double) (i - 1) / steps;
					break;
				}
			}
			for (int i = 0; i < 12; i++) {
				double mid = (inside + outside) / 2;
				if (area.contains(start.add(path.scale(mid)))) inside = mid;
				else outside = mid;
			}
			edge = start.add(path.scale(inside));
		} else {
			// уже был снаружи (поставили или бросили из-за границы): ближайшая точка границы, если её можно узнать, иначе на месте
			edge = area.nearestEdge(position());
			if (edge == null) edge = position();
		}
		setPos(edge);
		// в воздухе падает отвесно на границе, на земле или в воде замирает сразу
		setDeltaMovement(0, Math.min(getDeltaMovement().y, 0), 0);
		if (onGround() || isInWater()) settle();
		else setDropping(true);
		needsSync = true;
	}

	private void settle() {
		setDropping(false);
		setDeltaMovement(Vec3.ZERO);
		setFrozen(true);
		needsSync = true;
	}

	/** Существо, вошедшее в мяч, толкает его по своему ходу. */
	private void bump() {
		for (LivingEntity living : level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.05), e -> e.isAlive() && !e.isSpectator())) {
			Vec3 away = position().subtract(living.position()).multiply(1, 0, 1);
			if (away.lengthSqr() < 1e-6) continue;
			Vec3 dir = away.normalize();
			Vec3 lv = living instanceof Player ? knownVelocity(living) : living.getDeltaMovement();
			double along = Math.max(BUMP_MIN, lv.multiply(1, 0, 1).dot(dir) * BUMP);
			Vec3 v = getDeltaMovement();
			double have = v.multiply(1, 0, 1).dot(dir);
			if (have < along) {
				setDeltaMovement(v.add(dir.scale(along - have)).add(0, 0.05, 0));
				needsSync = true;
			}
		}
	}

	/** Скорость игрока сервер не знает (её считает клиент): берём сдвиг за тик. */
	private static Vec3 knownVelocity(LivingEntity living) {
		Entity body = living.getRootVehicle();
		return new Vec3(body.getX() - body.xo, body.getY() - body.yo, body.getZ() - body.zo);
	}

	/** Пнуть по взгляду. */
	public void kick(Entity by) {
		kick(by.getLookAngle().multiply(1, 0, 1), 1, true);
	}

	/** Наибольшая сила удара стрелкой (доля {@link #KICK}). */
	public static final double MAX_POWER = 1.5;

	/**
	 * Удар по {@code dir} (может смотреть вверх) с силой {@code power} × {@link #KICK}; к горизонтальной части добавляется подброс
	 * {@link #KICK_UP}. Клиент бьёт так же сразу, не дожидаясь сервера ({@code sound} = false: звук даст сервер).
	 */
	public void kick(Vec3 dir, double power, boolean sound) {
		if (dir.lengthSqr() < 1e-6) dir = new Vec3(0, 0, 1);
		Vec3 n = dir.normalize();
		double flat = Math.hypot(n.x, n.z);
		power = Math.clamp(power, 0, MAX_POWER);
		setFrozen(false);
		setDropping(false);
		setDeltaMovement(n.scale(KICK * power).add(0, KICK_UP * power * flat, 0));
		if (sound) playSound(SoundEvents.SLIME_JUMP_SMALL, 1.0f, 1.2f);
		needsSync = true;
	}

	/** Бросок из руки: скорость задаёт предмет. */
	public void launch(Vec3 velocity) {
		setFrozen(false);
		setDropping(false);
		setDeltaMovement(velocity);
		needsSync = true;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (hand != InteractionHand.MAIN_HAND || !withinReach(player, level().isClientSide() ? 0 : 1.0)) return InteractionResult.PASS;
		if (level().isClientSide()) return InteractionResult.SUCCESS;
		ItemStack stack = BallItem.withPlace(new ItemStack(Balls.ITEM), place);
		if (!player.getInventory().add(stack)) return InteractionResult.FAIL;
		playSound(SoundEvents.ITEM_PICKUP, 0.4f, 1.0f);
		discard();
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		Entity by = source.getEntity();
		if (by == null || by instanceof Player player && !withinReach(player, 1.0)) return false;
		kick(by);
		return true;
	}

	@Override
	public boolean isPickable() {
		return true;
	}

	@Override
	public boolean isAttackable() {
		return true;
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 96 * 96;
	}

	@Override
	public @Nullable ItemStack getPickResult() {
		return BallItem.withPlace(new ItemStack(Balls.ITEM), place);
	}
}
