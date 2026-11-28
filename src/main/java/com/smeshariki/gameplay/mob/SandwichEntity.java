package com.smeshariki.gameplay.mob;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.api.Mechanics;
import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Бутерброд: моб, а не смешарик (вселиться нельзя, в окнах выбора его нет), в мире один ({@link UniqueSandwich}), 5 сердец. Живёт в локации
 * мира ({@link UniqueSandwich#place}; без локации никого не заражает) и ведёт себя по режиму ({@link Mode}):
 * <ul>
 * <li>{@link Mode#IDLE} — цели нет: парит на месте на {@link #HOVER} над поверхностью, смотрит на ближнего игрока;</li>
 * <li>{@link Mode#STALK} — цель выбрана ({@link #setTarget}: действие уровня или случай, {@link UniqueSandwich}): летит к ней, прячась
 * и не показываясь ей дальше {@link #SPELL_RADIUS} ({@link #sneakPoint}); цель резко обернулась — рывок {@link #DASH_SPEED}; подобравшись на
 * {@link #SPELL_RADIUS}, выходит под взгляд и, глядя цели прямо в глаза со стороны ({@link #eyeContact}), накладывает морок (после полученного
 * урона — не раньше чем через {@link #HURT_COOLDOWN} тиков) ({@link SandwichSpell}), вспыхивает и восстанавливает здоровье;</li>
 * <li>{@link Mode#MIMIC} — цель заражена: падает на землю, виден только цели (остальным его не рисует клиент, {@link #hiddenFrom}) и
 * притворяется одним из существ, которых цель видит бутербродами: повторяет движение ближайшего живого, а нет такого — ходит, бегает с
 * прыжками и стоит, озираясь, как игрок. Ударили, а сердец больше двух — отскакивает, смотрит на ударившего и мотает головой «нет»; осталось
 * два и меньше — снова охотится на цель, чтобы продлить морок (новое заражение лечит его).</li>
 * </ul>
 * Цель сбрасывается, только когда она ушла из локации (или пропала из мира) и с гибелью Бутерброда. Ранит его только чужой удар; убитый
 * роняет своё яйцо — его заражённый съедает, чтобы снять морок ({@link SandwichEggItem}).
 */
public class SandwichEntity extends Mob {
	public enum Mode {
		IDLE, STALK, MIMIC
	}

	/** Просвет между низом парящего бутерброда и верхом блока под ним. */
	public static final double HOVER = 1.0;
	/** Насколько вниз ищется поверхность; глубже её нет — бутерброд держит свою высоту. */
	private static final int GROUND_REACH = 16;
	/** Покой: доля расстояния до места, добавляемая к скорости за тик, остаток прежней скорости и предел скорости. */
	private static final double PULL = 0.08, KEEP = 0.7, MAX_SPEED = 0.4;

	/** Размер модели: хитбокс урона и высота её середины над точкой под ней. Хитбокс столкновений меньше — 0,9×0,9 (`Mobs`). */
	public static final double HURT_WIDTH = 1.6, HURT_HEIGHT = 1.4, BODY_MID = 0.7;

	public static final float MAX_HEALTH = 10f;
	/** С таким здоровьем и ниже притворяющийся бутерброд снова охотится (2 сердца). */
	public static final float RETREAT_HEALTH = 4f;
	/** Заражает цель, от тела которой до него не больше стольких блоков (просвет между коробками). */
	public static final double SPELL_RADIUS = 2.0;
	/** Взгляд цели на бутерброд и бутерброда на цель: не дальше стольких градусов от прямой между глазами. */
	private static final double TARGET_GAZE = 20, OWN_GAZE = 15;
	/** Заражает, только глядя со стороны: между глазами не меньше стольких блоков и коробки не пересекаются (просвет не меньше второго числа). */
	private static final double MIN_EYE_DISTANCE = 1.2, MIN_GAP = 0.3;
	/** После полученного урона не может заражать столько тиков (10 секунд). */
	public static final int HURT_COOLDOWN = 200;

	/** Скорости охоты, блоков за тик: обычная (5 блоков/с) и рывок (15 блоков/с); рывок длится столько тиков. */
	public static final double STALK_SPEED = 0.25, DASH_SPEED = 0.75;
	private static final int DASH_TICKS = 40;
	/** Резкий поворот цели: голова повернулась на столько градусов за {@link #TURN_WINDOW} тиков. */
	private static final float SHARP_TURN = 80f;
	private static final int TURN_WINDOW = 6;
	/**
	 * Дальше {@link #SPELL_RADIUS} от цели бутерброд ей не показывается; подобравшись на этот просвет, выходит под взгляд — в точку перед её
	 * глазами на таком расстоянии от них, обходя цель сбоку. Ближе второго числа (по горизонтали между серединами) к цели не подлетает.
	 */
	private static final double FACE_DISTANCE = 2.2, KEEP_AWAY = 1.6;
	/** Замеченный вдали уходит из виду быстрее обычного, блоков за тик (8 блоков/с). */
	public static final double HIDE_SPEED = 0.4;
	/** Убегая во время отката, дальше стольких блоков от цели не стремится. */
	private static final double FLEE_FAR = 24;
	/** Цель видит то, что не дальше стольких градусов от её взгляда (половина поля зрения с запасом) и не закрыто блоками. */
	private static final double VIEW_HALF = 63;
	/**
	 * Дольше стольких тиков подряд (2 секунды, вместе с временем, пока он прячется) цель не должна видеть его на экране: дальше он уходит с
	 * глаз на скорости рывка, а если это был выход под взгляд — ещё и отступает на {@link #BACK_OFF} тиков. Переноса и исчезновения нет:
	 * уйти он может только своим ходом, в место, где его не видно.
	 */
	public static final int SEEN_LIMIT = 40;
	private static final int BACK_OFF = 100;
	/** Подкрадывание: шаг выбора точки, сколько направлений перебирается и раз во сколько тиков. */
	private static final double SNEAK_STEP = 2.5;
	private static final int SNEAK_DIRECTIONS = 16, SNEAK_PERIOD = 5;
	/** Оценка точки: штраф за место на виду у цели, прибавка за укрытие от её глаз блоками и за каждое препятствие рядом. */
	private static final double SEEN_PENALTY = 20.0, HIDDEN_BONUS = 1.5, COVER_BONUS = 0.4;

	/** Притворство: шаг и бег, блоков за тик (как у игрока), и на сколько блоков вокруг ищется, кого повторять. */
	private static final double WALK_SPEED = 0.216, SPRINT_SPEED = 0.28, MODEL_RANGE = 12;
	/** Сколько тиков после удара стоит и мотает головой. */
	private static final int NOD_TICKS = 30;

	/** События сущности (клиентам): вспышка при заражении и мотание головой; их длительность в тиках. */
	private static final byte FLASH_EVENT = 90, SHAKE_EVENT = 91;
	private static final int FLASH_TICKS = 12, SHAKE_TICKS = 30;

	public static final String EVENT_INFECTED = "gameplaymod:sandwich_infected", EVENT_DEAD = "gameplaymod:sandwich_dead";

	private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(SandwichEntity.class, EntityDataSerializers.BYTE);
	/** Номер сущности игрока-цели, пока она заражена (только она его видит — и когда он притворяется, и когда снова охотится); 0 — виден всем. */
	private static final EntityDataAccessor<Integer> OWNER = SynchedEntityData.defineId(SandwichEntity.class, EntityDataSerializers.INT);

	private int flashTicks, shakeTicks;
	/** Учтён как единственный в мире ({@link UniqueSandwich}); у только что созданного — false. */
	boolean known;

	private double homeX = Double.NaN, homeZ = Double.NaN;
	private @Nullable UUID targetId;
	/** Локация: кэш, обновляется раз в 100 тиков. */
	private @Nullable Mechanics.Area area;
	private int areaAge = 100;

	// охота
	private final float[] targetYaws = new float[TURN_WINDOW];
	private int yawsKnown, dashTicks, replanIn, stalkTicks, infectCooldown, seenTicks;
	private @Nullable Vec3 waypoint;

	// притворство
	private enum Routine {
		WALK, SPRINT, STAND
	}

	private Routine routine = Routine.STAND;
	private int routineTicks, blockedTicks, nodTicks;
	private float routineYaw;
	private @Nullable LivingEntity attacker;

	public SandwichEntity(EntityType<? extends SandwichEntity> type, Level level) {
		super(type, level);
		setNoGravity(true);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, MAX_HEALTH);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(MODE, (byte) 0);
		builder.define(OWNER, 0);
	}

	public Mode mode() {
		return Mode.values()[Mth.clamp(this.entityData.get(MODE), 0, Mode.values().length - 1)];
	}

	private void setMode(Mode mode) {
		if (!level().isClientSide() && mode != mode()) UniqueSandwich.LOGGER.info("Бутерброд: {} -> {} в {}, цель {}", mode(), mode, at(), this.targetId);
		this.entityData.set(MODE, (byte) mode.ordinal());
		setNoGravity(mode != Mode.MIMIC);
		this.seenTicks = 0;
		if (mode == Mode.IDLE) {
			this.homeX = getX();
			this.homeZ = getZ();
		}
		this.waypoint = null;
		this.replanIn = 0;
		this.dashTicks = 0;
		this.stalkTicks = 0;
		this.yawsKnown = 0;
		this.nodTicks = 0;
		this.routineTicks = 0;
	}

	private String at() {
		return String.format("%.1f %.1f %.1f", getX(), getY(), getZ());
	}

	/** Состояние одной строкой, для отладки ({@code /sandwich info}, {@code /sandwich debug}). */
	public String debugLine() {
		ServerPlayer target = this.targetId == null || !(level() instanceof ServerLevel level) ? null : level.getServer().getPlayerList().getPlayer(this.targetId);
		String who = this.targetId == null ? "нет" : target == null ? "не в сети" : target.getName().getString();
		StringBuilder line = new StringBuilder(String.format("%s, %s, хп %.0f/%.0f, цель: %s", at(), mode(), getHealth(), getMaxHealth(), who));
		if (target != null) line.append(String.format(" (%.1f бл.)", Math.sqrt(distanceToSqr(CharacterEntity.body(target)))));
		if (mode() == Mode.STALK) line.append(String.format(", на виду %d/%d т., откат %d т.", this.seenTicks, SEEN_LIMIT, this.infectCooldown));
		if (this.entityData.get(OWNER) != 0) line.append(", виден только цели");
		return line.toString();
	}

	/** Клиент: этому игроку бутерброд не рисуется (притворяется перед другим). */
	public boolean hiddenFrom(Player viewer) {
		int owner = this.entityData.get(OWNER);
		return owner != 0 && owner != viewer.getId();
	}

	/**
	 * Выбрать цель заражения: бутерброд начинает к ней подкрадываться (а уже заражённую — сразу притворяется рядом). Цель должна быть в
	 * локации бутерброда, иначе она тут же сбросится.
	 */
	public void setTarget(ServerPlayer target) {
		this.targetId = target.getUUID();
		setMode(target.hasEffect(Mobs.SPELL) ? Mode.MIMIC : Mode.STALK);
	}

	public boolean hasTarget() {
		return this.targetId != null;
	}

	private void clearTarget() {
		this.targetId = null;
		setMode(Mode.IDLE);
	}

	private @Nullable Mechanics.Area area(ServerLevel level) {
		if (++this.areaAge >= 100) {
			this.areaAge = 0;
			this.area = Mechanics.place(level.getServer(), UniqueSandwich.place(level.getServer()));
		}
		return this.area != null && this.area.dimension() == level.dimension() ? this.area : null;
	}

	/** Цель, если она ещё годится: в сети, жива, не наблюдатель, в этом мире и в локации. Иначе цель сброшена. */
	private @Nullable ServerPlayer target(ServerLevel level, @Nullable Mechanics.Area area) {
		if (this.targetId == null) return null;
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(this.targetId);
		if (player == null || !player.isAlive() || player.isSpectator() || player.level() != level || area == null || !area.contains(CharacterEntity.body(player).position())) {
			clearTarget();
			return null;
		}
		return player;
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		Mechanics.Area area = area(level);
		ServerPlayer target = target(level, area);
		Mode mode = mode();
		if (target == null && mode != Mode.IDLE) {
			setMode(Mode.IDLE);
			mode = Mode.IDLE;
		}
		// пока цель заражена, остальным его не видно — и на земле, и в новой охоте
		this.entityData.set(OWNER, target != null && target.hasEffect(Mobs.SPELL) ? target.getId() : 0);
		switch (mode) {
			case IDLE -> idle(level);
			case STALK -> stalk(level, target, area);
			case MIMIC -> {
				// морок кончился сам: цель прежняя, заражаем снова
				if (!target.hasEffect(Mobs.SPELL)) setMode(Mode.STALK);
				else mimic(level, target, area);
			}
		}
	}

	// ---------------------------------------------------------------- покой

	private void idle(ServerLevel level) {
		if (Double.isNaN(this.homeX)) {
			this.homeX = getX();
			this.homeZ = getZ();
		}
		double ground = groundBelow(level, this.homeX, getY() + 0.01, this.homeZ);
		double targetY = Double.isNaN(ground) ? getY() : ground + HOVER;
		Vec3 pull = new Vec3(this.homeX - getX(), targetY - getY(), this.homeZ - getZ()).scale(PULL);
		Vec3 speed = getDeltaMovement().scale(KEEP).add(pull);
		if (speed.lengthSqr() > MAX_SPEED * MAX_SPEED) speed = speed.normalize().scale(MAX_SPEED);
		setDeltaMovement(speed);
		Player nearest = null;
		double best = 8 * 8;
		for (ServerPlayer player : level.players()) {
			double d = player.distanceToSqr(this);
			if (!player.isSpectator() && d < best) {
				best = d;
				nearest = player;
			}
		}
		if (nearest != null) getLookControl().setLookAt(nearest, 30f, 30f);
	}

	/** Верх ближайшего блока со столкновением не выше {@code fromY} в столбце (x, z); NaN — нет в пределах поиска или чанк не загружен. */
	private static double groundBelow(ServerLevel level, double x, double fromY, double z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int top = Mth.floor(fromY);
		pos.set(Mth.floor(x), top, Mth.floor(z));
		if (!level.isLoaded(pos)) return Double.NaN;
		for (int y = top; y >= top - GROUND_REACH && y >= level.getMinY(); y--) {
			pos.setY(y);
			VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (shape.isEmpty()) continue;
			double surface = y + shape.max(Direction.Axis.Y);
			if (surface <= fromY) return surface;
		}
		return Double.NaN;
	}

	// ---------------------------------------------------------------- охота

	private void stalk(ServerLevel level, ServerPlayer target, Mechanics.Area area) {
		Entity body = CharacterEntity.body(target);
		Vec3 eyes = target.getEyePosition();
		getLookControl().setLookAt(eyes.x, eyes.y, eyes.z, 30f, 30f);
		this.stalkTicks++;
		if (this.infectCooldown > 0) this.infectCooldown--;
		// цель резко обернулась — рывок
		float yaw = target.getYHeadRot();
		int slot = this.tickCount % TURN_WINDOW;
		if (this.yawsKnown >= TURN_WINDOW && Math.abs(Mth.wrapDegrees(yaw - this.targetYaws[slot])) >= SHARP_TURN) this.dashTicks = DASH_TICKS;
		this.targetYaws[slot] = yaw;
		this.yawsKnown++;
		boolean dash = this.dashTicks > 0;
		if (dash) this.dashTicks--;
		double gap = gap(body.getBoundingBox());
		// сколько цель видит его подряд: дольше предела — пропадает из виду совсем, пока не уйдёт с глаз
		boolean seen = seenAt(level, target, position());
		boolean facing = this.infectCooldown <= 0 && gap <= SPELL_RADIUS;
		if (seen) {
			// не успел заразить, выйдя под взгляд: отступает и прячется
			if (++this.seenTicks > SEEN_LIMIT && facing) {
				this.infectCooldown = BACK_OFF;
				facing = false;
			}
		} else if (this.seenTicks > 0) this.seenTicks = Math.max(0, this.seenTicks - 4);
		// цель смотрит на него слишком долго: уходит с глаз на полной скорости. Других путей отхода нет — только скорость и место, где не видно
		boolean exposed = this.seenTicks > SEEN_LIMIT;
		// откат (после урона или неудачного выхода под взгляд): убегает от цели и прячется, в пределах локации
		boolean fleeing = this.infectCooldown > 0;
		if (facing && eyeContact(target, gap)) {
			infect(level, target);
			return;
		}
		Vec3 goal;
		if (facing) {
			// подобрался: выходит под взгляд, в точку перед глазами цели, не сквозь неё, а сбоку
			Vec3 view = target.getViewVector(1f);
			Vec3 flat = new Vec3(view.x, 0, view.z);
			flat = flat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : flat.normalize();
			Vec3 mine = new Vec3(getX() - eyes.x, 0, getZ() - eyes.z);
			if (mine.lengthSqr() > 1e-4 && mine.normalize().dot(flat) < 0.2) {
				Vec3 side = new Vec3(-flat.z, 0, flat.x);
				if (side.dot(mine) < 0) side = side.scale(-1);
				flat = flat.scale(0.3).add(side).normalize();
			}
			goal = new Vec3(eyes.x + flat.x * FACE_DISTANCE, eyes.y - getEyeHeight(), eyes.z + flat.z * FACE_DISTANCE);
			this.waypoint = null;
		} else {
			if (--this.replanIn <= 0 || this.waypoint == null || this.waypoint.distanceToSqr(position()) < 0.25) {
				this.waypoint = sneakPoint(level, target, body, fleeing ? area : null);
				this.replanIn = dash || seen ? 2 : SNEAK_PERIOD;
			}
			goal = this.waypoint;
		}
		// быстро (рывок после резкого поворота цели, уход с её глаз) он летит, только когда не приближается к цели: догоняет всегда обычным шагом
		Vec3 at = body.position();
		boolean closing = Math.hypot(goal.x - at.x, goal.z - at.z) < Math.hypot(getX() - at.x, getZ() - at.z) - 0.1;
		double speed = closing || facing ? STALK_SPEED : dash || exposed ? DASH_SPEED : seen || fleeing ? HIDE_SPEED : STALK_SPEED;
		Vec3 to = goal.subtract(position());
		double length = to.length();
		Vec3 wanted = length < 0.05 ? Vec3.ZERO : to.scale(Math.min(speed, length) / length);
		setDeltaMovement(getDeltaMovement().lerp(wanted, 0.5));
	}

	/**
	 * Точки, по которым проверяется, виден ли бутерброд: он шире и выше своей коробки (1,6×1,4) — в стороны торчат листья-крылья, вверх
	 * усики, вперёд и вверх глаза. Каждая — {вбок, вверх, вперёд} от точки под его серединой; «вперёд» — к цели (в охоте он смотрит на неё).
	 * Числа сняты с модели ({@code client/SandwichModel}, увеличение 2): менять вместе с ней.
	 */
	private static final double[][] OUTLINE = { { 0, 0.7, 0 }, { 1.35, 0.4, 0 }, { -1.35, 0.4, 0 }, { 0.95, 1.5, 0 }, { -0.95, 1.5, 0 }, { 0, 1.25, 0.45 } };

	/** Цель увидит бутерброд, стоящий в точке {@code feet}: видна хотя бы одна из точек его очертаний ({@link #OUTLINE}). */
	private boolean seenAt(ServerLevel level, ServerPlayer target, Vec3 feet) {
		Vec3 eyes = target.getEyePosition();
		Vec3 forward = new Vec3(eyes.x - feet.x, 0, eyes.z - feet.z);
		forward = forward.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : forward.normalize();
		for (double[] p : OUTLINE) {
			if (seenBy(level, target, new Vec3(feet.x - forward.z * p[0] + forward.x * p[2], feet.y + p[1], feet.z + forward.x * p[0] + forward.z * p[2]))) return true;
		}
		return false;
	}

	/** Цель видит точку: она в её поле зрения ({@link #VIEW_HALF}) и между глазами цели и точкой нет блоков. */
	private boolean seenBy(ServerLevel level, ServerPlayer target, Vec3 point) {
		Vec3 eyes = target.getEyePosition(), to = point.subtract(eyes);
		if (to.lengthSqr() < 1e-6) return true;
		return to.normalize().dot(target.getViewVector(1f)) >= Math.cos(Math.toRadians(VIEW_HALF)) && !blocked(level, eyes, point);
	}

	/**
	 * Куда лететь, подкрадываясь: из точек вокруг на шаг {@link #SNEAK_STEP} (на высоте парения над своей поверхностью, свободных, с прямым
	 * пролётом до них и не ближе {@link #KEEP_AWAY} к цели) берётся лучшая по правилам: ближе к цели — лучше (чем дольше охота, тем важнее);
	 * на виду у цели ({@link #seenBy}) — штраф {@link #SEEN_PENALTY}, поэтому на виду он оказывается, только когда иначе никак; скрыта от её
	 * глаз блоками — {@link #HIDDEN_BONUS}; каждое препятствие рядом, за которым можно укрыться, — {@link #COVER_BONUS}. Нет ни одной годной
	 * точки — вверх. С областью {@code flee} — бегство: наоборот, чем дальше от цели, тем лучше (до {@link #FLEE_FAR} блоков, дальше только
	 * прячется), и точка должна быть в этой области (локации); остальные правила те же.
	 */
	private Vec3 sneakPoint(ServerLevel level, ServerPlayer target, Entity body, @Nullable Mechanics.Area flee) {
		Vec3 from = position(), at = body.position(), eyes = target.getEyePosition();
		double half = BODY_MID, hurry = 1.0 + this.stalkTicks / 200.0;
		Vec3 center = from.add(0, half, 0);
		Vec3 best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < SNEAK_DIRECTIONS; i++) {
			double angle = Math.PI * 2 * i / SNEAK_DIRECTIONS;
			double x = from.x + Math.cos(angle) * SNEAK_STEP, z = from.z + Math.sin(angle) * SNEAK_STEP;
			double ground = groundBelow(level, x, from.y + 2.5, z);
			if (Double.isNaN(ground)) continue;
			Vec3 point = new Vec3(x, ground + HOVER, z);
			if (!level.noCollision(this, getBoundingBox().move(point.subtract(from)))) continue;
			Vec3 mid = point.add(0, half, 0);
			if (blocked(level, center, mid)) continue;
			double away = Math.hypot(point.x - at.x, point.z - at.z);
			if (away < KEEP_AWAY || (flee != null && !flee.contains(point))) continue;
			double score = flee != null ? Math.min(away, FLEE_FAR) : -away * hurry;
			// на виду — большой штраф, тем больше, чем дольше цель его уже видит
			if (seenAt(level, target, point)) score -= SEEN_PENALTY + this.seenTicks;
			else if (blocked(level, eyes, mid)) score += HIDDEN_BONUS;
			score += cover(level, point) * COVER_BONUS + this.random.nextDouble() * 0.2;
			if (score > bestScore) {
				bestScore = score;
				best = point;
			}
		}
		return best != null ? best : from.add(0, 1, 0);
	}

	/** Сколько из восьми столбцов вокруг точки заняты блоками на высоте бутерброда: препятствия, за которыми можно укрыться. */
	private int cover(ServerLevel level, Vec3 point) {
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 8; i++) {
			double angle = Math.PI / 4 * i;
			pos.set(Mth.floor(point.x + Math.cos(angle) * 1.6), Mth.floor(point.y + 0.5), Mth.floor(point.z + Math.sin(angle) * 1.6));
			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) count++;
		}
		return count;
	}

	private boolean blocked(ServerLevel level, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS;
	}

	/**
	 * Прямой контакт глаз: бутерброд смотрит цели в глаза со стороны (не вплотную и не изнутри неё: {@link #MIN_EYE_DISTANCE}, {@link #MIN_GAP}),
	 * между глазами нет блоков, цель смотрит на него, а он — прямо на неё.
	 */
	private boolean eyeContact(ServerPlayer target, double gap) {
		Vec3 between = getEyePosition().subtract(target.getEyePosition());
		if (gap < MIN_GAP || between.length() < MIN_EYE_DISTANCE || !hasLineOfSight(target)) return false;
		between = between.normalize();
		return target.getViewVector(1f).dot(between) >= Math.cos(Math.toRadians(TARGET_GAZE)) && getViewVector(1f).dot(between.scale(-1)) >= Math.cos(Math.toRadians(OWN_GAZE));
	}

	/**
	 * Где бутерброда можно ударить (и куда попадёт стрела или прицел): по размеру его модели. Отдельно от хитбокса столкновений (0,9×0,9, как у
	 * смешариков свой узкий): с тем он проходит в проём в один блок. Используется лучом попадания ({@code ProjectileUtilMixin}) и просветом до цели.
	 */
	public AABB hurtBox() {
		double half = HURT_WIDTH / 2;
		return new AABB(getX() - half, getY(), getZ() - half, getX() + half, getY() + HURT_HEIGHT, getZ() + half);
	}

	/** Просвет между бутербродом (по размеру модели) и телом (0 — касаются). */
	private double gap(AABB other) {
		AABB box = hurtBox();
		double dx = Math.max(0, Math.max(box.minX - other.maxX, other.minX - box.maxX));
		double dy = Math.max(0, Math.max(box.minY - other.maxY, other.minY - box.maxY));
		double dz = Math.max(0, Math.max(box.minZ - other.maxZ, other.minZ - box.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Заражение (и продление): морок на полный срок, вспышка, здоровье восстановлено, дальше бутерброд притворяется. */
	private void infect(ServerLevel level, ServerPlayer target) {
		curse(target);
		level.broadcastEntityEvent(this, FLASH_EVENT);
		level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFF2B0), getX(), getY() + BODY_MID, getZ(), 1, 0, 0, 0, 0);
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, getSoundSource(), 1f, 1.4f);
		setHealth(getMaxHealth());
		setMode(Mode.MIMIC);
		Mechanics.emit(level.getServer(), EVENT_INFECTED);
	}

	/** Накладывает морок на полный срок (уже идущий продлевается до полного). */
	public static void curse(LivingEntity target) {
		target.addEffect(new MobEffectInstance(Mobs.SPELL, SandwichSpell.TICKS));
	}

	// ---------------------------------------------------------------- притворство

	private void mimic(ServerLevel level, ServerPlayer target, Mechanics.Area area) {
		if (this.nodTicks > 0) {
			// после удара: отлетает от него сам (ванильный отскок), смотрит на ударившего и мотает головой
			this.nodTicks--;
			if (this.attacker != null && this.attacker.isAlive()) getLookControl().setLookAt(this.attacker, 60f, 60f);
			return;
		}
		Vec3 move = copied(level, target);
		if (move == null) move = routine(target);
		// из локации не выходит: поворачивает к цели (она внутри)
		if (!area.contains(position().add(move.x * 4, 0, move.z * 4))) {
			Vec3 back = CharacterEntity.body(target).position().subtract(position());
			this.routineYaw = (float) Math.toDegrees(Math.atan2(-back.x, back.z));
			move = heading(this.routineYaw).scale(Math.max(move.horizontalDistance(), WALK_SPEED));
		}
		if (this.horizontalCollision && move.horizontalDistanceSqr() > 1e-4) {
			// упёрся: запрыгивает на блок, а не вышло — сворачивает
			if (onGround()) jumpFromGround();
			if (++this.blockedTicks > 15) {
				this.routineYaw += 90 + this.random.nextFloat() * 180;
				this.blockedTicks = 0;
			}
		} else this.blockedTicks = 0;
		setDeltaMovement(move.x, getDeltaMovement().y, move.z);
		if (move.horizontalDistanceSqr() > 1e-4) getLookControl().setLookAt(getX() + move.x * 20, getEyeY(), getZ() + move.z * 20, 30f, 30f);
	}

	/** Движение ближайшего живого рядом (его сдвиг за тик, прыжок вместе с ним); null — рядом никого или он стоит. */
	private @Nullable Vec3 copied(ServerLevel level, ServerPlayer target) {
		Entity targetBody = CharacterEntity.body(target);
		LivingEntity model = null;
		double best = MODEL_RANGE * MODEL_RANGE;
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(MODEL_RANGE), e -> e != this && e != target && e != targetBody
				&& e.isAlive() && !e.isSpectator() && !e.isInvisible() && !(e instanceof ArmorStand))) {
			double d = other.distanceToSqr(this);
			if (d < best) {
				best = d;
				model = other;
			}
		}
		if (model == null) return null;
		Vec3 step = new Vec3(model.getX() - model.xo, 0, model.getZ() - model.zo);
		double length = step.length();
		if (length < 0.03) return null;
		if (length > SPRINT_SPEED) step = step.scale(SPRINT_SPEED / length);
		if (model.getY() > model.yo + 0.05 && onGround()) jumpFromGround();
		return step;
	}

	/** Своё поведение «как игрок»: идёт, бежит с прыжками, стоит и озирается (иногда смотрит на цель); отрезки случайной длины. */
	private Vec3 routine(ServerPlayer target) {
		if (--this.routineTicks <= 0) {
			float roll = this.random.nextFloat();
			if (roll < 0.5f) {
				this.routine = Routine.WALK;
				this.routineTicks = 40 + this.random.nextInt(80);
			} else if (roll < 0.7f) {
				this.routine = Routine.SPRINT;
				this.routineTicks = 40 + this.random.nextInt(40);
			} else {
				this.routine = Routine.STAND;
				this.routineTicks = 20 + this.random.nextInt(40);
			}
			this.routineYaw += (this.random.nextFloat() - 0.5f) * 180f;
		}
		switch (this.routine) {
			case WALK:
				return heading(this.routineYaw).scale(WALK_SPEED);
			case SPRINT:
				if (onGround()) jumpFromGround();
				return heading(this.routineYaw).scale(SPRINT_SPEED);
			default:
				if (this.routineTicks % 15 == 0) {
					if (this.random.nextInt(3) == 0) getLookControl().setLookAt(target, 30f, 30f);
					else {
						Vec3 look = heading(this.routineYaw + (this.random.nextFloat() - 0.5f) * 240f);
						getLookControl().setLookAt(getX() + look.x * 8, getEyeY() + (this.random.nextFloat() - 0.5f) * 4, getZ() + look.z * 8, 30f, 30f);
					}
				}
				return Vec3.ZERO;
		}
	}

	private static Vec3 heading(float yaw) {
		double radians = Math.toRadians(yaw);
		return new Vec3(-Math.sin(radians), 0, Math.cos(radians));
	}

	// ---------------------------------------------------------------- удары, гибель, вид

	/** Ранит только чужой удар (и то, что обходит неуязвимость): он в мире один и не должен гибнуть от лавы или падения. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurtServer(level, source, amount);
		if (!(source.getEntity() instanceof LivingEntity striker) || striker == this) return false;
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt) this.infectCooldown = HURT_COOLDOWN;
		if (hurt && isAlive() && mode() == Mode.MIMIC) {
			if (getHealth() <= RETREAT_HEALTH) setMode(Mode.STALK);
			else {
				this.nodTicks = NOD_TICKS;
				this.attacker = striker;
				level.broadcastEntityEvent(this, SHAKE_EVENT);
			}
		}
		return hurt;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (level() instanceof ServerLevel level) {
			// убитый кем-то роняет своё яйцо; убранный командой — нет
			if (source.getEntity() != null) spawnAtLocation(level, new ItemStack(Mobs.SANDWICH_EGG));
			UniqueSandwich.LOGGER.info("Бутерброд: убит в {} ({})", at(), source.getMsgId());
			UniqueSandwich.onDeath(level);
			Mechanics.emit(level.getServer(), EVENT_DEAD);
		}
	}

	@Override
	public void handleEntityEvent(byte id) {
		if (id == FLASH_EVENT) this.flashTicks = FLASH_TICKS;
		else if (id == SHAKE_EVENT) this.shakeTicks = SHAKE_TICKS;
		else super.handleEntityEvent(id);
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			if (this.flashTicks > 0) this.flashTicks--;
			if (this.shakeTicks > 0) this.shakeTicks--;
		}
	}

	/** Сила вспышки для рисунка: 1 в момент заражения, тает до 0. */
	public float flash(float partialTick) {
		return this.flashTicks <= 0 ? 0f : Math.max(0f, (this.flashTicks - partialTick) / FLASH_TICKS);
	}

	/** Мотание головой «нет» для рисунка: поворот в градусах (три качка, затухает), 0 — не мотает. */
	public float shake(float partialTick) {
		if (this.shakeTicks <= 0) return 0f;
		float left = Math.max(0f, (this.shakeTicks - partialTick) / SHAKE_TICKS);
		return Mth.sin((1f - left) * (float) Math.PI * 6f) * 35f * left;
	}

	@Override
	public boolean removeWhenFarAway(double distanceSquared) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void checkFallDamage(double dy, boolean onGround, BlockState state, BlockPos pos) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putBoolean("Known", this.known);
		out.putInt("Mode", mode().ordinal());
		out.storeNullable("Target", UUIDUtil.CODEC, this.targetId);
		if (!Double.isNaN(this.homeX)) {
			out.putDouble("HomeX", this.homeX);
			out.putDouble("HomeZ", this.homeZ);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		this.known = in.getBooleanOr("Known", false);
		this.homeX = in.getDoubleOr("HomeX", Double.NaN);
		this.homeZ = in.getDoubleOr("HomeZ", Double.NaN);
		Optional<UUID> target = in.read("Target", UUIDUtil.CODEC);
		this.targetId = target.orElse(null);
		Mode mode = this.targetId == null ? Mode.IDLE : Mode.values()[Mth.clamp(in.getIntOr("Mode", 0), 0, Mode.values().length - 1)];
		this.entityData.set(MODE, (byte) mode.ordinal());
		setNoGravity(mode != Mode.MIMIC);
	}
}
