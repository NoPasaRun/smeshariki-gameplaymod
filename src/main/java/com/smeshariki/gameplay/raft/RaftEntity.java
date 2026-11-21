package com.smeshariki.gameplay.raft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import java.util.Set;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.smeshariki.gameplay.current.CurrentField;
import com.smeshariki.gameplay.lava.LavaWaveEntity;
import com.smeshariki.gameplay.rope.RopeEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Плот из блоков, плывущий по течению. Блоки убраны из мира и лежат здесь; сущность плавает на поверхности воды,
 * её блоки добавляются к столкновениям ({@code EntityGetterMixin}), так что по плоту можно ходить, а стоящих на нём
 * сущностей он несёт с собой. Позиция сущности — центр основания. Движение считает сервер, клиент получает позицию
 * обычными пакетами; своего игрока на клиенте двигает его клиент, остальных — сервер.
 */
public class RaftEntity extends Entity {
	/** Все загруженные плоты обеих сторон (клиентские и серверные), их различает {@code level()}. */
	public static final List<RaftEntity> ALL = new CopyOnWriteArrayList<>();
	/** Скорость плота при полном течении под ним, блоков за тик (6,66 блока/с; брёвна на реке идут так же). Тела в течении — {@code Currents.SPEED}. */
	public static final double SPEED = 6.66 / 20;
	/** Насколько основание плота погружено в воду. */
	private static final double DRAFT = 0.6;
	/** На волне лавы: предел подъёма и спуска за тик и доля разницы со скоростью волны, которую плот догоняет за тик. */
	private static final double SWELL_CLIMB = 0.4, SWELL_GRIP = 0.3;
	/**
	 * Обрушение волны лавы топит плоты под ней: верх на {@code LAVA_SUNK} ниже поверхности, стоящий горит. Под лавой плот держится
	 * {@code LAVA_HOLD} тиков после обрушения или пока его не поднимет следующая волна; между волнами лежит на лаве. На воде волн нет.
	 */
	private static final double LAVA_SUNK = 0.4;
	private static final int LAVA_HOLD = 40;
	/** Сколько тиков ещё держаться под лавой (на сервере и клиенте считается одинаково, в NBT не пишется). */
	private int sunkTicks;
	/** Не больше стольких колонок опрашиваем у воды за тик. */
	private static final int MAX_SAMPLES = 16;

