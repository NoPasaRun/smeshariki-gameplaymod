package com.smeshariki.gameplay.character;

import org.joml.Quaternionf;

import java.util.List;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Персонаж-шарик (Крош, Ёжик): бродит, поглядывает на игроков и убегает, если его обидеть. Какой именно персонаж, решает
 * тип сущности (внешность рисует клиентская часть).
 * <p>
 * В персонажа можно вселиться: ПКМ пустой рукой сажает игрока ему в голову (камера у лица, сам игрок невидим). Пока он
 * «вселён», он слушается взгляда и клавиш игрока (двойной прыжок — только у Кроша); Shift выходит. Движение ведёт клиент вселённого
 * (как у любого верхового существа), поэтому двойной прыжок считается там же.
 * <p>
 * Особенности по Ctrl (клавиша бега), включает их клиент вселённого пакетом {@link CharacterAbility}: Ёжик скатывается с холма
 * клубком (направлением и камерой игрок не управляет), Карыч планирует, но только вниз.
 */
public class CharacterEntity extends PathfinderMob {
	/** Во сколько раз скорость верхом меньше базовой (базовая рассчитана на движение ИИ и для езды слишком велика). */
	private static final float RIDDEN_SPEED_FACTOR = 0.5f;
	/** Скорость верхом в воздухе: шаг и бег. */
	private static final float AIR_CONTROL = 0.2f, AIR_CONTROL_SPRINT = 0.26f;

	private static final double MAX_HEALTH = 20;

	/** Сколько здоровья (2 = сердечко) возвращает любимая еда. */
	private static final float FOOD_HEAL = 4f;
	/** Во сколько раз вселённый ломает блоки медленнее обычного игрока. */
	public static final float BREAK_SPEED_FACTOR = 0.2f;
	/** Дольше этого персонаж после поджога не горит, тиков (3 с; ваниль даёт лаве 15 с, огню 8). В лаве он поджигается снова каждый тик. */
	public static final int MAX_FIRE_TICKS = 60;
	/** Вселённый игрок доедает любимую еду в этом использовании (чтобы вылечить один раз). */
	private boolean eatHealed;

	/** Слоты одежды: голова, глаза, руки, ноги. */
	public static final int GEAR_HEAD = 0, GEAR_EYES = 1, GEAR_ARMS = 2, GEAR_LEGS = 3;
	/** Одежда для показа клиентам (сама одежда лежит в {@link #gear} на сервере). */
	private static final List<EntityDataAccessor<ItemStack>> GEAR_DATA = List.of(
			SynchedEntityData.defineId(CharacterEntity.class, EntityDataSerializers.ITEM_STACK),
			SynchedEntityData.defineId(CharacterEntity.class, EntityDataSerializers.ITEM_STACK),
			SynchedEntityData.defineId(CharacterEntity.class, EntityDataSerializers.ITEM_STACK),
			SynchedEntityData.defineId(CharacterEntity.class, EntityDataSerializers.ITEM_STACK));

	/** Надетые предметы (по одному в слоте). Меняет окно {@link CharacterMenu}; изменения уходят в синхронизируемые данные. */
	public final SimpleContainer gear = new SimpleContainer(4) {
		@Override
		public void setChanged() {
			super.setChanged();
			syncGear();
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}
	};

	/** Особенность включена: Ёжик катится, Карыч планирует. */
	private static final EntityDataAccessor<Boolean> ABILITY = SynchedEntityData.defineId(CharacterEntity.class, EntityDataSerializers.BOOLEAN);

	/**
	 * Клубок: ускорение вниз по склону за тик (при крутизне 1), множитель скорости за тик на склоне, на ровном месте и в воздухе,
	 * предел скорости, начальный толчок, скорость, ниже которой на ровном месте клубок останавливается, радиус для вращения.
	 */
	private static final double ROLL_ACCEL = 0.03, ROLL_FRICTION = 0.985, ROLL_FLAT_FRICTION = 0.97, ROLL_AIR_FRICTION = 0.995, ROLL_MAX = 0.6, ROLL_PUSH = 0.3, ROLL_STOP = 0.04;
	/** Радиус шара (общее тело 0,375, увеличенное в 1,8/1,3 раза). */
	private static final float ROLL_RADIUS = 0.375f * 1.8f / 1.3f;
	/** Планирование: скорость вперёд (при взгляде вниз растёт вдвое), падение за тик (при взгляде вниз быстрее). */
	private static final double GLIDE_SPEED = 0.3, GLIDE_FALL = 0.06, GLIDE_DIVE_FALL = 0.2;

	/** Только клиент: накопленный поворот клубка (для рендера, с предыдущим тиком для плавности) и раскрытость крыльев (0..1). */
	public final Quaternionf rollRot = new Quaternionf(), rollRotO = new Quaternionf();
	public float spread, spreadO;
	/** Только клиент: поворот камеры вокруг оси взгляда, пока Ёжик катится (рад.); после остановки плавно возвращается. */
	public float camRoll, camRollO;

