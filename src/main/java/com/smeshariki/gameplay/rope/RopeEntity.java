package com.smeshariki.gameplay.rope;

import java.util.List;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.smeshariki.gameplay.raft.BalloonEntity;
import com.smeshariki.gameplay.raft.RaftEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Тарзанка. Сущность — это свободный конец верёвки (её позиция и есть конец), а постоянное крепление хранится в данных.
 * Верёвка жёсткая (как палка длиной {@link #length()}, её можно менять), но если конец ближе — рисуется провисшей.
 * <ul>
 * <li>{@code FREE} — конец ни за что не зацеплен, сервер двигает его как маятник под действием гравитации;</li>
 * <li>{@code ATTACHED} — конец зафиксирован на блоке, плоту или шаре;</li>
 * <li>{@code HELD} — верёвку держит игрок, схватив её в каком-то месте: рычаг маятника — отрезок от крепления до руки
 * ({@link #armLength()}), остаток верёвки свисает вниз из руки. Его собственный клиент не даёт ему уйти от крепления дальше
 * рычага, поэтому, спрыгнув с уступа, игрок раскачивается.</li>
 * </ul>
 * Крепление и зацепленный конец могут быть на плоту или шаре: тогда хранится сам плот и точка в его системе отсчёта, и они
 * едут и поворачиваются вместе с ним. Если плот разобрали, верёвка падает предметом, как при поломке блока крепления.
 */
public class RopeEntity extends Entity {
	public static final byte FREE = 0, ATTACHED = 1, HELD = 2;
	public static final float MIN_LENGTH = 0.2f, MAX_LENGTH = 24f, DEFAULT_LENGTH = 8f;
	/** Самая длинная верёвка любого вида ({@link #maxLength()}): радиус поиска верёвок вокруг игрока и плота. */
	public static final float LONGEST = 48f;
	/** Верёвка считается натянутой, если конец не ближе длины на столько. */
	private static final double TAUT = 0.1;
	private static final double GRAVITY = 0.05;
	/** Ванильное сопротивление воздуха игроку за тик: по горизонтали и по вертикали. */
	public static final double VANILLA_DRAG_XZ = 0.91;
	private static final double VANILLA_DRAG_Y = 0.98;
	/** Доля скорости, которую качание теряет за тик на самом деле (остальное сопротивление мы возвращаем). */
	public static final double SWING_LOSS = 0.01;
	/** С какой скоростью Shift спускает по верёвке, блоков за тик. */
	private static final float SLIDE_SPEED = 0.15f;

	private static final EntityDataAccessor<Vector3fc> ANCHOR = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Byte> MODE = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Integer> HOLDER = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.INT);
	/** Рычаг, пока верёвку держат (от крепления до руки). */
	private static final EntityDataAccessor<Float> ARM = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> LENGTH = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.FLOAT);
	/** Плот под креплением и под зацепленным концом: номер сущности, {@link #NO_RAFT} или {@link #RAFT_UNLOADED}; на плоту ANCHOR и END_LOCAL — точки в его системе. */
	private static final EntityDataAccessor<Integer> ANCHOR_RAFT = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> END_RAFT = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Vector3fc> END_LOCAL = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.VECTOR3);
	/** Сущность (не плот) под креплением: номер, {@link #NO_RAFT} или {@link #RAFT_UNLOADED}; тогда ANCHOR — сдвиг от её позиции. */
	private static final EntityDataAccessor<Integer> ANCHOR_ENTITY = SynchedEntityData.defineId(RopeEntity.class, EntityDataSerializers.INT);
	private static final int NO_RAFT = -1, RAFT_UNLOADED = -2;
	/** Плот, которого нет так долго, считаем пропавшим, а верёвку — порванной, тиков. */
	private static final int LOST_RAFT_TICKS = 1200;
	/** Конец, зацепленный дальше длины на столько (плот уехал), срывается. */
	private static final double SNAP_SLACK = 1.0;

	/** Блоки крепления и зацепа; нужны только серверу, чтобы заметить, что блок убрали. */
	private BlockPos anchorBlock = BlockPos.ZERO;
	private BlockPos attachBlock;
	/** Постоянные адреса плотов для сервера (номера сущностей меняются при загрузке) и последние найденные плоты. */
	private java.util.UUID anchorRaftId, endRaftId;
	private RaftEntity anchorRaftRef, endRaftRef;
	/** Сущность под креплением для сервера: постоянный адрес и последняя найденная. */
	private java.util.UUID anchorEntityId;
	private Entity anchorEntityRef;
	/** Последнее положение крепления: на случай, когда плот ещё не загружен. */
	private Vec3 lastAnchor;
	private int lostRaftTicks;

	public RopeEntity(EntityType<? extends RopeEntity> type, Level level) {
		super(type, level);
	}

	// ---------------------------------------------------------------- данные

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(ANCHOR, new Vector3f());
		builder.define(MODE, FREE);
		builder.define(HOLDER, -1);
		builder.define(LENGTH, DEFAULT_LENGTH);
		builder.define(ARM, DEFAULT_LENGTH);
		builder.define(ANCHOR_RAFT, NO_RAFT);
		builder.define(END_RAFT, NO_RAFT);
		builder.define(END_LOCAL, new Vector3f());
		builder.define(ANCHOR_ENTITY, NO_RAFT);
	}

	/** Рычаг маятника: пока верёвку держат — от крепления до руки, иначе вся длина. */
	public double armLength() {
		return mode() == HELD ? this.entityData.get(ARM) : length();
	}

	/** Провис дуги между точками {@code a} и {@code b} при длине нити {@code arm}. */
	public static double sagOf(Vec3 a, Vec3 b, double arm) {
		double slack = arm * arm - a.subtract(b).lengthSqr();
		return slack > 0 ? 0.35 * Math.sqrt(slack) : 0;
	}

	/** Точка дуги от {@code a} до {@code b} при доле пути {@code t}. */
	public static Vec3 curvePoint(Vec3 a, Vec3 b, double sag, double t) {
		return a.lerp(b, t).add(0, -sag * 4 * t * (1 - t), 0);
	}

	/** Расстояние от точки до верёвки (не удерживаемой), для проверки сервером. */
	public double distanceToRope(Vec3 point) {
		Vec3 a = anchor(), b = position();
		double sag = sagOf(a, b, length());
		double best = Double.MAX_VALUE;
		for (int i = 0; i <= 48; i++) best = Math.min(best, curvePoint(a, b, sag, i / 48.0).distanceTo(point));
		return best;
	}

	public double length() {
		return this.entityData.get(LENGTH);
	}

	/** Проходит ли верёвка (не удерживаемая, дугой от крепления до конца) через коробку: игрок «в блоке с тарзанкой» цепляется за неё сам. */
	public boolean touches(net.minecraft.world.phys.AABB box) {
		Vec3 a = anchor(), b = position();
		double sag = sagOf(a, b, length());
		int n = (int) (length() * 6) + 8;
		for (int i = 0; i <= n; i++) if (box.contains(curvePoint(a, b, sag, (double) i / n))) return true;
		return false;
	}

	/** Меняет длину в допустимых пределах; возвращает итог. */
	public float adjustLength(float delta) {
		float old = this.entityData.get(LENGTH);
		float l = Math.clamp(old + delta, minLength(), maxLength());
		this.entityData.set(LENGTH, l);
		// рычаг растёт и убывает вместе с длиной: свисающий из руки хвост не меняется
		this.entityData.set(ARM, Math.clamp(this.entityData.get(ARM) + (l - old), minLength(), l));
		return l;
	}

	public Vec3 anchor() {
		return anchor(1f);
	}

	/** Крепление при кадре partialTick: на плоту оно двигается и между тиками. */
	public Vec3 anchor(float partialTick) {
		Vector3fc a = this.entityData.get(ANCHOR);
		Vec3 stored = new Vec3(a.x(), a.y(), a.z());
		if (anchorOnEntity()) {
			Entity e = level().getEntity(this.entityData.get(ANCHOR_ENTITY));
			if (e != null && !(e instanceof RopeEntity)) return lastAnchor = e.getPosition(partialTick).add(stored);
			return lastAnchor != null ? lastAnchor : position();
		}
		if (!anchorOnRaft()) return stored;
		RaftEntity raft = raftById(this.entityData.get(ANCHOR_RAFT));
		if (raft != null) return lastAnchor = raft.fromLocal(stored, partialTick);
		return lastAnchor != null ? lastAnchor : position();
	}

	/** Плот под креплением или null, если крепление на блоке или плот не загружен. */
	/** UUID плота, на котором крепление, или null: виден и тогда, когда плот не загружен. */
	/** Плот, к которому прикреплён свободный конец (тарзанка, оканчивающаяся на корзине), или {@code null}. */
	public java.util.UUID endRaftId() {
		return endRaftId;
	}

	/** Сущность, на которой закреплён верёвочный якорь; {@code null} — крепление на блоке или плоту. */
	public java.util.UUID anchorEntityId() {
		return anchorEntityId;
	}

	public java.util.UUID anchorRaftId() {
		return anchorRaftId;
	}

	public RaftEntity anchorRaft() {
		return raftById(this.entityData.get(ANCHOR_RAFT));
	}

	private boolean anchorOnRaft() {
		return this.entityData.get(ANCHOR_RAFT) != NO_RAFT;
	}

	private boolean anchorOnEntity() {
		return this.entityData.get(ANCHOR_ENTITY) != NO_RAFT;
	}

	/** Крепление на блоке мира (не на плоту, шаре или другой сущности): в выживании такую верёвку не удлинить. */
	public boolean anchoredToBlock() {
		return !anchorOnRaft() && !anchorOnEntity();
	}

	private RaftEntity raftById(int id) {
		return id >= 0 && level().getEntity(id) instanceof RaftEntity raft ? raft : null;
	}

	public byte mode() {
		return this.entityData.get(MODE);
	}

	public int holderId() {
		return this.entityData.get(HOLDER);
	}

	/** Точка на теле, куда приходит конец верёвки в руках. Вселённый держит её телом персонажа: сам игрок сидит в нём низко. */
	public static Vec3 holdPoint(Entity holder, float partialTick) {
		Entity body = holder instanceof Player player ? com.smeshariki.gameplay.character.CharacterEntity.body(player) : holder;
		return body.getPosition(partialTick).add(0, body.getBbHeight() * 0.6, 0);
	}

	/** Верёвка, конец которой сейчас держит игрок или персонаж без игрока (на обеих сторонах). */
	public static RopeEntity heldBy(Entity holder) {
		List<RopeEntity> list = holder.level().getEntitiesOfClass(RopeEntity.class, holder.getBoundingBox().inflate(LONGEST + 4),
				r -> r.mode() == HELD && r.holderId() == holder.getId());
		return list.isEmpty() ? null : list.get(0);
	}

	// ---------------------------------------------------------------- действия (сервер)

	/** Первый клик предметом: крепление на блоке, конец в руке. */
	public void setupAnchor(BlockPos block, Vec3 anchor, Player holder) {
		this.anchorBlock = block;
		this.entityData.set(ANCHOR, new Vector3f((float) anchor.x, (float) anchor.y, (float) anchor.z));
		take(holder, length());
	}

	/** Первый клик предметом по плоту: крепление на нём, конец в руке. */
	public void setupAnchor(RaftEntity raft, Vec3 anchor, Player holder) {
		anchorOnto(raft, anchor);
		take(holder, length());
	}

	/** Первый клик предметом по сущности: крепление в точке world на ней (едет вместе с ней), конец в руке. */
	public void setupAnchor(Entity target, Vec3 world, Player holder) {
		this.anchorEntityId = target.getUUID();
		this.anchorEntityRef = target;
		this.lastAnchor = world;
		Vec3 local = world.subtract(target.position());
		this.entityData.set(ANCHOR_ENTITY, target.getId());
		this.entityData.set(ANCHOR, new Vector3f((float) local.x, (float) local.y, (float) local.z));
		take(holder, length());
	}

	private void anchorOnto(RaftEntity raft, Vec3 world) {
		Vec3 local = raft.toLocal(world);
		this.anchorRaftId = raft.getUUID();
		this.anchorRaftRef = raft;
		this.lastAnchor = world;
		this.entityData.set(ANCHOR_RAFT, raft.getId());
		this.entityData.set(ANCHOR, new Vector3f((float) local.x, (float) local.y, (float) local.z));
	}

	private void endOnto(RaftEntity raft, Vec3 world) {
		Vec3 local = raft.toLocal(world);
		this.endRaftId = raft.getUUID();
		this.endRaftRef = raft;
		this.attachBlock = null;
		this.entityData.set(END_RAFT, raft.getId());
		this.entityData.set(END_LOCAL, new Vector3f((float) local.x, (float) local.y, (float) local.z));
	}

	private void clearEndRaft() {
		this.endRaftId = null;
		this.endRaftRef = null;
		this.entityData.set(END_RAFT, NO_RAFT);
	}

	/** Верёвка между плотом и шаром, с точки зрения плота: точка на плоту, точка на шаре в мире и длина. */
	public record Link(Vec3 local, Vec3 balloonPoint, double length) {
	}

	/** Верёвки, которыми плот привязан к шару. Обе стороны считают по своим данным. */
	public static List<Link> linksOf(RaftEntity raft) {
		var area = raft.getBoundingBox().inflate(LONGEST + raft.sx() + raft.sy() + raft.sz());
		List<Link> links = new java.util.ArrayList<>();
		for (RopeEntity rope : raft.level().getEntitiesOfClass(RopeEntity.class, area, r -> r.mode() == ATTACHED)) {
			RaftEntity start = rope.raftById(rope.entityData.get(ANCHOR_RAFT)), end = rope.raftById(rope.entityData.get(END_RAFT));
			Vec3 anchorLocal = vec(rope.entityData.get(ANCHOR)), endLocal = vec(rope.entityData.get(END_LOCAL));
			if (start == raft && end instanceof BalloonEntity b) links.add(new Link(anchorLocal, b.fromLocal(endLocal, 1f), rope.length()));
			else if (end == raft && start instanceof BalloonEntity b) links.add(new Link(endLocal, b.fromLocal(anchorLocal, 1f), rope.length()));
		}
		return links;
	}

	private static Vec3 vec(Vector3fc v) {
		return new Vec3(v.x(), v.y(), v.z());
	}

	/** Постройку собрали в плот: верёвки, закреплённые на её блоках, переходят на него. Иначе они порвались бы вместе с блоками. */
	public static void adopt(RaftEntity raft, java.util.Set<BlockPos> blocks) {
		var area = raft.getBoundingBox().inflate(LONGEST + raft.sx() + raft.sy() + raft.sz());
		for (RopeEntity rope : raft.level().getEntitiesOfClass(RopeEntity.class, area)) {
			if (rope.anchoredToBlock() && blocks.contains(rope.anchorBlock)) rope.anchorOnto(raft, rope.anchor());
			if (rope.attachBlock != null && blocks.contains(rope.attachBlock)) rope.endOnto(raft, rope.position());
		}
	}

	/** Игрок или персонаж без игрока схватил верёвку там, где его рука: рычаг — расстояние от крепления до руки. */
	public void grabBy(Entity holder) {
		take(holder, Math.clamp(holdPoint(holder, 1f).distanceTo(anchor()), minLength(), length()));
	}

	/** Игрок сломал верёвку, ударив по креплению. */
	public void breakBy(Player player) {
		if (level() instanceof ServerLevel server) breakRope(server, !player.hasInfiniteMaterials());
	}

	private void take(Entity holder, double arm) {
		this.entityData.set(ARM, (float) arm);
		this.entityData.set(MODE, HELD);
		this.entityData.set(HOLDER, holder.getId());
		this.attachBlock = null;
		clearEndRaft();
		setDeltaMovement(Vec3.ZERO);
		setPos(holdPoint(holder, 1f));
	}

	/** Тот, кто держал конец, только что зацепил его: сам он за эту верёвку заново не хватается, пока не выйдет из её клетки. */
	private void markHolderReleased() {
		if (mode() == HELD && level().getEntity(holderId()) instanceof Player player) Ropes.markReleased(player.getUUID(), getId());
	}

	/** Зацепляет конец за блок; false, если точка дальше длины верёвки. */
	public boolean attachTo(BlockPos block, Vec3 point) {
		if (point.distanceTo(anchor()) > length()) return false;
		markHolderReleased();
		this.entityData.set(MODE, ATTACHED);
		this.entityData.set(HOLDER, -1);
		this.attachBlock = block;
		setDeltaMovement(Vec3.ZERO);
		setPos(point);
		return true;
	}

	/** Зацепляет конец за плот или шар; false, если точка дальше длины верёвки. */
	public boolean attachTo(RaftEntity raft, Vec3 point) {
		if (point.distanceTo(anchor()) > length()) return false;
		markHolderReleased();
		this.entityData.set(MODE, ATTACHED);
		this.entityData.set(HOLDER, -1);
		endOnto(raft, point);
		setDeltaMovement(Vec3.ZERO);
		setPos(point);
		return true;
	}

	/** Отпускает конец: дальше он падает и качается. */
	public void release() {
		if (mode() == HELD && level().getEntity(holderId()) instanceof Entity holder) {
			// отпустивший не должен тут же зацепиться снова, пока не выйдет из клетки верёвки
			if (holder instanceof Player player) Ropes.markReleased(player.getUUID(), getId());
			// конец — внизу свисающего из руки хвоста
			Vec3 end = holdPoint(holder, 1f).add(0, -(length() - this.entityData.get(ARM)), 0);
			if (!level().noCollision(this, getBoundingBox().move(end.subtract(position())))) end = holdPoint(holder, 1f);
			setPos(end);
			// конец продолжает движение руки, а не падает с места
			setDeltaMovement(holder.getKnownMovement());
		}
		this.entityData.set(MODE, FREE);
		this.entityData.set(HOLDER, -1);
		this.attachBlock = null;
		clearEndRaft();
	}

	/** Наименьшая длина верёвки этого вида. */
	public float minLength() {
		return MIN_LENGTH;
	}

	/** Наибольшая длина верёвки этого вида. */
	public float maxLength() {
		return MAX_LENGTH;
	}

	/**
	 * На сколько блоков по горизонтали конец (или держащий) может отойти от отвеса под креплением; у обычной тарзанки без предела.
	 * Дальше скорость наружу гасится, а тело возвращается не больше чем на {@link #SWING_PULL} за тик (схватившего издалека подтягивает).
	 */
	protected double swingLimit() {
		return Double.POSITIVE_INFINITY;
	}

	static final double SWING_PULL = 0.3;

	/** Сдвиг по горизонтали, возвращающий точку p в круг размаха под креплением a (не больше SWING_PULL). */
	Vec3 swingFix(Vec3 a, Vec3 p) {
		double lim = swingLimit();
		if (Double.isInfinite(lim)) return Vec3.ZERO;
		double dx = p.x - a.x, dz = p.z - a.z, d = Math.sqrt(dx * dx + dz * dz);
		if (d <= lim) return Vec3.ZERO;
		double k = Math.min(d - lim, SWING_PULL) / d;
		return new Vec3(-dx * k, 0, -dz * k);
	}

	/** Гасит горизонтальную скорость наружу за краем размаха. */
	Vec3 swingClip(Vec3 a, Vec3 p, Vec3 v) {
		double lim = swingLimit();
		if (Double.isInfinite(lim)) return v;
		double dx = p.x - a.x, dz = p.z - a.z, d = Math.sqrt(dx * dx + dz * dz);
		if (d < lim - 0.05 || d < 1e-6) return v;
		double nx = dx / d, nz = dz / d, out = v.x * nx + v.z * nz;
		return out > 0 ? new Vec3(v.x - nx * out, v.y, v.z - nz * out) : v;
	}

	/** Принимает ли верёвка этот предмет из руки держащего её (ПКМ с концом в руке), вместо того чтобы зацепить конец. */
	public boolean takesItem(ItemStack stack) {
		return false;
	}

	/** Сервер: держащий верёвку отдал ей предмет из руки (см. {@link #takesItem}). */
	public void giveItem(Player player, ItemStack stack) {
	}

	/** Предмет, в который верёвка превращается при поломке. */
	protected ItemStack dropStack() {
		return new ItemStack(Ropes.ITEM);
	}

	private void breakRope(ServerLevel level, boolean drop) {
		if (drop) spawnAtLocation(level, dropStack());
		discard();
	}

	// ---------------------------------------------------------------- тик

	/** Хват уже обработан: первый тик в руках особый (см. {@link #keepGrabSpeed}). Только у клиента держащего. */
	private boolean heldSeen;
	/** Наибольшая скорость после хвата, блоков за тик (около 14 блоков в секунду). */
	private static final double GRAB_SPEED_MAX = 0.7;

	/**
	 * Хват: верёвка мгновенно натягивается и ванильная проекция убирала составляющую вдоль неё — до половины скорости (падение шло в никуда), причём сколько именно,
	 * зависело от того, как далеко от отвеса под креплением схватили. Теперь скорость по касательной не меньше скорости до хвата (но не больше {@link #GRAB_SPEED_MAX}):
	 * разгон не зависит от точки хвата.
	 */
	static Vec3 keepGrabSpeed(Vec3 before, Vec3 tangent) {
		double was = Math.min(before.length(), GRAB_SPEED_MAX), now = tangent.length();
		if (now < 0.02 || now >= was) return tangent;
		return tangent.scale(was / now);
	}

	@Override
	public void tick() {
		super.tick();
		if (mode() != HELD) heldSeen = false;
		switch (mode()) {
			case HELD -> tickHeld();
			case FREE -> {
				if (!level().isClientSide()) tickFree();
			}
			default -> {
				RaftEntity raft = raftById(this.entityData.get(END_RAFT));
				if (raft != null) {
					Vector3fc l = this.entityData.get(END_LOCAL);
					setPos(raft.fromLocal(new Vec3(l.x(), l.y(), l.z()), 1f));
				}
			}
		}
		if (level() instanceof ServerLevel server) {
			// только что загруженная верёвка ищет плоты сразу: на ней может висеть другой плот
			boolean searching = this.entityData.get(ANCHOR_RAFT) == RAFT_UNLOADED || this.entityData.get(END_RAFT) == RAFT_UNLOADED
					|| this.entityData.get(ANCHOR_ENTITY) == RAFT_UNLOADED;
			if (searching || tickCount % 10 == 0) validate(server);
		}
	}

	/** Shift спускает по верёвке к её концу: рычаг растёт, игрок опускается. Подняться так нельзя, а сервер видит клавишу в пакете ввода. */
	private void slideDown(Entity holder) {
		if (level().isClientSide() || !(holder instanceof ServerPlayer player) || !player.getLastClientInput().shift()) return;
		float arm = this.entityData.get(ARM), end = (float) length();
		if (arm < end) this.entityData.set(ARM, Math.min(end, arm + SLIDE_SPEED));
	}

	private void tickHeld() {
		Entity holder = level().getEntity(holderId());
		if (holder == null || !holder.isAlive() || (holder instanceof Player p && p.isSpectator())) {
			if (!level().isClientSide()) release();
			return;
		}
		slideDown(holder);
		boolean grabTick = !heldSeen;
		heldSeen = true;
		Vec3 point = holdPoint(holder, 1f);
		setPos(point);
		Vec3 a = anchor();
		if (!level().isClientSide() && point.distanceTo(a) > armLength() + 8) {
			if (anchorRaftId == null && anchorEntityId == null) {
				// закреплена на блоке: якорь не мог «улететь», значит держащего перенесли (сброс миссии, телепорт). Верёвка не ломается (иначе предмет
				// падает под ней, например в лаву, и после сброса её нет), а отпускается и остаётся висеть под креплением
				release();
				setPos(a.x, a.y - length(), a.z);
				setDeltaMovement(Vec3.ZERO);
				return;
			}
			// разрыв: верёвка выпадает предметом и не остаётся на якоре (в творческом предмета нет)
			if (level() instanceof ServerLevel server) breakRope(server, !(holder instanceof Player p && p.hasInfiniteMaterials()));
			return;
		}
		Vec3 fix = correction(a, point, armLength());
		boolean taut = point.distanceTo(a) >= armLength() - TAUT;
		if (taut) holder.resetFallDistance();
		// своего игрока двигает его клиент (сервер принимает его позицию), поэтому жёсткую верёвку применяем там
		if (level().isClientSide() && holder instanceof Player p && p.isLocalPlayer()) {
			// у вселённого верёвка держит персонажа: позицию пассажира задаёт он
			Entity body = p.getRootVehicle();
			if (taut) body.resetFallDistance();
			Vec3 swing = swingFix(a, point.add(fix));
			fix = fix.add(swing);
			if (fix.lengthSqr() > 1e-8) body.move(MoverType.SELF, fix);
			Vec3 v = body.getDeltaMovement();
			Vec3 before = v;
			if (taut && !body.onGround() && !body.isInWater() && !p.isFallFlying() && !p.getAbilities().flying) {
				// ваниль гасит скорость в воздухе, из-за чего маятник замирает за пару секунд: возвращаем это сопротивление
				double xz = (1 - SWING_LOSS) / VANILLA_DRAG_XZ, y = (1 - SWING_LOSS) / VANILLA_DRAG_Y;
				v = new Vec3(v.x * xz, v.y * y, v.z * xz);
			}
			Vec3 clipped = clipVelocity(a, point.add(fix), v, armLength());
			if (grabTick && !body.onGround()) clipped = keepGrabSpeed(before, clipped);
			body.setDeltaMovement(swingClip(a, point.add(fix), clipped));
		} else if (!level().isClientSide() && !(holder instanceof Player)) {
			// персонажа без игрока (напарника) двигает сервер: верёвка держит его там же, без маятника
			if (fix.lengthSqr() > 1e-8) holder.move(MoverType.SELF, fix);
			holder.setDeltaMovement(clipVelocity(a, point.add(fix), holder.getDeltaMovement(), armLength()));
		}
	}

	/** Маятник: точечная масса на жёсткой нити вокруг крепления. */
	private void tickFree() {
		Vec3 a = anchor();
		setDeltaMovement(getDeltaMovement().add(0, -GRAVITY, 0).scale(1 - SWING_LOSS));
		move(MoverType.SELF, getDeltaMovement());
		Vec3 fix = correction(a, position(), length());
		fix = fix.add(swingFix(a, position().add(fix)));
		if (fix.lengthSqr() > 1e-8) move(MoverType.SELF, fix);
		// плот уехал, а конец застрял в блоках: тянем его сквозь них, иначе верёвка вытянется без предела
		if (!anchoredToBlock() && position().distanceTo(a) > length() + SNAP_SLACK) setPos(a.add(position().subtract(a).normalize().scale(length())));
		setDeltaMovement(swingClip(a, position(), clipVelocity(a, position(), getDeltaMovement(), length())));
		if (onGround()) setDeltaMovement(getDeltaMovement().multiply(0.7, 1, 0.7));
	}

	// ---------------------------------------------------------------- ограничение верёвки

	/** Сдвиг, который возвращает конец на расстояние не дальше длины верёвки от крепления. */
	static Vec3 correction(Vec3 a, Vec3 p, double length) {
		Vec3 d = p.subtract(a);
		double dist = d.length();
		if (dist <= length) return Vec3.ZERO;
		return d.scale(length / dist - 1);
	}

	/** Гасит составляющую скорости, тянущую конец дальше натянутой верёвки. */
	static Vec3 clipVelocity(Vec3 a, Vec3 p, Vec3 v, double length) {
		Vec3 d = p.subtract(a);
		double dist = d.length();
		if (dist < 1e-6 || dist < length - TAUT) return v;
		Vec3 n = d.scale(1.0 / dist);
		double outward = v.dot(n);
		return outward > 0 ? v.subtract(n.scale(outward)) : v;
	}

	private void validate(ServerLevel server) {
		if (anchorEntityId != null) {
			// сущность убита или удалена: верёвка падает предметом; выгружена: ждём, пока загрузится снова
			if (anchorEntityRef != null && anchorEntityRef.isRemoved()) {
				if (anchorEntityRef.getRemovalReason() != null && anchorEntityRef.getRemovalReason().shouldDestroy()) {
					breakRope(server, true);
					return;
				}
				anchorEntityRef = null;
				this.entityData.set(ANCHOR_ENTITY, RAFT_UNLOADED);
			}
			if (anchorEntityRef == null) {
				anchorEntityRef = server.getEntity(anchorEntityId);
				if (anchorEntityRef != null) this.entityData.set(ANCHOR_ENTITY, anchorEntityRef.getId());
				else if (tickCount % 10 == 0 && (lostRaftTicks += 10) >= LOST_RAFT_TICKS) {
					breakRope(server, true);
					return;
				}
			}
			if (anchorEntityRef != null) lostRaftTicks = 0;
		} else if (anchorRaftId != null) {
			// разобрали: падает предметом; выгрузили: ждём, пока плот загрузится снова
			if (anchorRaftRef != null && anchorRaftRef.isRemoved()) {
				if (anchorRaftRef.getRemovalReason() != null && anchorRaftRef.getRemovalReason().shouldDestroy()) {
					breakRope(server, true);
					return;
				}
				anchorRaftRef = null;
				this.entityData.set(ANCHOR_RAFT, RAFT_UNLOADED);
			}
			if (anchorRaftRef == null) {
				anchorRaftRef = findRaft(server, anchorRaftId);
				if (anchorRaftRef != null) this.entityData.set(ANCHOR_RAFT, anchorRaftRef.getId());
				else if (tickCount % 10 == 0 && (lostRaftTicks += 10) >= LOST_RAFT_TICKS) {
					breakRope(server, true);
					return;
				}
			}
			if (anchorRaftRef != null) lostRaftTicks = 0;
		} else if (server.getBlockState(anchorBlock).isAir()) {
			breakRope(server, true);
			return;
		}
		if (mode() != ATTACHED) return;
		if (endRaftId != null) {
			if (endRaftRef != null && endRaftRef.isRemoved()) {
				if (endRaftRef.getRemovalReason() != null && endRaftRef.getRemovalReason().shouldDestroy()) {
					release();
					return;
				}
				endRaftRef = null;
				this.entityData.set(END_RAFT, RAFT_UNLOADED);
			}
			if (endRaftRef == null) {
				endRaftRef = findRaft(server, endRaftId);
				if (endRaftRef != null) this.entityData.set(END_RAFT, endRaftRef.getId());
			}
		} else if (attachBlock == null || server.getBlockState(attachBlock).isAir()) {
			release();
			return;
		}
		// одно из креплений уехало: натянутая верёвка срывает конец
		if (position().distanceTo(anchor()) > length() + SNAP_SLACK) release();
	}

	private static RaftEntity findRaft(ServerLevel server, java.util.UUID id) {
		for (RaftEntity r : RaftEntity.ALL) if (r.level() == server && !r.isRemoved() && id.equals(r.getUUID())) return r;
		return null;
	}

	// ---------------------------------------------------------------- взаимодействие

	/** Верёвку берут, отпускают и ломают по ЛКМ через пакеты клиента (см. {@link RopeAction}), а не ударом по сущности. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 96 * 96;
	}

	// ---------------------------------------------------------------- сохранение

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		// как в данных: на плоту это точка в его системе отсчёта, на сущности — сдвиг от её позиции, а не точка в мире
		Vector3fc a = this.entityData.get(ANCHOR);
		out.putDouble("AX", a.x());
		out.putDouble("AY", a.y());
		out.putDouble("AZ", a.z());
		out.putFloat("Length", this.entityData.get(LENGTH));
		out.putInt("BX", anchorBlock.getX());
		out.putInt("BY", anchorBlock.getY());
		out.putInt("BZ", anchorBlock.getZ());
		out.putInt("Attached", attachBlock != null || endRaftId != null ? 1 : 0);
		if (anchorRaftId != null) out.putString("AnchorRaft", anchorRaftId.toString());
		if (anchorEntityId != null) out.putString("AnchorEntity", anchorEntityId.toString());
		if (endRaftId != null) {
			Vector3fc e = this.entityData.get(END_LOCAL);
			out.putString("EndRaft", endRaftId.toString());
			out.putDouble("EX", e.x());
			out.putDouble("EY", e.y());
			out.putDouble("EZ", e.z());
		}
		if (attachBlock != null) {
			out.putInt("TX", attachBlock.getX());
			out.putInt("TY", attachBlock.getY());
			out.putInt("TZ", attachBlock.getZ());
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		this.entityData.set(ANCHOR, new Vector3f((float) in.getDoubleOr("AX", 0), (float) in.getDoubleOr("AY", 0), (float) in.getDoubleOr("AZ", 0)));
		this.entityData.set(LENGTH, Math.clamp(in.getFloatOr("Length", DEFAULT_LENGTH), minLength(), maxLength()));
		this.anchorBlock = new BlockPos(in.getIntOr("BX", 0), in.getIntOr("BY", 0), in.getIntOr("BZ", 0));
		boolean attached = in.getIntOr("Attached", 0) != 0;
		this.anchorRaftId = uuid(in.getStringOr("AnchorRaft", ""));
		this.endRaftId = uuid(in.getStringOr("EndRaft", ""));
		this.entityData.set(ANCHOR_RAFT, anchorRaftId != null ? RAFT_UNLOADED : NO_RAFT);
		this.anchorEntityId = uuid(in.getStringOr("AnchorEntity", ""));
		this.entityData.set(ANCHOR_ENTITY, anchorEntityId != null ? RAFT_UNLOADED : NO_RAFT);
		this.entityData.set(END_RAFT, endRaftId != null ? RAFT_UNLOADED : NO_RAFT);
		this.entityData.set(END_LOCAL, new Vector3f((float) in.getDoubleOr("EX", 0), (float) in.getDoubleOr("EY", 0), (float) in.getDoubleOr("EZ", 0)));
		this.attachBlock = attached && endRaftId == null ? new BlockPos(in.getIntOr("TX", 0), in.getIntOr("TY", 0), in.getIntOr("TZ", 0)) : null;
		// конец в руках после перезагрузки не держит никто
		this.entityData.set(MODE, attached ? ATTACHED : FREE);
		this.entityData.set(HOLDER, -1);
	}

	private static java.util.UUID uuid(String text) {
		try {
			return text.isEmpty() ? null : java.util.UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