	private static final EntityDataAccessor<Integer> SX = SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SY = SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SZ = SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.INT);
	/** Время мира начала разрушения ({@code | 1}, чтобы ноль значил «целый»). */
	private static final EntityDataAccessor<Integer> DESTROYED = SynchedEntityData.defineId(RaftEntity.class, EntityDataSerializers.INT);
	/** Сколько тиков длится разрушение: блоки разлетаются, крутятся и сжимаются, потом сущность исчезает. */
	public static final int DESTROY_TICKS = 30;

	/** Блоки плота: индекс {@code x + sx * (z + sz * y)}; воздух — пустая клетка. */
	private BlockState[] cells = new BlockState[0];
	/** Горизонтальная скорость, вертикальная скорость. */
	protected double vx, vy, vz;
	/** Позиция в конце прошлого тика: от неё считаем, на сколько сдвинуть тех, кто стоял на плоту. */
	private double lastX, lastY, lastZ;
	private boolean started;
	/** Клиент: последняя позиция, присланная сервером, и её шаг; клиент считает движение сам, а присланное — лишь поправка. */
	protected Vec3 serverPos;
	private Vec3 serverDelta;
	/** Клиент: {@code clientAge}, когда пришла последняя позиция сервера. */
	private int serverAt;
	/** Клиент: сервер перенёс плот (шаг пакетов не похож на движение) — поставить сразу. */
	private boolean teleported;
	/** Сдвиг этого тика от сверки с сервером или переноса, а не от своей скорости; стоящих он везёт, но инерцией не считается. */
	protected Vec3 correction = Vec3.ZERO;
	private int clientAge;
	/** Насколько клиентский плот может разойтись с серверным, прежде чем его вернут на место рывком, блоков; меньшее догоняется плавно. */
	private static final double SNAP_DISTANCE = 32.0;
	/** Доля расхождения с сервером, убираемая клиентом за тик при малом расхождении (как у шара): незаметно. */
	private static final double PULL = 0.02;
	/** Доля растёт на CATCHUP_GROWTH за блок расхождения до CATCHUP: 4 блока догоняются примерно за 15 тиков. */
	private static final double CATCHUP = 0.15, CATCHUP_GROWTH = 0.03;
	/** Предел догоняющего сдвига за тик, блоков. */
	private static final double MAX_CATCHUP = 2.0;
	/** С какого расхождения клиент подтягивает и скорость к серверной, и доля за тик. */
	private static final double SYNC_FROM = 1.0, VELOCITY_SYNC = 0.2;
	/** Шаг пакетов, отличающийся от прошлого шага и от своей скорости больше этого, — перенос, а не движение, блоков. */
	private static final double TELEPORT_JUMP = 1.5;
	private static final int LOAD_HOLD_TICKS = 40, HANG_PASSES = 4;
	private boolean loadedFromSave;
	/** Потеря горизонтальной скорости за тик в воздухе (как у тарзанки, {@code RopeEntity.SWING_LOSS}). */
	public static final double AIR_LOSS = 0.01;
	/** Тяжесть за тик без воды под плотом и предел скорости падения. */
	public static final double FALL = 0.04, MAX_FALL = 0.5;
	/** Сколько тиков после броска ({@link #fling}) плот летит, не слушая воду под собой: иначе вода сразу погасила бы подъём. */
	private static final int FLING_TICKS = 5;
	private int flungTicks;
	/** Ванильное сопротивление воздуха по горизонтали за тик. */
	private static final double AIR_DRAG = 0.91;
	/** Своя скорость больше этого (блоков за тик) телам не передаётся: защита от сбоя. Сдвиги сверки и переноса в неё не входят ({@link #correction}). */
	private static final double MAX_INERTIA = 4.0;
	/** Кто стоял на плоту в прошлый тик. */
	private Set<LivingEntity> riders = new HashSet<>();
	/** Только что вышли за край плота, но ещё числятся на земле. */
	private Set<LivingEntity> leaving = new HashSet<>();
	/** Сошедшие с плота в полёте и его скорость по горизонтали. Состояние на время полёта, не сохраняется. */
	private final Map<LivingEntity, Vec3> inertia = new HashMap<>();
	/** Масса тела в блоках плота при обмене импульсом. */
	private static final double BODY_MASS = 5.0;
	/** Предел одной добавки скорости от толчка, блоков за тик: защита от присланного клиентом. */
	private static final double MAX_PUSH = 0.5;
	/** Клиент: отправить серверу толчок своего тела (ставит клиентская часть мода). */
	public static java.util.function.Consumer<RaftPush> clientSend = p -> {
	};
	/** Число блоков плота — его масса; -1 — пересчитать. */
	private int mass = -1;
	/** Свободное место для клиентского рендерера. */
	public Object clientCache;

	public RaftEntity(EntityType<? extends RaftEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setInvulnerable(true);
	}

	// ---------------------------------------------------------------- данные

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(SX, 1);
		builder.define(SY, 1);
		builder.define(SZ, 1);
		builder.define(DESTROYED, 0);
	}

	// ---------------------------------------------------------------- разрушение

	public boolean destroyed() {
		return this.entityData.get(DESTROYED) != 0;
	}

	/** Доля пройденного разрушения от 0 до 1; у целого -1. */
	public float destroyProgress(float partialTick) {
		if (!destroyed()) return -1f;
		return Math.clamp(((int) level().getGameTime() - this.entityData.get(DESTROYED) + partialTick) / DESTROY_TICKS, 0f, 1f);
	}

	/**
	 * Разрушить (сервер): плот перестаёт держать и везти стоящих, верёвки на нём пропадают без предметов, блоки разлетаются (рисует клиент), осколки и звук —
	 * из его же блоков, на воде ещё брызги. Через {@link #DESTROY_TICKS} сущность исчезает; разрушаемая в файл мира не пишется.
	 */
	public void destroy() {
		if (destroyed() || !(level() instanceof ServerLevel level)) return;
		this.entityData.set(DESTROYED, (int) level.getGameTime() | 1);
		java.util.UUID id = getUUID();
		for (RopeEntity rope : level.getEntitiesOfClass(RopeEntity.class, getBoundingBox().inflate(64), r -> id.equals(r.anchorRaftId()))) rope.discard();
		int filled = 0;
		Map<BlockState, Integer> counts = new HashMap<>();
		for (BlockState c : cells) if (c != null && !c.isAir()) {
			filled++;
			counts.merge(c, 1, Integer::sum);
		}
		if (filled == 0) return;
		int step = Math.max(1, filled / 60), seen = 0;
		int sx = sx(), sz = sz();
		double ox = originX(), oz = originZ();
		for (int i = 0; i < cells.length; i++) {
			BlockState c = cells[i];
			if (c == null || c.isAir() || seen++ % step != 0) continue;
			int ix = i % sx, iz = (i / sx) % sz, iy = i / (sx * sz);
			level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, c), ox + ix + 0.5, getY() + iy + 0.5, oz + iz + 0.5, 4, 0.3, 0.3, 0.3, 0.1);
		}
		BlockState main = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
		var sound = main.getSoundType();
		level.playSound(null, getX(), getY() + sy() / 2.0, getZ(), sound.getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS, Math.min(2f, sound.getVolume() + filled / 100f), sound.getPitch() * 0.8f);
		BlockPos under = BlockPos.containing(getX(), getY() - 0.5, getZ());
		if (level.getFluidState(under).is(FluidTags.WATER) || level.getFluidState(under.above()).is(FluidTags.WATER)) {
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH, getX(), getY() + 0.8, getZ(), 30, sx / 3.0, 0.1, sz / 3.0, 0.2);
		}
	}

	/** Разрушает все плоты (брёвна, шары), чья позиция в box и проходит inside. Сколько разрушено. */
	public static int destroyIn(ServerLevel level, AABB box, java.util.function.Predicate<Vec3> inside) {
		int n = 0;
		for (RaftEntity raft : level.getEntitiesOfClass(RaftEntity.class, box, r -> !r.destroyed() && inside.test(r.position()))) {
			raft.destroy();
			n++;
		}
		return n;
	}

	@Override
	public boolean shouldBeSaved() {
		return !destroyed() && super.shouldBeSaved();
	}

	public int sx() {
		return this.entityData.get(SX);
	}

	public int sy() {
		return this.entityData.get(SY);
	}

	public int sz() {
		return this.entityData.get(SZ);
	}

	/** Западный (минимальный по x) край плота. */
	public double originX() {
		return getX() - sx() / 2.0;
	}

	public double originZ() {
		return getZ() - sz() / 2.0;
	}

	/** Задаёт размеры и блоки (сервер при создании; клиент — из пакета). */
	public void setCells(int sx, int sy, int sz, BlockState[] cells) {
		this.entityData.set(SX, sx);
		this.entityData.set(SY, sy);
		this.entityData.set(SZ, sz);
		this.cells = cells;
		this.clientCache = null;
		this.mass = -1;
	}

	public BlockState[] cells() {
		return cells;
	}

	/** Блок плота в клетке (ix, iy, iz) от его угла; воздух вне границ. */
	public BlockState cellAt(int ix, int iy, int iz) {
		int sx = sx(), sy = sy(), sz = sz();
		if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) return Blocks.AIR.defaultBlockState();
		int i = ix + sx * (iz + sz * iy);
		if (i >= cells.length) return Blocks.AIR.defaultBlockState();
		BlockState s = cells[i];
		return s == null ? Blocks.AIR.defaultBlockState() : s;
	}

	// ---------------------------------------------------------------- движение

	@Override
	public void tick() {
		super.tick();
		if (!started) {
			lastX = getX();
			lastY = getY();
			lastZ = getZ();
			started = true;
		}
		if (destroyed()) {
			if (!level().isClientSide() && destroyProgress(0f) >= 1f) discard();
			return;
		}
		if (level().isClientSide()) {
			reconcile();
			if (cells.length > 0) simulate();
		} else {
			simulate();
		}
		carry();
		correction = Vec3.ZERO;
		lastX = getX();
		lastY = getY();
		lastZ = getZ();
	}

	/**
	 * На клиенте позицию двигает не пакет, а та же симуляция, что и на сервере: иначе плот прыгал бы между тиками и
	 * уходил из-под игрока. Присланная позиция только запоминается; если клиент разошёлся с ней сильно (вошёл в мир
	 * при уже плывущем плоте, рывок сети), возвращаем плот к ней вместе со скоростью.
	 */
	@Override
	public void setPos(double x, double y, double z) {
		if (started && level().isClientSide()) {
			Vec3 now = new Vec3(x, y, z);
			if (serverPos != null) {
				// прошлый шаг пакетов старше тика — сервер стоял (неподвижное ваниль не шлёт)
				Vec3 before = serverDelta == null || clientAge - serverAt > 2 ? Vec3.ZERO : serverDelta;
				serverDelta = now.subtract(serverPos);
				// перенос (команда, сброс): шаг не похож ни на прошлый шаг сервера, ни на свою скорость. Быстрый полёт (жерло) — похож
				double jump = Math.min(serverDelta.distanceTo(before), serverDelta.distanceTo(new Vec3(vx, vy, vz)));
				if (jump > TELEPORT_JUMP) teleported = true;
			}
			serverPos = now;
			serverAt = clientAge;
			return;
		}
		// сервер: перенос извне (команда, сброс уровня) — не скорость плота; свои шаги плот делает через place
		if (started) correction = correction.add(x - getX(), y - getY(), z - getZ());
		super.setPos(x, y, z);
	}

	protected void place(double x, double y, double z) {
		super.setPos(x, y, z);
	}

	protected double snapDistance() {
		return SNAP_DISTANCE;
	}

	/** Скорость сервера по шагу пакетов; ноль, если пакетов давно не было (сервер стоит). */
	private Vec3 serverVelocity() {
		return serverDelta == null || clientAge - serverAt > 2 ? Vec3.ZERO : serverDelta;
	}

	protected void reconcile() {
		clientAge++;
		if (serverPos == null) return;
		boolean joining = clientAge <= 10;
		Vec3 from = position();
		if (teleported) {
			// перенос: ставим сразу и берём скорость сервера (после команды — ноль)
			teleported = false;
			Vec3 v = serverVelocity();
			vx = v.x;
			vy = v.y;
			vz = v.z;
			place(serverPos.x, serverPos.y, serverPos.z);
			correction = correction.add(position().subtract(from));
			return;
		}
		double d = from.distanceTo(serverPos);
		if (joining || d > snapDistance()) {
			Vec3 v = serverVelocity();
			vx = v.x;
			vy = v.y;
			vz = v.z;
			if (!joining) {
				place(serverPos.x, serverPos.y, serverPos.z);
				correction = correction.add(position().subtract(from));
			}
			return;
		}
		// микроскопические шаги стоящий не повторил бы и понемногу оказался бы внутри палубы (см. simulate). Стоящих сдвинет carry
		if (d < 1.0e-3) return;
		if (d > SYNC_FROM) {
			// заметно разошлись: своя скорость, видимо, не та (толчок, упор в блок) — подтягиваем и её, иначе расхождение набегало бы снова
			Vec3 v = serverVelocity();
			vx += (v.x - vx) * VELOCITY_SYNC;
			vy += (v.y - vy) * VELOCITY_SYNC;
			vz += (v.z - vz) * VELOCITY_SYNC;
		}
		// догон: доля расхождения за тик растёт с ним (мелкое убирается незаметно, большое — за несколько тиков), сдвиг не больше MAX_CATCHUP
		double k = Math.min(CATCHUP, PULL + CATCHUP_GROWTH * d);
		double step = Math.min(d * k, MAX_CATCHUP);
		Vec3 to = from.add(serverPos.subtract(from).scale(step / d));
		if (free(to.x, to.y, to.z)) {
			place(to.x, to.y, to.z);
			correction = correction.add(position().subtract(from));
		}
	}

	/** Колонки (x, z) плота, в которых есть блоки; для опроса воды берём не больше {@link #MAX_SAMPLES}. */
	private List<int[]> sampleColumns() {
		int sx = sx(), sy = sy(), sz = sz();
		List<int[]> all = new ArrayList<>();
		for (int z = 0; z < sz; z++) {
			for (int x = 0; x < sx; x++) {
				for (int y = 0; y < sy; y++) {
					if (!cellAt(x, y, z).isAir()) {
						all.add(new int[] { x, z });
						break;
					}
				}
			}
		}
		int step = Math.max(1, all.size() / MAX_SAMPLES);
		List<int[]> out = new ArrayList<>();
		for (int i = 0; i < all.size(); i += step) out.add(all.get(i));
		return out;
	}

	/** Поверхность воды или лавы в колонке рядом с текущей высотой плота или NaN, если их нет. */
	private double surfaceAt(int bx, int bz, BlockPos.MutableBlockPos pos) {
		int top = (int) Math.floor(getY()) + 2;
		for (int y = top; y >= top - 5; y--) {
			pos.set(bx, y, bz);
			FluidState f = level().getFluidState(pos);
			if (f.is(FluidTags.WATER) || f.is(FluidTags.LAVA)) {
				return y + f.getHeight(level(), pos);
			}
		}
		return Double.NaN;
	}

	protected void simulate() {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		CurrentField field = CurrentField.of(level());
		double ox = originX(), oz = originZ();
		double surfaceSum = 0, fx = 0, fz = 0, wx = 0, wz = 0;
		int wet = 0;
		boolean swell = false, crash = false;
		List<int[]> columns = sampleColumns();
		for (int[] c : columns) {
			int bx = (int) Math.floor(ox + c[0] + 0.5), bz = (int) Math.floor(oz + c[1] + 0.5);
			double surface = surfaceAt(bx, bz, pos);
			if (Double.isNaN(surface)) continue;
			wet++;
			// волна лавы поднимает поверхность и несёт плавающее, как течение
			LavaWaveEntity.Swell w = LavaWaveEntity.swellAt(level(), bx + 0.5, bz + 0.5);
			if (w.lift() > 0) {
				swell = true;
				if (w.crash()) crash = true;
				surface += w.lift();
				wx += w.vx();
				wz += w.vz();
			}
			surfaceSum += surface;
			if (field == null) continue;
			pos.set(bx, (int) Math.floor(surface - 0.01), bz);
			Vec3 dir = field.flowAt(pos);
			if (dir != null) {
				fx += dir.x;
				fz += dir.z;
			}
		}
		double tx = 0, tz = 0;
		boolean flying = flungTicks > 0;
		if (flying) flungTicks--;
		if (wet > 0 && !flying) {
			// ускорение к скорости течения под плотом; без течения плот постепенно останавливается
			tx = fx / wet * SPEED + wx / wet;
			tz = fz / wet * SPEED + wz / wet;
			// обрушение топит; новая волна (не обрушение) поднимает сразу
			if (crash) this.sunkTicks = LavaWaveEntity.CRASH_TICKS + LAVA_HOLD;
			else if (swell) this.sunkTicks = 0;
			else if (this.sunkTicks > 0) this.sunkTicks--;
			boolean sunk = this.sunkTicks > 0;
			double dy = surfaceSum / wet - DRAFT - (sunk ? sy() + LAVA_SUNK - DRAFT : 0) - getY();
			// склон волны круче, чем подъём воды: медленный плот волна бы накрыла
			double climb = swell || sunk ? SWELL_CLIMB : 0.08;
			vy = Math.clamp(dy * (swell || sunk ? 0.5 : 0.25), -climb, climb);
			// сдвиг меньше 1e-7 ваниль отбрасывает: стоящий не поднялся бы с плотом и понемногу оказался бы внутри палубы, где упирается в блоки
			if (Math.abs(vy) < 1.0e-4) vy = 0;
		} else if (loadHold() > 0 && !flying) {
			// только что загружен: верёвки ещё ищут шар, а плот мог висеть на них в воздухе
			vy = 0;
		} else {
			vy = Math.max(vy - FALL, -MAX_FALL);
		}
		// в воде и на земле скорость за 10 % в тик догоняет течение (без него — гаснет, как от трения); в воздухе только сопротивление, иначе плот на верёвке не качается
		boolean airborne = flying || wet == 0 && free(getX(), getY() - 0.05, getZ());
		double grip = swell ? SWELL_GRIP : airborne ? AIR_LOSS : 0.1;
		vx += (tx - vx) * grip;
		vz += (tz - vz) * grip;
		if (Math.abs(vx) < 1.0e-4) vx = 0;
		if (Math.abs(vz) < 1.0e-4) vz = 0;
		double x = getX(), y = getY(), z = getZ();
		if (vx != 0) {
			if (free(x + vx, y, z)) x += vx;
			else vx = 0;
		}
		if (vz != 0) {
			if (free(x, y, z + vz)) z += vz;
			else vz = 0;
		}
		if (vy != 0) {
			if (free(x, y + vy, z)) y += vy;
			else vy = 0;
		}
		Vec3 held = tether(x, y, z);
		if (held.x != getX() || held.y != getY() || held.z != getZ()) place(held.x, held.y, held.z);
	}

	/**
	 * Натянутые верёвки возвращают плот в позицию (x, y, z) не дальше их длины и гасят скорость от креплений вдоль них.
	 * Позиция после этого; зовут {@code simulate} плота и шара.
	 */
	protected Vec3 tether(double x, double y, double z) {
		Vec3 pulled = hang(new Vec3(x, y, z));
		Vec3 pull = pulled.subtract(x, y, z);
		if (pull.lengthSqr() > 1.0e-12) {
			// натянутая верёвка гасит скорость только вдоль себя: иначе она копилась бы, а поперёк плот качается и свисает под шар
			Vec3 n = pull.normalize();
			double along = vx * n.x + vy * n.y + vz * n.z;
			if (along < 0) {
				vx -= n.x * along;
				vy -= n.y * along;
				vz -= n.z * along;
			}
			if (free(pulled.x, y, z)) x = pulled.x;
			if (free(x, y, pulled.z)) z = pulled.z;
			if (free(x, pulled.y, z)) y = pulled.y;
		}
		return new Vec3(x, y, z);
	}

	/** Сколько тиков плот ещё не падает после загрузки. */
	private int loadHold() {
		return Math.max(0, (level().isClientSide() ? LOAD_HOLD_TICKS - clientAge : loadedFromSave ? LOAD_HOLD_TICKS - tickCount : 0));
	}

	/**
	 * Верёвки от плота к шару и к блокам мира: позиция, в которую плот приходит, если ни одна верёвка не длиннее своей длины. Верёвка
	 * только тянет, поэтому пока она провисает, плот движется сам. Несколько проходов, потому что верёвок обычно несколько.
	 * Шар держат только верёвки к блокам: плот, висящий на нём, шар не тянет.
	 */
	private Vec3 hang(Vec3 pos) {
		List<RopeEntity.Link> links = RopeEntity.linksOf(this);
		if (links.isEmpty()) return pos;
		for (int pass = 0; pass < HANG_PASSES; pass++) {
			for (RopeEntity.Link link : links) {
				if (this instanceof BalloonEntity && !link.fixed()) continue;
				Vec3 span = pos.add(link.offset()).subtract(link.point());
				double d = span.length();
				if (d > link.length()) pos = pos.subtract(span.scale(1 - link.length() / d));
			}
		}
		return pos;
	}

	/** Можно ли плоту стоять с центром основания в (x, y, z): форма ни одного его блока не упирается в блоки мира. */
	protected boolean free(double x, double y, double z) {
		int sx = sx(), sy = sy(), sz = sz();
		double ox = x - sx / 2.0, oz = z - sz / 2.0;
		AABB whole = new AABB(ox, y, oz, ox + sx, y + sy, oz + sz).deflate(1.0e-3);
		if (level().noBlockCollision(this, whole)) return true;
		for (int iy = 0; iy < sy; iy++) {
			for (int iz = 0; iz < sz; iz++) {
				for (int ix = 0; ix < sx; ix++) {
					BlockState s = cellAt(ix, iy, iz);
					if (s.isAir()) continue;
					// по настоящей форме блока: плот из полублоков иначе стоял бы на земле пустой половиной клетки
					for (AABB part : s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
						if (!level().noBlockCollision(this, part.move(ox + ix, y + iy, oz + iz).deflate(1.0e-3))) return false;
					}
				}
			}
		}
		return true;
	}

	/** Точка мира в системе отсчёта плота: так её можно хранить, пока плот едет и поворачивается. */
	public Vec3 toLocal(Vec3 world) {
		return world.subtract(position());
	}

	/** Точка мира для точки плота при кадре partialTick. */
	public Vec3 fromLocal(Vec3 local, float partialTick) {
		return getPosition(partialTick).add(local);
	}

	/** Сдвигает тех, кто стоял на плоту, вместе с ним (и отодвигает тех, в кого он въехал). */
	private void carry() {
		keepInertia();
		double dx = getX() - lastX, dy = getY() - lastY, dz = getZ() - lastZ;
		Vec3 delta = new Vec3(dx, dy, dz);
		// скорость плота — сдвиг без поправок сверки и переносов: их стоящий проезжает с плотом, но не получает как удар или инерцию
		Vec3 motion = delta.subtract(correction);
		boolean real = motion.lengthSqr() <= MAX_INERTIA * MAX_INERTIA;
		Set<LivingEntity> riders = new HashSet<>();
		if (dx != 0 || dy != 0 || dz != 0) {
			AABB area = new AABB(lastX - sx() / 2.0, lastY - 1.0, lastZ - sz() / 2.0, lastX + sx() / 2.0, lastY + sy() + 3.0, lastZ + sz() / 2.0)
					.inflate(0.5, 0, 0.5).expandTowards(dx, dy, dz);
			List<Entity> near = level().getEntities(this, area, this::canCarry);
			if (!near.isEmpty()) {
				List<AABB> before = boxes(lastX, lastY, lastZ);
				List<AABB> after = boxes(getX(), getY(), getZ());
				for (Entity e : near) {
					AABB bb = e.getBoundingBox();
					// стоящий касается палубы, а из-за округления может войти в неё на сотые доли миллионной
					boolean rides = !intersects(before, bb.deflate(1.0e-6)) && intersects(before, bb.move(0, -0.06, 0));
					if (!rides && !intersects(after, bb)) continue;
					e.move(MoverType.SHULKER_BOX, delta);
					if (!rides && e instanceof LivingEntity && real) strike(e, motion);
					if (rides) {
						// move() при подъёме сбрасывает опору: тело стояло на плоту и стоит на нём
						e.setOnGround(true);
						e.verticalCollisionBelow = true;
						e.resetFallDistance();
						if (e instanceof LivingEntity living) riders.add(living);
					}
				}
			}
		}
		Set<LivingEntity> leaving = new HashSet<>();
		if (real) {
			for (LivingEntity e : this.riders) {
				// шагнувший за край ещё тик числится на земле; если и через тик на земле, значит сошёл на сушу
				if (!riders.contains(e) && !e.isRemoved() && e.onGround()) leaving.add(e);
				else launch(e, riders, motion);
			}
			for (LivingEntity e : this.leaving) if (!e.onGround()) launch(e, riders, motion);
		}
		this.riders = riders;
		this.leaving = leaving;
	}

	private void launch(LivingEntity e, Set<LivingEntity> riders, Vec3 delta) {
		if (riders.contains(e) || e.isRemoved()) return;
		e.setDeltaMovement(e.getDeltaMovement().add(delta));
		this.inertia.put(e, new Vec3(delta.x, 0, delta.z));
	}

	/**
	 * Сошедшие с плота летят с его скоростью: ванильное сопротивление воздуха погасило бы её за прыжок, и тело отставало бы от
	 * плота. Поэтому здесь возвращается то, что съело сопротивление. На посадке на плот добавка снимается
	 * (дальше его везёт {@code carry}, иначе скольжение по палубе), на суше и в воде её гасит трение.
	 */
	private void keepInertia() {
		if (this.inertia.isEmpty()) return;
		Iterator<Map.Entry<LivingEntity, Vec3>> it = this.inertia.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<LivingEntity, Vec3> entry = it.next();
			LivingEntity e = entry.getKey();
			Vec3 left = entry.getValue();
			Vec3 v = e.getDeltaMovement();
			if (e.onGround() && standsOnRaft(e)) {
				double size = left.length();
				if (size > 0) {
					// только вдоль добавки и не дальше нуля: своя скорость тела остаётся, назад его не отбрасывает
					Vec3 dir = left.scale(1 / size);
					double removed = Math.clamp(v.dot(dir), 0, size * AIR_DRAG);
					e.setDeltaMovement(v.subtract(dir.scale(removed)));
				}
				it.remove();
			} else if (e.isRemoved() || e.onGround() || e.isInWater() || e.horizontalCollision || e.isPassenger() || e.isFallFlying() || e instanceof Player p && p.getAbilities().flying
					|| e.getControllingPassenger() instanceof Player rider && (rider.getAbilities().flying || RopeEntity.heldBy(rider) != null) || e instanceof Player holder && RopeEntity.heldBy(holder) != null
					|| e instanceof com.smeshariki.gameplay.character.CharacterEntity c && c.isAbilityActive()) {
				it.remove();
			} else {
				e.setDeltaMovement(v.add(left.scale(1 - AIR_DRAG)));
			}
		}
	}

	private boolean canCarry(Entity e) {
		return e != this && drivenHere(e);
	}

	/** Движение тела считает эта сторона: пассажира везёт его транспорт; вселённым персонажем, как и игроком, управляет клиент этого игрока. */
	private static boolean drivenHere(Entity e) {
		if (e.isRemoved() || e.noPhysics || e.isSpectator() || e instanceof RaftEntity || e.isPassenger()) return false;
		Entity driver = e.getControllingPassenger() instanceof Player rider ? rider : e;
		if (e.level().isClientSide()) return driver instanceof Player p && p.isLocalPlayer();
		return !(driver instanceof Player);
	}

	// ---------------------------------------------------------------- обмен импульсом

	/** Меняется ли плот импульсом с телами. Брёвна на реке идут по пути: толкают тела жёстко, как раньше, сами не толкаются. */
	protected boolean exchangesImpulse() {
		return true;
	}

	/** Масса плота — число его блоков. */
	public double mass() {
		if (mass < 0) {
			int n = 0;
			for (BlockState s : cells) if (s != null && !s.isAir()) n++;
			mass = Math.max(1, n);
		}
		return mass;
	}

	/**
	 * Плот въехал в тело: неупругий удар вдоль хода плота по горизонтали. Тело получает общую скорость, плот теряет столько же импульса;
	 * лёгкий плот о тело почти останавливается, тяжёлый почти не замечает.
	 */
	private void strike(Entity e, Vec3 delta) {
		if (!exchangesImpulse()) return;
		double h = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (h < 1.0e-4) return;
		double nx = delta.x / h, nz = delta.z / h;
		Vec3 v = e.getDeltaMovement();
		double vb = v.x * nx + v.z * nz;
		if (vb >= h) return;
		double common = (mass() * h + BODY_MASS * vb) / (mass() + BODY_MASS);
		e.setDeltaMovement(v.add(nx * (common - vb), 0, nz * (common - vb)));
		bump(nx * (common - h), nz * (common - h));
	}

	/**
	 * Тело своим движением {@code move} упёрлось во что-то: если в плот (не тот, на котором стоит), отдаёт ему импульс по упёршейся оси.
	 * Зовёт {@code EntityRaftPushMixin} на стороне, которая двигает тело.
	 */
	public static void pushedBy(Entity e, Vec3 move, double fromX, double fromZ) {
		if (!(e instanceof LivingEntity) || !drivenHere(e) || NO_PUSH.get()[0] > 0) return;
		// плот под ступнями (без запаса вбок, как в under: иначе упёршийся в борт считался бы стоящим на нём)
		AABB bb = e.getBoundingBox();
		AABB feet = new AABB(bb.minX, bb.minY - 0.06, bb.minZ, bb.maxX, bb.minY - 1.0e-6, bb.maxZ);
		for (int axis = 0; axis < 2; axis++) {
			double want = axis == 0 ? move.x : move.z, got = axis == 0 ? e.getX() - fromX : e.getZ() - fromZ;
			if (Math.abs(want) < 1.0e-4 || Math.abs(want - got) < 1.0e-6) continue;
			double sign = Math.signum(want);
			AABB probe = e.getBoundingBox().move(axis == 0 ? sign * 0.02 : 0, 0, axis == 1 ? sign * 0.02 : 0);
			for (RaftEntity r : ALL) {
				if (r.level() != e.level() || r.isRemoved() || r.destroyed() || !r.exchangesImpulse()) continue;
				if (!r.touches(probe) || r.touches(feet)) continue;
				// висящий на верёвке, привязанной к этому плоту, его не толкает: сила внутренняя
				RopeEntity rope = RopeEntity.heldBy(e.getControllingPassenger() instanceof Player rider ? rider : e);
				if (rope != null && rope.anchorRaft() == r) break;
				double rel = want - (axis == 0 ? r.vx : r.vz);
				if (rel * sign <= 0) break;
				double d = BODY_MASS * rel / (r.mass() + BODY_MASS);
				r.bump(axis == 0 ? d : 0, axis == 1 ? d : 0);
				break;
			}
		}
	}

	/** Глубина {@link #withoutPush} на этом потоке (клиент и встроенный сервер — разные потоки). */
	private static final ThreadLocal<int[]> NO_PUSH = ThreadLocal.withInitial(() -> new int[1]);

	/** Сдвиг тела, который не его импульс (подтяжка верёвкой и т. п.): упёршись в плот, не толкает его. */
	public static void withoutPush(Runnable move) {
		int[] depth = NO_PUSH.get();
		depth[0]++;
		try {
			move.run();
		} finally {
			depth[0]--;
		}
	}

	/**
	 * Бросок (жерло): скорость задаётся целиком, а не добавкой, и несколько тиков вода её не гасит. Сервер рассылает её клиентам,
	 * клиент применяет пришедшую ({@code RaftFling}).
	 */
	public void fling(Vec3 v) {
		if (destroyed()) return;
		vx = v.x;
		vy = v.y;
		vz = v.z;
		flungTicks = FLING_TICKS;
		// брошенный уже не «только что загруженный»: иначе клиент первые тики жизни плота держал бы его без тяжести (loadHold)
		clientAge = Math.max(clientAge, LOAD_HOLD_TICKS);
		loadedFromSave = false;
		if (!level().isClientSide()) Rafts.broadcastFling(this, v);
	}

	/** Пересекают ли блоки плота коробку. */
	public boolean touches(AABB box) {
		List<VoxelShape> shapes = new ArrayList<>();
		collect(box, shapes);
		for (VoxelShape shape : shapes) for (AABB b : shape.toAabbs()) if (b.intersects(box)) return true;
		return false;
	}

	/** Скорость гребка сидящего ({@link #paddle}), блоков за тик: столько получило бы тело его массы, плот берёт долю по массам. */
	private static final double PADDLE = 0.3;

	/** Сидящий на плоту гребёт в сторону {@code dir} (по горизонтали): толчок с учётом масс, как от тела ({@link #bump}). */
	public void paddle(Vec3 dir) {
		double h = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
		if (h < 1.0e-6) return;
		double k = BODY_MASS * PADDLE / (mass() + BODY_MASS) / h;
		bump(dir.x * k, dir.z * k);
	}

	// ---------------------------------------------------------------- сиденья

	/** Пассажиры плота — только сиденья ({@link SeatEntity}). */
	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return passenger instanceof SeatEntity;
	}

	/** Сиденье стоит в своей точке палубы: плот тикает раньше пассажиров, поэтому сидящий едет без отставания. */
	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction move) {
		if (passenger instanceof SeatEntity seat) {
			Vec3 p = fromLocal(seat.local(), 1f);
			move.accept(passenger, p.x, p.y, p.z);
		} else super.positionRider(passenger, move);
	}

	/** Толчок, посчитанный на этой стороне: применить и передать остальным (клиент — серверу, сервер — всем, кто видит плот). */
	public void bump(double dx, double dz) {
		if (!exchangesImpulse() || destroyed()) return;
		applyPush(dx, dz);
		if (level().isClientSide()) clientSend.accept(new RaftPush(getId(), (float) dx, (float) dz));
		else Rafts.broadcastPush(this, dx, dz, null);
	}

	/** Добавить скорость от толчка (пришедшего пакетом или своего). */
	public void applyPush(double dx, double dz) {
		if (!exchangesImpulse() || destroyed()) return;
		vx += Math.clamp(dx, -MAX_PUSH, MAX_PUSH);
		vz += Math.clamp(dz, -MAX_PUSH, MAX_PUSH);
	}

	private static boolean intersects(List<AABB> boxes, AABB bb) {
		for (AABB b : boxes) if (b.intersects(bb)) return true;
		return false;
	}

	private List<AABB> boxes(double x, double y, double z) {
		List<VoxelShape> shapes = new ArrayList<>();
		collect(new AABB(x - sx() / 2.0 - 1, y - 1, z - sz() / 2.0 - 1, x + sx() / 2.0 + 1, y + sy() + 1, z + sz() / 2.0 + 1), x, y, z, shapes);
		List<AABB> out = new ArrayList<>();
		for (VoxelShape s : shapes) out.addAll(s.toAabbs());
		return out;
	}

	// ---------------------------------------------------------------- столкновения

	/** Формы блоков плота, попадающие в ящик query (в мировых координатах, для текущей позиции). */
	public void collect(AABB query, List<VoxelShape> out) {
		if (destroyed()) return;
		collect(query, getX(), getY(), getZ(), out);
	}

	private void collect(AABB query, double x, double y, double z, List<VoxelShape> out) {
		int sx = sx(), sy = sy(), sz = sz();
		double ox = x - sx / 2.0, oz = z - sz / 2.0;
		int x0 = Math.max(0, (int) Math.floor(query.minX - ox)), x1 = Math.min(sx - 1, (int) Math.floor(query.maxX - ox));
		int y0 = Math.max(0, (int) Math.floor(query.minY - y)), y1 = Math.min(sy - 1, (int) Math.floor(query.maxY - y));
		int z0 = Math.max(0, (int) Math.floor(query.minZ - oz)), z1 = Math.min(sz - 1, (int) Math.floor(query.maxZ - oz));
		for (int iy = y0; iy <= y1; iy++) {
			for (int iz = z0; iz <= z1; iz++) {
				for (int ix = x0; ix <= x1; ix++) {
					BlockState s = cellAt(ix, iy, iz);
					if (s.isAir()) continue;
					VoxelShape shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
					if (!shape.isEmpty()) out.add(shape.move(ox + ix, y + iy, oz + iz));
				}
			}
		}
	}

	/** Ближайшее попадание луча в блоки плота: расстояние от начала луча или NaN. */
	public double rayDistance(Vec3 from, Vec3 to) {
		List<VoxelShape> shapes = new ArrayList<>();
		collect(new AABB(from, to).inflate(1.0), shapes);
		double best = Double.NaN;
		for (VoxelShape s : shapes) {
			var hit = s.clip(from, to, BlockPos.ZERO);
			if (hit == null) continue;
			double d = hit.getLocation().distanceTo(from);
			if (Double.isNaN(best) || d < best) best = d;
		}
		return best;
	}

	/** Стоит ли тело над блоками какого-нибудь плота этого мира (с запасом вниз). */
	public static boolean standsOnRaft(Entity e) {
		if (ALL.isEmpty()) return false;
		AABB box = e.getBoundingBox().inflate(0.0625).expandTowards(0, -1.0, 0);
		for (RaftEntity r : ALL) {
			if (r.level() != e.level() || r.isRemoved()) continue;
			List<VoxelShape> shapes = new ArrayList<>();
			r.collect(box, shapes);
			if (!shapes.isEmpty()) return true;
		}
		return false;
	}

	/** Плот или шар под ногами тела (на нём или в полублока над ним), {@code null} — нет. */
	public static @org.jetbrains.annotations.Nullable RaftEntity under(Entity e) {
		AABB box = e.getBoundingBox().inflate(0.0625).expandTowards(0, -0.5, 0);
		for (RaftEntity r : ALL) {
			if (r.level() != e.level() || r.isRemoved() || r.destroyed()) continue;
			List<VoxelShape> shapes = new ArrayList<>();
			r.collect(box, shapes);
			if (!shapes.isEmpty()) return r;
		}
		return null;
	}

	/** Стоит ли тело именно на палубе плота или шара, а не рядом с их боком и не над ними. */
	public static boolean onDeck(Entity e) {
		if (ALL.isEmpty() || !e.onGround()) return false;
		AABB bb = e.getBoundingBox();
		AABB feet = new AABB(bb.minX, bb.minY - 0.06, bb.minZ, bb.maxX, bb.minY - 1.0e-6, bb.maxZ);
		for (RaftEntity r : ALL) {
			if (r.level() != e.level() || r.isRemoved()) continue;
			List<VoxelShape> shapes = new ArrayList<>();
			r.collect(feet, shapes);
			for (VoxelShape shape : shapes) for (AABB box : shape.toAabbs()) if (box.intersects(feet)) return true;
		}
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
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	// ---------------------------------------------------------------- сохранение

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putInt("SX", sx());
		out.putInt("SY", sy());
		out.putInt("SZ", sz());
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> index = new HashMap<>();
		int[] ids = new int[cells.length];
		for (int i = 0; i < cells.length; i++) {
			BlockState s = cells[i] == null ? Blocks.AIR.defaultBlockState() : cells[i];
			Integer at = index.get(s);
			if (at == null) {
				at = palette.size();
				palette.add(s);
				index.put(s, at);
			}
			ids[i] = at;
		}
		out.store("Palette", BlockState.CODEC.listOf(), palette);
		out.putIntArray("Cells", ids);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		int sx = in.getIntOr("SX", 1), sy = in.getIntOr("SY", 1), sz = in.getIntOr("SZ", 1);
		List<BlockState> palette = in.read("Palette", BlockState.CODEC.listOf()).orElse(List.of());
		int[] ids = in.getIntArray("Cells").orElse(new int[0]);
		BlockState[] loaded = new BlockState[ids.length];
		for (int i = 0; i < ids.length; i++) loaded[i] = ids[i] >= 0 && ids[i] < palette.size() ? palette.get(ids[i]) : Blocks.AIR.defaultBlockState();
		setCells(sx, sy, sz, loaded);
		this.started = false;
		this.loadedFromSave = true;
	}
}