	/** Клубок докатился до ровного места и остановился (клиент вселённого проверяет и выключает режим). */
	private boolean rollDone;
	/** Тик сервера, когда Карыч последний раз планировал (при приземлении после этого безопасная высота вдвое больше). */
	private int lastGlideTick = -1000;

	/** Нажатие прыжка вселённого (ставит клиентская часть), обрабатывается в {@link #tickRidden}. */
	private boolean jumpRequested;
	/** Второй прыжок в воздухе уже сделан. */
	private boolean doubleJumpUsed;

	public CharacterEntity(EntityType<? extends CharacterEntity> type, Level level) {
		super(type, level);
	}

	public static AttributeSupplier.Builder createAttributes() {
		// 10 сердец; падение: 1 сердечко (2 хп) за каждые 10 блоков сверх безопасных 3
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, MAX_HEALTH).add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.FALL_DAMAGE_MULTIPLIER, 0.2);
	}

	/** С какой доли высоты хитбокса, погружённой в лаву, она жжёт: стоящего на плоту над лавой касание ногами не поджигает. */
	public static final double LAVA_DEPTH = 0.5;

	private boolean deepInLava() {
		return getFluidHeight(net.minecraft.tags.FluidTags.LAVA) > getBbHeight() * LAVA_DEPTH;
	}

	@Override
	public void lavaIgnite() {
		if (deepInLava()) super.lavaIgnite();
	}

	@Override
	public void lavaHurt() {
		if (deepInLava()) super.lavaHurt();
	}

	/** Горит недолго: поджог от лавы и огня не больше {@link #MAX_FIRE_TICKS}. */
	@Override
	public void setRemainingFireTicks(int ticks) {
		super.setRemainingFireTicks(isRolling() ? 0 : Math.min(ticks, MAX_FIRE_TICKS));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ABILITY, false);
		for (EntityDataAccessor<ItemStack> data : GEAR_DATA) builder.define(data, ItemStack.EMPTY);
	}

	@Override
	protected void registerGoals() {
		// без FloatGoal: упавший в глубокую воду персонаж тонет и, если не выберется, задыхается (воздух тратится как у игрока)
		this.goalSelector.addGoal(1, new PanicGoal(this, 1.5));
		this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 1.0));
		this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6f));
		this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
	}

	/** Персонажи не пропадают сами, когда игрок далеко (у обычного моба при удалении на 128+ блоков он исчезает). */
	@Override
	public boolean removeWhenFarAway(double distanceSquared) {
		return false;
	}

	/** Любимая еда: единственное, что восстанавливает здоровье (само оно не восстанавливается). */
	public Item favoriteFood() {
		if (getType() == Characters.YOZHIK) return Items.APPLE;
		if (getType() == Characters.KROSH) return Items.CARROT;
		if (getType() == Characters.LOSYASH) return Items.BREAD;
		if (getType() == Characters.NYUSHA) return Items.COOKIE;
		if (getType() == Characters.BARASH) return Items.SWEET_BERRIES;
		if (getType() == Characters.SOVUNYA) return Items.BEETROOT;
		return Items.PUMPKIN_PIE;
	}

	private void eatFavorite() {
		heal(FOOD_HEAL);
		playSound(SoundEvents.GENERIC_EAT.value(), 1f, 1f);
		if (level() instanceof ServerLevel server) server.sendParticles(ParticleTypes.HEART, getX(), getY() + getBbHeight(), getZ(), 5, 0.3, 0.2, 0.3, 0.02);
	}

	/** Вселённый игрок съел любимую еду сам. */
	@Override
	public void aiStep() {
		super.aiStep();
		if (!level().isClientSide() && getControllingPassenger() instanceof Player rider) {
			// пузырьки воздуха в HUD показывают воздух игрока: даём ему воздух персонажа, а тонет и задыхается сам персонаж
			rider.setAirSupply(Math.max(0, getAirSupply()));
			if (!rider.isUsingItem()) this.eatHealed = false;
			else if (!this.eatHealed && rider.getUseItem().is(favoriteFood()) && rider.getUseItemRemainingTicks() <= 1) {
				this.eatHealed = true;
				if (getHealth() < getMaxHealth()) eatFavorite();
			}
		}
		if (level().isClientSide()) updateAbilityAnimation();
		// персонажи, призванные до того, как сердец стало 10, подтягиваются до нового максимума
		else if (this.tickCount == 1 && getAttributeBaseValue(Attributes.MAX_HEALTH) < MAX_HEALTH) getAttribute(Attributes.MAX_HEALTH).setBaseValue(MAX_HEALTH);
	}

	// ---------------------------------------------------------------- одежда

	private void syncGear() {
		if (level().isClientSide()) return;
		for (int i = 0; i < GEAR_DATA.size(); i++) this.entityData.set(GEAR_DATA.get(i), this.gear.getItem(i).copy());
	}

	/** Надеть одежду в её слот, если он пуст (для уровней: персонаж начинает миссию в очках). @return надето ли */
	public boolean wear(ItemStack stack) {
		if (!(stack.getItem() instanceof AccessoryItem accessory) || !this.gear.getItem(accessory.slot()).isEmpty()) return false;
		this.gear.setItem(accessory.slot(), stack.copyWithCount(1));
		return true;
	}

	/** Что надето в слоте (и на сервере, и на клиенте — для показа на модели). */
	public ItemStack gearStack(int slot) {
		return this.entityData.get(GEAR_DATA.get(slot));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		for (int i = 0; i < GEAR_DATA.size(); i++) out.store("Gear" + i, ItemStack.OPTIONAL_CODEC, this.gear.getItem(i));
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		for (int i = 0; i < GEAR_DATA.size(); i++) {
			int slot = i;
			in.read("Gear" + i, ItemStack.OPTIONAL_CODEC).ifPresent(stack -> this.gear.setItem(slot, stack));
		}
	}

	/** Визуальная поза (только клиент, для катсцен и других модов); null — обычная. */
	private CharacterPose visualPose;

	/** Задаёт визуальную позу ({@code null} снимает); хранится без копирования — позу можно менять между кадрами. */
	public void setVisualPose(CharacterPose pose) {
		this.visualPose = pose;
	}

	public CharacterPose getVisualPose() {
		return this.visualPose;
	}

	/** Очки надеты. */
	public boolean hasGlasses() {
		return gearStack(GEAR_EYES).is(Characters.GLASSES);
	}

	/** Насколько побиты очки (0 — целые, 1 — на грани), для трещин на экране. */
	public float glassesWear() {
		ItemStack glasses = gearStack(GEAR_EYES);
		return glasses.is(Characters.GLASSES) ? (float) glasses.getDamageValue() / glasses.getMaxDamage() : 0f;
	}

	/** Половина размера головы по горизонтали и высота зоны лица вокруг уровня глаз, блоков: сюда должен попасть удар, чтобы очки пострадали. */
	private static final double FACE_HALF = 0.3, FACE_HEIGHT = 0.35;

	/**
	 * Очки бьются, только если удар пришёл по лицу: в лицо ударил моб или игрок (его взгляд упирается в голову спереди) или в лицо прилетел предмет
	 * (стрела, снежок: траектория проходит через голову спереди). Падение, огонь, лава, взрыв сзади и удары в тело очков не трогают.
	 */
	private boolean hitsFace(DamageSource source) {
		net.minecraft.world.entity.Entity direct = source.getDirectEntity();
		if (direct == null || direct == this) return false;
		net.minecraft.world.phys.AABB head = new net.minecraft.world.phys.AABB(getX() - FACE_HALF, getEyeY() - FACE_HEIGHT, getZ() - FACE_HALF, getX() + FACE_HALF, getEyeY() + FACE_HEIGHT, getZ() + FACE_HALF);
		Vec3 from, to;
		if (direct instanceof net.minecraft.world.entity.projectile.Projectile) {
			// траектория на ближайшие тики: за этот тик снаряд уже вошёл в тело, поэтому берём луч по скорости с запасом назад
			Vec3 v = direct.getDeltaMovement();
			if (v.lengthSqr() < 1e-6) return false;
			from = direct.position().subtract(v.scale(2));
			to = direct.position().add(v.scale(1));
		} else if (direct instanceof LivingEntity attacker) {
			from = attacker.getEyePosition();
			to = from.add(attacker.getLookAngle().scale(4));
		} else {
			return false;
		}
		if (head.inflate(0.15).clip(from, to).isEmpty() && !head.inflate(0.15).contains(from)) return false;
		// спереди: источник в передней полусфере по взгляду персонажа
		Vec3 look = Vec3.directionFromRotation(0, getYHeadRot());
		Vec3 toSource = new Vec3(from.x - getX(), 0, from.z - getZ());
		return toSource.lengthSqr() < 1e-6 || look.dot(toSource.normalize()) > 0.2;
	}

	/** Хитбокс урона: ширина и высота (блоки). По форме модели (круглое тело с ручками), шире и ниже хитбокса столкновений 0,6 × 1,8. */
	private static final double HURT_WIDTH = 1.2, HURT_HEIGHT = 1.6;

	/**
	 * Где смешарика можно ударить (и куда попадёт стрела или прицел). Отдельно от {@link #getBoundingBox()}, который отвечает за столкновения, и не меняет их.
	 * Используется лучом попадания ({@code ProjectileUtilMixin}).
	 */
	public net.minecraft.world.phys.AABB hurtBox() {
		double half = HURT_WIDTH / 2;
		return new net.minecraft.world.phys.AABB(getX() - half, getY(), getZ() - half, getX() + half, getY() + HURT_HEIGHT, getZ() + half);
	}

	/** Бьёт по очкам только удар по лицу ({@link #hitsFace}); сила износа — половина урона, не меньше единицы; когда они на пределе, разбиваются. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// в клубке урона нет (кроме обходящего неуязвимость: /kill, пустота)
		if (isRolling() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
		boolean hurt = super.hurtServer(level, source, amount);
		ItemStack glasses = this.gear.getItem(GEAR_EYES);
		if (hurt && amount > 0 && glasses.is(Characters.GLASSES) && hitsFace(source)) {
			glasses.setDamageValue(glasses.getDamageValue() + Math.max(1, Mth.ceil(amount / 2f)));
			if (glasses.getDamageValue() >= glasses.getMaxDamage()) {
				this.gear.setItem(GEAR_EYES, ItemStack.EMPTY);
				playSound(SoundEvents.GLASS_BREAK, 1f, 1f);
			} else this.gear.setChanged();
		}
		return hurt;
	}

	/**
	 * Кто забирает одежду вместо того, чтобы персонаж её ронял (уровни: после гибели в задаче персонаж вернётся одетым). Получает игрока, который
	 * был в персонаже, и снятую одежду; {@code true} — забрал, ронять не надо.
	 */
	@FunctionalInterface
	public interface GearKeeper {
		boolean keep(java.util.UUID player, List<ItemStack> gear);
	}

	private static GearKeeper gearKeeper = (player, gear) -> false;

	public static void setGearKeeper(GearKeeper keeper) {
		gearKeeper = keeper;
	}

	/** Последний игрок, вселявшийся в персонажа: погибший без всадника отдаёт одежду ему. Только в памяти. */
	private java.util.UUID lastRider;

	/** Снять всю одежду и уронить, если её не забрал {@link GearKeeper} для игрока {@code rider}. */
	private void shedGear(ServerLevel level, @org.jetbrains.annotations.Nullable java.util.UUID rider) {
		List<ItemStack> worn = new java.util.ArrayList<>();
		for (ItemStack stack : this.gear.removeAllItems()) if (!stack.isEmpty()) worn.add(stack);
		syncGear();
		if (worn.isEmpty() || rider != null && gearKeeper.keep(rider, worn)) return;
		for (ItemStack stack : worn) spawnAtLocation(level, stack);
	}

	/** Умирая, персонаж роняет всё, что на него надето (или отдаёт уровню, см. {@link GearKeeper}). */
	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean playerKill) {
		super.dropCustomDeathLoot(level, source, playerKill);
		shedGear(level, getControllingPassenger() instanceof Player rider ? rider.getUUID() : this.lastRider);
	}

	// ---------------------------------------------------------------- вселение

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		ItemStack held = player.getItemInHand(hand);
		if (held.is(favoriteFood())) {
			if (getHealth() >= getMaxHealth()) return InteractionResult.PASS;
			if (!level().isClientSide()) {
				held.consume(1, player);
				eatFavorite();
			}
			return InteractionResult.SUCCESS;
		}
		// автор уровня ставит персонажа на место, чтобы с ним можно было поговорить: Shift+ПКМ пустой рукой в творческом режиме
		if (hand == InteractionHand.MAIN_HAND && player.isCreative() && player.isSecondaryUseActive() && player.getMainHandItem().isEmpty()) {
			if (!level().isClientSide()) {
				setNoAi(!isNoAi());
				setPersistenceRequired();
				if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendOverlayMessage(Component.translatable(isNoAi() ? "message.gameplaymod.character_pinned" : "message.gameplaymod.character_unpinned"));
			}
			return InteractionResult.SUCCESS;
		}
		// в выживании перепрыгнуть в другого нельзя: кликом по нему говорят (LevelMod)
		if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty() || player.isSecondaryUseActive() || isVehicle() || isScripted() || player.isPassenger() && !player.isCreative()) return InteractionResult.PASS;
		if (!level().isClientSide() && !possess(player)) return InteractionResult.PASS;
		return InteractionResult.SUCCESS;
	}

	/** Вселяет игрока (только на сервере). */
	public boolean possess(Player player) {
		if (!player.startRiding(this)) return false;
		this.lastRider = player.getUUID();
		// внутри персонажа игрока не видно (скрытый бесконечный эффект; снимается при выходе и при входе в мир)
		player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
		return true;
	}

	/**
	 * Управляет скрипт (напарник BehaviorMod): своих целей нет (не гуляет, не паникует), занят для выбора и яйца призыва. Снятие возвращает
	 * обычные цели. Не сохраняется: скрипт ставит заново при загрузке.
	 */
	private boolean scripted;

	public boolean isScripted() {
		return this.scripted;
	}

	public void setScripted(boolean value) {
		if (this.scripted == value) return;
		this.scripted = value;
		this.goalSelector.removeAllGoals(goal -> true);
		if (!value) registerGoals();
	}

	/** Второй такой же смешарик (в мире он один): роняет одежду и исчезает. */
	void retireDuplicate(ServerLevel level) {
		for (ItemStack stack : this.gear.removeAllItems()) if (!stack.isEmpty()) spawnAtLocation(level, stack);
		discard();
	}

	/** Shift не выводит из персонажа: в выживании выйти нельзя, в творческом выход по Esc ({@link CharacterDismiss}). */
	public static boolean mustStayPossessed(ServerPlayer player) {
		return (player.gameMode() == GameType.SURVIVAL || player.gameMode() == GameType.CREATIVE) && isPossessing(player);
	}

	/** Остался ли на игроке скрытый эффект невидимости от вселения (для очистки после сбоя). */
	public static boolean hasPossessionEffect(Player player) {
		MobEffectInstance e = player.getEffect(MobEffects.INVISIBILITY);
		return e != null && e.isInfiniteDuration() && !e.isVisible() && !e.showIcon() && !e.isAmbient();
	}

	/** Игрок сейчас вселён в персонажа. */
	public static boolean isPossessing(Player player) {
		return player.getVehicle() instanceof CharacterEntity;
	}

	/** Тело, которое движется вместо игрока: скорость пассажира ни на что не влияет, поэтому внешние силы прикладываются к персонажу. */
	public static Entity body(Player player) {
		return player.getVehicle() instanceof CharacterEntity character ? character : player;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return passenger instanceof Player && !isVehicle();
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player player ? player : null;
	}

	/**
	 * Игрок стал пассажиром — он вселён, и невидимость должна быть на нём. При входе в мир посадка восстанавливается из файла уже после входа игрока
	 * ({@link Characters}: на входе эффект снимается, раз игрок ещё не сидит), поэтому ставить его надо здесь, а не только в {@link #possess}.
	 */
	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);
		if (!level().isClientSide() && passenger instanceof Player player && !player.hasEffect(MobEffects.INVISIBILITY))
			player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (!level().isClientSide() && passenger instanceof Player player && hasPossessionEffect(player)) player.removeEffect(MobEffects.INVISIBILITY);
		if (!level().isClientSide()) setAbilityActive(false);
		setSprinting(false);
	}

	/** Глаза игрока оказываются на уровне лица персонажа (высота глаз задана в типе сущности). */
	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// на скате персонаж лежит: глаза у поверхности, как у скользящего игрока
		double eye = com.smeshariki.gameplay.slide.SlopeSurface.isSliding(this) ? Math.min(getEyeHeight(), 0.5) : getEyeHeight();
		return new Vec3(0, eye - passenger.getEyeHeight() + passenger.getVehicleAttachmentPoint(this).y, 0);
	}

	/**
	 * Глаза вселённого игрока внутри хитбокса персонажа; если бы тот выбирался прицелом «изнутри» (по умолчанию да), любой клик
	 * попадал бы в самого персонажа, а не в блок или существо за ним.
	 */
	@Override
	public boolean canBePickedFromInside() {
		return false;
	}

	/** Пока вселён игрок, собственный ИИ персонажа не работает. */
	@Override
	public boolean isEffectiveAi() {
		return super.isEffectiveAi() && !hasControllingPassenger();
	}

	// ---------------------------------------------------------------- особенности (Ctrl)

	/** У этого персонажа есть особенность по Ctrl. */
	public boolean hasAbility() {
		return getType() == Characters.YOZHIK || getType() == Characters.KARYCH;
	}

	public boolean isAbilityActive() {
		return hasAbility() && this.entityData.get(ABILITY);
	}

	public void setAbilityActive(boolean active) {
		if (this.entityData.get(ABILITY) != active) this.entityData.set(ABILITY, active);
		if (active && getType() == Characters.KARYCH) this.lastGlideTick = this.tickCount;
	}

	/** Падение: безопасная высота у Карыча после планирования вдвое больше. */
	@Override
	protected int calculateFallDamage(double distance, float multiplier) {
		if (is(net.minecraft.tags.EntityTypeTags.FALL_DAMAGE_IMMUNE)) return 0;
		double safe = getAttributeValue(Attributes.SAFE_FALL_DISTANCE) * (getType() == Characters.KARYCH && this.tickCount - this.lastGlideTick < 20 ? 2 : 1);
		return Math.max(0, net.minecraft.util.Mth.ceil((distance - safe) * multiplier * getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER)));
	}

	/** Клиент вселённого: Ёжик свернулся и покатился в сторону, куда смотрел игрок. */
	public void startRoll(float yaw) {
		double r = Math.toRadians(yaw);
		Vec3 v = getDeltaMovement();
		setDeltaMovement(v.x - Math.sin(r) * ROLL_PUSH, v.y, v.z + Math.cos(r) * ROLL_PUSH);
		this.rollDone = false;
	}

	public boolean isRollDone() {
		return this.rollDone;
	}

	/** На сколько рисовать тело ниже коробки, чтобы ноги стояли на скате, а не висели над ним (плавно, только для рисунка). */
	public float slopeDrop;

	/** Ёжик сейчас катится клубком. */
	public boolean isRolling() {
		return getType() == Characters.YOZHIK && isAbilityActive();
	}

	/** Клиент: клубок поворачивается по пройденному пути, крылья плавно раскрываются при планировании. */
	private void updateAbilityAnimation() {
		this.rollRotO.set(this.rollRot);
		this.spreadO = this.spread;
		this.camRollO = this.camRoll;
		if (isRolling()) {
			double dx = getX() - this.xo, dz = getZ() - this.zo, d = Math.hypot(dx, dz);
			float rollRadius = ROLL_RADIUS;
			if (d > 1e-4) {
				new Quaternionf().fromAxisAngleRad((float) (dz / d), 0, (float) (-dx / d), (float) (d / rollRadius)).mul(this.rollRot, this.rollRot);
				this.camRoll += (float) (d / rollRadius);
			}
		} else {
			this.rollRot.identity();
			this.rollRotO.identity();
			// камера доворачивается до нуля коротким путём
			float twoPi = (float) (2 * Math.PI);
			float shift = twoPi * Math.round(this.camRoll / twoPi);
			this.camRoll -= shift;
			this.camRollO -= shift;
			this.camRoll *= 0.7f;
		}
		this.spread += ((getType() == Characters.KARYCH && isAbilityActive() ? 1f : 0f) - this.spread) * 0.3f;
	}

	@Override
	public void travel(Vec3 input) {
		if (isAbilityActive() && getControllingPassenger() instanceof Player rider) {
			this.jumpRequested = false;
			if (isRolling()) {
				roll();
				return;
			} else if (!onGround() && !isInWater()) {
				glide(rider);
				return;
			}
		}
		super.travel(input);
	}

	/** Высота поверхности под точкой (верх ближайшего твёрдого блока ниже ног). */
	private double groundY(double x, double z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int top = (int) Math.floor(getY()) + 2;
		for (int y = top; y > top - 6; y--) {
			pos.set((int) Math.floor(x), y, (int) Math.floor(z));
			BlockState state = level().getBlockState(pos);
			if (state.getBlock() instanceof com.smeshariki.gameplay.slide.SlideBlock slide) {
				double h = slide.surface(state, x - Math.floor(x), z - Math.floor(z));
				if (!Double.isNaN(h)) return y + h;
			}
			VoxelShape shape = state.getCollisionShape(level(), pos);
			if (!shape.isEmpty()) return y + shape.max(Direction.Axis.Y);
		}
		return top - 6;
	}

	/** Клубок: после толчка в сторону взгляда на склоне разгоняется вниз по уклону, на ровном месте катится по инерции и останавливается. Входа игрока не слушает. */
	private void roll() {
		Vec3 v = getDeltaMovement();
		double vx = v.x, vy = v.y, vz = v.z;
		if (onGround()) {
			double d = 0.7;
			double gx = (groundY(getX() + d, getZ()) - groundY(getX() - d, getZ())) / (2 * d);
			double gz = (groundY(getX(), getZ() + d) - groundY(getX(), getZ() - d)) / (2 * d);
			double slope = Math.hypot(gx, gz);
			boolean flat = slope < 0.05;
			if (!flat) {
				double k = Math.min(1, slope * 1.5) / slope;
				vx -= gx * k * ROLL_ACCEL;
				vz -= gz * k * ROLL_ACCEL;
			}
			double friction = flat ? ROLL_FLAT_FRICTION : ROLL_FRICTION;
			vx *= friction;
			vz *= friction;
			vy = -0.0784;
			// выкатился на плато и встал: режим заканчивается
			if (flat && Math.hypot(vx, vz) < ROLL_STOP) this.rollDone = true;
		} else {
			vy = (vy - 0.08) * 0.98;
			vx *= ROLL_AIR_FRICTION;
			vz *= ROLL_AIR_FRICTION;
		}
		double speed = Math.hypot(vx, vz);
		if (speed > ROLL_MAX) {
			vx *= ROLL_MAX / speed;
			vz *= ROLL_MAX / speed;
		}
		setDeltaMovement(vx, vy, vz);
		move(MoverType.SELF, getDeltaMovement());
	}

	/** Планирование: вперёд по взгляду, падение медленное (взгляд вниз — пикирование), вверх подняться нельзя. */
	private void glide(Player rider) {
		Vec3 look = rider.getLookAngle();
		double h = Math.hypot(look.x, look.z);
		double dirX = h > 1e-4 ? look.x / h : 0, dirZ = h > 1e-4 ? look.z / h : 0;
		double dive = Math.max(0, rider.getXRot()) / 90.0;
		double speed = GLIDE_SPEED * (1 + dive);
		Vec3 v = getDeltaMovement();
		double vx = v.x + (dirX * speed - v.x) * 0.1, vz = v.z + (dirZ * speed - v.z) * 0.1;
		double target = -(GLIDE_FALL + GLIDE_DIVE_FALL * dive);
		// сервер считает висящего над землёй «летающим», поэтому падение всегда заметно (>0,03 за тик)
		double vy = Math.min(Math.min(v.y, 0) + (target - Math.min(v.y, 0)) * 0.3, -GLIDE_FALL * 0.9);
		setDeltaMovement(vx, vy, vz);
		move(MoverType.SELF, getDeltaMovement());
		setRot(rider.getYRot(), rider.getXRot() * 0.5f);
		this.yRotO = this.yBodyRot = this.yHeadRot = getYRot();
	}

	// ---------------------------------------------------------------- управление и двойной прыжок

	/** Двойной прыжок — особенность только Кроша. */
	public boolean canDoubleJump() {
		return getType() == Characters.KROSH;
	}

	/** Клиент вселённого сообщает, что нажат прыжок. */
	public void requestJump() {
		this.jumpRequested = true;
	}

	@Override
	protected Vec3 getRiddenInput(Player player, Vec3 input) {
		if (frozenHere()) return Vec3.ZERO;
		// в воздухе вбок управляет как игрок, на земле ходит боком вдвое медленнее
		return new Vec3(player.xxa * (inAir() ? 1f : 0.5f), 0, player.zza);
	}

	private boolean inAir() {
		return !onGround() && !isInWater() && !isInLava();
	}

	/** Сам (без игрока) плавать не умеет: в воде прыжок только от дна. Вселённый всплывает за стамину ({@link #swimUp}). */
	@Override
	protected void jumpInLiquid(TagKey<Fluid> fluid) {
		if (!fluid.equals(FluidTags.WATER)) {
			super.jumpInLiquid(fluid);
			return;
		}
		if (onGround()) jumpFromGround();
	}

	@Override
	protected float getRiddenSpeed(Player player) {
		// ваниль берёт в воздухе десятую часть этой скорости: получается управление игрока в воздухе (0,02 и 0,026 при беге)
		if (inAir()) return player.isSprinting() ? AIR_CONTROL_SPRINT : AIR_CONTROL;
		return (float) getAttributeValue(Attributes.MOVEMENT_SPEED) * RIDDEN_SPEED_FACTOR;
	}

	/** Ваниль даёт вселённому бегать (Ctrl и двойное W), только если транспорт это разрешает. */
	@Override
	public boolean canSprint() {
		return true;
	}

	/** Удар в лоб: направление бега входит в стену круче этого (синус угла к стене; 45°). Положе — касание, бег не гаснет. */
	private static final double HEAD_ON = Math.sin(Math.toRadians(45));

	/**
	 * Столкновение прошлого тика — удар в лоб, а не касание. Куда хотел бежать — по нажатым клавишам и повороту (скорость по упёршейся оси
	 * столкновение уже обнулило); упёршаяся ось — та, где скорость обнулена. Упёрся по обеим (угол) — в лоб.
	 */
	private boolean headOn(Vec3 input) {
		double len = Math.hypot(input.x, input.z);
		if (len < 1.0e-4) return true;
		double sin = Math.sin(Math.toRadians(getYRot())), cos = Math.cos(Math.toRadians(getYRot()));
		double fx = (input.x * cos - input.z * sin) / len, fz = (input.z * cos + input.x * sin) / len;
		Vec3 v = getDeltaMovement();
		boolean blockedX = v.x == 0 && Math.abs(fx) > 0.05, blockedZ = v.z == 0 && Math.abs(fz) > 0.05;
		if (blockedX && blockedZ) return true;
		if (blockedX) return Math.abs(fx) > HEAD_ON;
		if (blockedZ) return Math.abs(fz) > HEAD_ON;
		return false;
	}

	/** Прибавка к прыжку с бега (ваниль даёт 0,2 вперёд): разбег должен заметно удлинять прыжок, а не слегка подталкивать. */
	private static final double SPRINT_JUMP_FORWARD = 0.2, SPRINT_JUMP_UP = 1.0;

	@Override
	public void jumpFromGround() {
		super.jumpFromGround();
		if (!isSprinting() || isRolling()) return;
		Vec3 v = getDeltaMovement();
		double yaw = Math.toRadians(getYRot());
		// ваниль уже добавила 0,2 по взгляду; доводим до SPRINT_JUMP_FORWARD
		double extra = SPRINT_JUMP_FORWARD - 0.2;
		setDeltaMovement(v.x - Math.sin(yaw) * extra, v.y * SPRINT_JUMP_UP, v.z + Math.cos(yaw) * extra);
	}

	/** До какого тика тело проходит сквозь сущности (держит {@link com.smeshariki.gameplay.rope.PullRopeEntity}). */
	private int passUntil;

	/** Верёвка подтягивает: ещё пару тиков тело не сталкивается с сущностями. */
	public void passEntities() {
		this.passUntil = this.tickCount + 3;
	}

	public boolean passesEntities() {
		return this.tickCount < this.passUntil;
	}

	@Override
	public boolean isPushable() {
		return !passesEntities() && super.isPushable();
	}

	@Override
	protected void pushEntities() {
		if (!passesEntities()) super.pushEntities();
	}

	@Override
	public boolean canCollideWith(Entity other) {
		return !passesEntities() && super.canCollideWith(other);
	}

	/** Этот клиент держит вселённого на месте (ожидание после сброса задачи, см. {@link CharacterFreeze}). */
	public boolean frozenHere() {
		return level().isClientSide() && CharacterFreeze.frozen() && getControllingPassenger() instanceof Player;
	}

	@Override
	protected void tickRidden(Player player, Vec3 riddenInput) {
		if (frozenHere()) {
			// стоит: ни разбега, ни прыжка, ни скольжения; падать, если под ним нет земли, может
			this.jumpRequested = false;
			player.setSprinting(false);
			setDeltaMovement(0, Math.min(0, getDeltaMovement().y), 0);
		}
		super.tickRidden(player, riddenInput);
		// персонаж смотрит туда же, куда игрок
		setRot(player.getYRot(), player.getXRot() * 0.5f);
		this.yRotO = this.yBodyRot = this.yHeadRot = getYRot();
		if (isLocalInstanceAuthoritative() && player.isSprinting()) {
			// столкновение пассажира всегда ложно, поэтому ванильная остановка бега о стену смотрит сюда; клубок не бежит.
			// Касание по касательной бег не гасит (жалоба: на автобеге задел стену или ствол — и дальше шагом), только удар в лоб
			if (this.horizontalCollision && headOn(riddenInput) || isRolling()) player.setSprinting(false);
		}
		// флаг персонажа даёт ту же прибавку к скорости (+30 %), толчок при прыжке с разбега и частицы, что и у игрока
		setSprinting(player.isSprinting() && !isRolling());
		if (!isLocalInstanceAuthoritative()) return;
		if (frozenHere()) return;
		if (onGround()) this.doubleJumpUsed = false;
		boolean swum = false;
		if (isInWater()) {
			// прыжок от дна бесплатный, всплытие тратит стамину; без неё тонет
			boolean jump = this.jumpRequested || player.isJumping();
			this.jumpRequested = false;
			if (jump && onGround()) jumpFromGround();
			else if (jump && this.stamina > 0f) {
				swimUp();
				swum = true;
			}
			// в воде тонет втрое быстрее ванили (её тяга 0,02 за тик): всплытие дорогое, дно близко
			if (!swum && !onGround()) {
				Vec3 v = getDeltaMovement();
				setDeltaMovement(v.x, v.y - WATER_SINK, v.z);
			}
		}
		if (!swum) regenStamina();
		if (isInWater()) return;
		if (this.jumpRequested) {
			this.jumpRequested = false;
			if (onGround()) {
				jumpFromGround();
			} else if (canDoubleJump() && !this.doubleJumpUsed) {
				this.doubleJumpUsed = true;
				resetFallDistance();
				setDeltaMovement(getDeltaMovement().x, getJumpPower(), getDeltaMovement().z);
			}
		}
	}

	// ---------------------------------------------------------------- всплытие за стамину

	/** Скорость всплытия, блоков за тик, и прибавка к вертикальной скорости за тик, пока зажат прыжок. */
	public static final double SWIM_UP = 0.1, SWIM_ACCEL = 0.06;
	/** Добавка к тяге вниз в воде за тик (у ванили 0,02: вместе втрое больше). */
	public static final double WATER_SINK = 0.04;
	/** Расход стамины за тик всплытия (полной хватает на 40 тиков, около 4 блоков вверх). */
	public static final float SWIM_COST = 1f / 40f;
	/** Восстановление за тик (с нуля до полной за 200 тиков) и пауза после последнего всплытия, тиков. */
	public static final float STAMINA_REGEN = 1f / 200f;
	public static final int STAMINA_REGEN_DELAY = 20;

	/** Стамина 0..1 (клиент вселённого: движение считает он; не сохраняется) и тики с последнего всплытия. */
	private float stamina = 1f;
	private int staminaRest;

	/** Стамина для полосы в HUD. */
	public float stamina() {
		return this.stamina;
	}

	/** Восстанавливается сама со временем, после короткой паузы. */
	private void regenStamina() {
		if (this.staminaRest < STAMINA_REGEN_DELAY) this.staminaRest++;
		else this.stamina = Math.min(1f, this.stamina + STAMINA_REGEN);
	}

	/** Зажатый прыжок поднимает вверх, пока есть стамина. */
	private void swimUp() {
		Vec3 v = getDeltaMovement();
		setDeltaMovement(v.x, Math.max(v.y, Math.min(v.y + SWIM_ACCEL, SWIM_UP)), v.z);
		this.stamina = Math.max(0f, this.stamina - SWIM_COST);
		this.staminaRest = 0;
	}
}
