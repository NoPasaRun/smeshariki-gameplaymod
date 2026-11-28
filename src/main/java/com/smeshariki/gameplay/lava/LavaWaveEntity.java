package com.smeshariki.gameplay.lava;

import com.smeshariki.gameplay.barrage.FireMarkerEntity;
import com.smeshariki.gameplay.character.CharacterEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Волна лавы, воды или снега ({@link WaveKind}): вид задаёт блок под маркером (предметы трёх маркеров ставят одну и ту же сущность, предмет — только вид
 * по умолчанию, если под маркером нет ни одного из материалов). Волна идёт по верху блоков своего вида и следует им вверх и вниз: каждый шаг пути
 * ищет верх материала не выше {@link #CLIMB} и не ниже {@link #DROP} от прежнего ({@link #column}), поэтому вода и лава текут каскадами и водопадами,
 * а лавина — по снежному склону; где материала нет (камень, берег), волны нет. Вода несёт тела по курсу, снег несёт и слегка ранит, лава поджигает.
 * Обрушение у жидкостей поднимает озеро под маркером на {@code Rise} слоёв, у снега кладёт {@code Rise} слоёв снега там, где был гребень.
 * <p>
 * Сущность по-прежнему {@code gameplaymod:lava_wave} (лежит в сохранённых мирах), старые маркеры без поля {@code Kind} — лава. Дальше про лаву;
 * у воды и снега то же самое.
 * <p>
 * Маркер у берега лавового озера. Маркер с группой {@code Group} запускает волну только по команде {@link LavaWaves#launch} (триггер LevelMod), а когда
 * она кончилась (обрушилась и слой лавы залит), сообщает {@link LavaWaves#FINISHED}: так уровень сам чередует волны с разных берегов. Маркер без группы волн не пускает
 * (с 2026-11-20; прежде пускал сам раз в {@code Interval} тиков) — сначала задать группу. От маркера по курсу {@code Yaw} идёт волна высотой {@code Height} со скоростью {@code Speed} блоков/с, на всю ширину озера (не дальше
 * {@code HalfWidth} в стороны, стены пещеры её обрезают). Пройдя {@code Length} блоков, она обрушивается и поднимает лаву озера на {@code Rise} слоёв (0 — не поднимает), не выше {@code PeakY}.
 * Внутри волны тело горит, как в лаве.
 * <p>
 * Про то, что на ней плавает, волна не знает: она только поверхность ({@link #swellAt}) — насколько лава поднята над уровнем и куда её склоны увлекают
 * плавающее (с гребнем вперёд и к гребню). Плоты спрашивают это сами, как поле течений.
 * <p>
 * Волна — не блоки: её форму считают сервер (урон) и клиент (рисунок) по времени начала {@code START} и уровню лавы {@code BASE}, которые идут в данных сущности.
 * С маркером в руке: ПКМ — курс +30°, Ctrl+ПКМ и Ctrl+ЛКМ — путь ±2, ЛКМ — убрать. Остальное через {@code /data}.
 */
public class LavaWaveEntity extends FireMarkerEntity {
	public static final float TURN = 30f;
	/** Верх лавы-источника над низом клетки. */
	public static final double LAVA_TOP = WaveKind.FLUID_TOP;
	/** Насколько выше и ниже прежнего верха материала волна ищет его на следующем блоке пути: подъём и спуск (водопад, склон) за блок. */
	public static final int CLIMB = 2, DROP = 6;
	/** Поперёк курса поиск шире на столько блоков высоты за блок от середины: склон может идти и вбок. */
	private static final double SIDE_SLOPE = 0.5;
	/** Через столько тиков путь и столбцы пересчитываются: под волной могли поставить или сломать блок, у клиента — догрузиться чанки. */
	private static final int COLUMN_REFRESH = 40;
	/** Вода и снег несут тело: доля разницы скорости вдоль курса за тик; подъём к верху волны (доля высоты за тик и предел). */
	private static final double CARRY_GRIP = 0.25, CARRY_LIFT = 0.3, CARRY_LIFT_MAX = 0.35;
	/** Лавина ранит раз в столько тиков на столько урона. */
	private static final int SNOW_HURT_EVERY = 20;
	private static final float SNOW_DAMAGE = 1f;
	/** Сколько тиков гребень поднимается в начале и опадает при обрушении. */
	public static final int RISE_TICKS = 20, CRASH_TICKS = 12;
	private static final float MIN_LENGTH = 2f, MAX_LENGTH = 128f, LENGTH_STEP = 2f;
	/** Насколько выше лавы ищем, где начинается её поверхность от маркера. */
	private static final int SEARCH_UP = 8, SEARCH_DOWN = 24;

	private static final EntityDataAccessor<Float> YAW = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> LENGTH = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HALF_WIDTH = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.FLOAT);
	/** Время мира в начале текущей волны, −1 — волны не было. */
	private static final EntityDataAccessor<Integer> START = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.INT);
	/** Верхняя клетка лавы в начале волны. */
	private static final EntityDataAccessor<Integer> BASE = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.INT);
	/** Подъём за обрушение и предел лавы — копии полей {@code rise} и {@code peakY} для показа клиенту (маркер в руке). */
	private static final EntityDataAccessor<Integer> RISE = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> PEAK = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.INT);
	/** Вид волны ({@link WaveKind#ordinal}). */
	private static final EntityDataAccessor<Integer> KIND = SynchedEntityData.defineId(LavaWaveEntity.class, EntityDataSerializers.INT);
	/** Наибольший подъём за одно обрушение (слоёв); 0 — волна лаву не поднимает. */
	public static final int MAX_RISE = 16;

	private int peakY = Integer.MIN_VALUE;
	/** Живые волны обеих сторон: их спрашивает плавающее на лаве ({@link #swellAt}). */
	public static final List<LavaWaveEntity> ALL = new CopyOnWriteArrayList<>();
	/** Склон тянет плавающее к гребню: доля расстояния до него за тик и предел, блоков за тик. */
	private static final double PULL = 0.12, MAX_PULL = 0.25;

	private boolean crashed;
	/** Запуск заказан командой, но волна ещё не пошла: идёт своя волна или заливается слой. */
	private boolean launchRequested;
	/** Волна пошла, а о её конце ещё не сообщили. */
	private boolean finishPending;
	/**
	 * Волна возвращена из снимка уровня посреди хода и ждёт, пока игрок отстоит ожидание после сброса ({@link #release}): пока ждёт, не идёт, не сообщает о конце и
	 * не запускает другую. В сохранение мира не пишется. {@code heldElapsed} — сколько тиков волна уже шла (−1 — не шла), {@code heldSince} — когда её придержали.
	 */
	private boolean held;
	private int heldElapsed = -1;
	private long heldSince;
	/** Придержанная волна сама отпускается через столько тиков, если уровень её не отпустил: волна не должна зависнуть навсегда. */
	private static final int HOLD_LIMIT = 1200;
	private final LavaLayer layer = new LavaLayer();
	/** Сколько слоёв поднимает одно обрушение ({@code Rise}, 0–{@link #MAX_RISE}) и сколько ещё залить после текущего. */
	private int rise = 1, riseLeft = 0;

	public LavaWaveEntity(EntityType<? extends LavaWaveEntity> type, Level level) {
		super(type, level);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(YAW, 0f);
		builder.define(LENGTH, 32f);
		builder.define(HEIGHT, 3f);
		builder.define(SPEED, 4f);
		builder.define(HALF_WIDTH, 16f);
		builder.define(START, -1);
		builder.define(BASE, 0);
		builder.define(RISE, 1);
		builder.define(PEAK, Integer.MIN_VALUE);
		builder.define(KIND, WaveKind.LAVA.ordinal());
	}

	// ---------------------------------------------------------------- параметры

	public WaveKind kind() {
		int i = this.entityData.get(KIND);
		WaveKind[] all = WaveKind.values();
		return i >= 0 && i < all.length ? all[i] : WaveKind.LAVA;
	}

	public void setKind(WaveKind kind) {
		this.entityData.set(KIND, kind.ordinal());
	}

	public float yaw() {
		return this.entityData.get(YAW);
	}

	public float length() {
		return this.entityData.get(LENGTH);
	}

	public float height() {
		return this.entityData.get(HEIGHT);
	}

	/** Блоков за тик. */
	public double speedPerTick() {
		return this.entityData.get(SPEED) / 20.0;
	}

	public float halfWidth() {
		return this.entityData.get(HALF_WIDTH);
	}

	/** Полуширина волны поперёк курса (колесо мыши с маркером, {@link LavaWaveScroll}). */
	public void setHalfWidth(float half) {
		this.entityData.set(HALF_WIDTH, Math.clamp(half, 0.5f, 64f));
	}

	public int base() {
		return this.entityData.get(BASE);
	}

	public int start() {
		return this.entityData.get(START);
	}

	/** Высота поверхности материала под маркером в начале волны (у снега — верх клетки). */
	public double surfaceY() {
		return base() + (kind().fluid() ? LAVA_TOP : 1.0);
	}

	/** Куда идёт волна. */
	public Vec3 course() {
		double r = Math.toRadians(yaw());
		return new Vec3(-Math.sin(r), 0, Math.cos(r));
	}

	/** Поперёк курса, вправо от него. */
	public Vec3 side() {
		Vec3 c = course();
		return new Vec3(-c.z, 0, c.x);
	}

	public float peakY() {
		return this.peakY;
	}

	/** Предел лавы и подъём за обрушение, как их видит клиент. */
	public int shownPeak() {
		return this.entityData.get(PEAK);
	}

	public int shownRise() {
		return this.entityData.get(RISE);
	}

	/** Верхняя клетка материала сейчас (маркер стоит над ней). */
	public int lavaTop() {
		return getBlockY() - 1;
	}

	/** Где встанет верх лавы после обрушения: на {@code Rise} слоёв выше, не выше предела. */
	public int lavaAfterCrash() {
		int peak = shownPeak();
		int top = lavaTop() + shownRise();
		return peak == Integer.MIN_VALUE ? top : Math.min(top, Math.max(peak, lavaTop()));
	}

	private void setPeak(int peak) {
		this.peakY = peak;
		this.entityData.set(PEAK, peak);
	}

	private void setRise(int rise) {
		this.rise = Math.clamp(rise, 0, MAX_RISE);
		this.entityData.set(RISE, this.rise);
	}

	// ---------------------------------------------------------------- форма волны

	/** Сколько тиков гребень идёт до противоположного берега. */
	public int travelTicks() {
		return Math.max(1, Mth.ceil(length() / Math.max(1.0e-3, speedPerTick())));
	}

	/** Тиков от начала волны, или −1, если её нет. */
	public double waveTime(float partialTick) {
		int start = start();
		if (start < 0) return -1;
		return (int) level().getGameTime() - start + partialTick;
	}

	public boolean running(double t) {
		return t >= 0 && t < travelTicks() + CRASH_TICKS;
	}

	/** Где гребень: путь от маркера по курсу. */
	public double crest(double t) {
		return Math.min(t, travelTicks()) * speedPerTick();
	}

	/** Насколько гребень поднялся из лавы в начале, от 0 до 1. */
	public double rise(double t) {
		double x = Math.clamp(t / RISE_TICKS, 0, 1);
		return x * x * (3 - 2 * x);
	}

	/** Высота гребня долей от {@code Height}: растёт в начале, опадает при обрушении. */
	public double amplitude(double t) {
		double crash = t > travelTicks() ? Math.clamp(1 - (t - travelTicks()) / CRASH_TICKS, 0, 1) : 1;
		return rise(t) * crash;
	}

	/** Длина склона волны перед гребнем и за ним: фронт круче. */
	public double front() {
		return 1 + 0.6 * height();
	}

	public double back() {
		return 2 + 1.4 * height();
	}

	/** Высота волны над лавой на расстоянии d от гребня по курсу (вперёд положительно). */
	public double heightAt(double d, double amplitude) {
		double half = d >= 0 ? front() : back();
		if (Math.abs(d) >= half) return 0;
		double c = Math.cos(Math.PI / 2 * d / half);
		return height() * amplitude * c * c;
	}

	/** Путь по курсу и сдвиг поперёк точки мира от маркера. */
	public double along(double x, double z) {
		Vec3 c = course();
		return (x - getX()) * c.x + (z - getZ()) * c.z;
	}

	public double across(double x, double z) {
		Vec3 s = side();
		return (x - getX()) * s.x + (z - getZ()) * s.z;
	}

	// ---------------------------------------------------------------- рельеф

	/** Столбец волны: высота поверхности материала и сколько над ней места до первого твёрдого блока. {@link #NONE} — материала нет. */
	public record Column(double surface, double room) {
		public boolean exists() {
			return !Double.isNaN(this.surface);
		}
	}

	public static final Column NONE = new Column(Double.NaN, Double.NaN);

	/** Верхние клетки материала по средней линии пути: индекс — блоков от маркера по курсу. */
	private int[] profile;
	private final Map<Long, Column> columns = new HashMap<>();
	private long columnsKey = Long.MIN_VALUE;
	private int columnsMade;

	/** Путь и столбцы строятся заново, когда меняется волна (начало, уровень, курс, путь, вид, ширина) и раз в {@link #COLUMN_REFRESH} тиков. */
	private void freshColumns() {
		long key = start();
		key = key * 31 + base();
		key = key * 31 + Float.floatToIntBits(yaw());
		key = key * 31 + Float.floatToIntBits(length());
		key = key * 31 + Float.floatToIntBits(halfWidth());
		key = key * 31 + kind().ordinal();
		if (this.profile != null && key == this.columnsKey && this.tickCount - this.columnsMade <= COLUMN_REFRESH && this.tickCount >= this.columnsMade) return;
		this.columnsKey = key;
		this.columnsMade = this.tickCount;
		this.columns.clear();
		WaveKind kind = kind();
		int n = Mth.ceil(length() + front()) + 2;
		int[] p = new int[n + 1];
		int y = base();
		p[0] = y;
		Vec3 c = course();
		for (int i = 1; i <= n; i++) {
			int found = topNear(kind, Mth.floor(getX() + c.x * i), Mth.floor(getZ() + c.z * i), y, CLIMB, DROP);
			// камень посреди озера волну не останавливает: дальше ищем от прежней высоты
			if (found != Integer.MIN_VALUE) y = found;
			p[i] = y;
		}
		this.profile = p;
	}

	/** Верх материала по средней линии на пути {@code along} от маркера. */
	private int nominal(double along) {
		freshColumns();
		return this.profile[(int) Math.clamp(Math.round(along), 0, this.profile.length - 1)];
	}

	/** Клетка — верх материала: сама его вида, над ней не он и не твёрдый блок. */
	private boolean isTop(WaveKind kind, BlockPos.MutableBlockPos at, int x, int y, int z) {
		Level level = level();
		if (!kind.matches(level.getBlockState(at.set(x, y, z)))) return false;
		BlockState above = level.getBlockState(at.set(x, y + 1, z));
		return !kind.matches(above) && above.getCollisionShape(level, at).isEmpty();
	}

	/** Верхняя клетка материала в столбце, ближайшая к {@code y}: не выше {@code up} и не ниже {@code down}; MIN_VALUE — нет. */
	private int topNear(WaveKind kind, int x, int z, int y, int up, int down) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int d = 0; d <= Math.max(up, down); d++) {
			if (d <= up && isTop(kind, at, x, y + d, z)) return y + d;
			if (d > 0 && d <= down && isTop(kind, at, x, y - d, z)) return y - d;
		}
		return Integer.MIN_VALUE;
	}

	/** Поверхность материала у столбца (x, z) и место над ней; {@link #NONE}, если волне тут идти не по чему (берег, камень, другой материал). */
	public Column column(int x, int z) {
		freshColumns();
		long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
		Column c = this.columns.get(key);
		if (c == null) {
			c = computeColumn(x, z);
			this.columns.put(key, c);
		}
		return c;
	}

	private Column computeColumn(int x, int z) {
		WaveKind kind = kind();
		double cx = x + 0.5, cz = z + 0.5;
		int slack = Mth.ceil(Math.abs(across(cx, cz)) * SIDE_SLOPE);
		int y = topNear(kind, x, z, nominal(along(cx, cz)), CLIMB + slack, DROP + slack);
		if (y == Integer.MIN_VALUE) return NONE;
		Level level = level();
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(x, y, z);
		double surface = kind.surface(level, at, level.getBlockState(at));
		int top = y + 2 + Mth.ceil(height());
		for (int yy = y + 1; yy <= top; yy++) {
			at.setY(yy);
			if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return new Column(surface, yy - surface);
		}
		return new Column(surface, Double.POSITIVE_INFINITY);
	}

	/**
	 * Сколько места над материалом у столбца (x, z): NaN, если волне тут не идти, иначе высота до первого блока над ним, чтобы волна не проходила сквозь
	 * выступы и площадки.
	 */
	public double columnRoom(int x, int z) {
		Column c = column(x, z);
		return c.exists() ? c.room() : Double.NaN;
	}

	/** Верх волны над точкой мира в момент t; NaN, где волны нет. */
	public double topAt(double x, double z, double t) {
		if (Math.abs(across(x, z)) > halfWidth()) return Double.NaN;
		double h = heightAt(along(x, z) - crest(t), amplitude(t));
		if (h <= 0) return Double.NaN;
		Column c = column(Mth.floor(x), Mth.floor(z));
		if (!c.exists()) return Double.NaN;
		return c.surface() + Math.min(h, c.room());
	}

	/** Насколько волна поднимает материал над его поверхностью в точке мира в момент t (0, где волны нет). */
	public double liftAt(double x, double z, double t) {
		double top = topAt(x, z, t);
		return Double.isNaN(top) ? 0 : top - column(Mth.floor(x), Mth.floor(z)).surface();
	}

	/** Поверхность всех волн мира в точке: подъём над уровнем лавы и куда склон увлекает плавающее, блоков за тик. */
	/** {@code crash} — волна над точкой обрушивается: плоты под ней уходят под лаву ({@code RaftEntity}). */
	public record Swell(double lift, double vx, double vz, boolean crash) {
		public static final Swell NONE = new Swell(0, 0, 0, false);
	}

	public static Swell swellAt(Level level, double x, double z) {
		if (ALL.isEmpty()) return Swell.NONE;
		double lift = 0, vx = 0, vz = 0;
		boolean crash = false;
		for (LavaWaveEntity w : ALL) {
			// по снегу плоты не плавают
			if (w.level() != level || w.isRemoved() || w.kind() == WaveKind.SNOW) continue;
			double t = w.waveTime(0);
			if (!w.running(t)) continue;
			double h = w.liftAt(x, z, t);
			if (h <= 0 || h <= lift) continue;
			lift = h;
			crash = t >= w.travelTicks();
			// пока гребень идёт, склон несёт с ним и тянет к нему; при обрушении никуда не несёт
			double v = 0;
			if (t < w.travelTicks()) v = w.speedPerTick() + Math.clamp((w.crest(t) - w.along(x, z)) * PULL, -MAX_PULL, MAX_PULL);
			Vec3 c = w.course();
			vx = c.x * v;
			vz = c.z * v;
		}
		return lift <= 0 ? Swell.NONE : new Swell(lift, vx, vz, crash);
	}

	/** Ящик, в котором может быть волна в момент t. */
	public AABB band(double t) {
		Vec3 c = course(), s = side();
		double crest = crest(t), w = halfWidth();
		double a0 = crest - back(), a1 = crest + front();
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (double a : new double[] { a0, a1 }) for (double u : new double[] { -w, w }) {
			double x = getX() + c.x * a + s.x * u, z = getZ() + c.z * a + s.z * u;
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		freshColumns();
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int i = (int) Math.clamp((long) Math.floor(a0), 0, this.profile.length - 1); i <= (int) Math.clamp((long) Math.ceil(a1), 0, this.profile.length - 1); i++) {
			lo = Math.min(lo, this.profile[i]);
			hi = Math.max(hi, this.profile[i]);
		}
		double slack = w * SIDE_SLOPE;
		return new AABB(minX, lo - DROP - slack - 1, minZ, maxX, hi + CLIMB + slack + height() + 2, maxZ);
	}

	// ---------------------------------------------------------------- ход

	@Override
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel server) serverTick(server);
		else clientTick();
	}

	private void clientTick() {
		double t = waveTime(0);
		if (!running(t)) return;
		Vec3 c = course(), s = side();
		for (int i = 0; i < 2; i++) {
			double u = (this.random.nextDouble() * 2 - 1) * halfWidth(), a = crest(t) + (this.random.nextDouble() - 0.5) * 2;
			double x = getX() + c.x * a + s.x * u, z = getZ() + c.z * a + s.z * u;
			double top = topAt(x, z, t);
			if (Double.isNaN(top)) continue;
			switch (kind()) {
				case LAVA -> level().addParticle(ParticleTypes.LAVA, x, top, z, 0, 0, 0);
				case WATER -> level().addParticle(ParticleTypes.SPLASH, x, top, z, c.x * 0.2, 0.1, c.z * 0.2);
				case SNOW -> level().addParticle(ParticleTypes.SNOWFLAKE, x, top, z, c.x * speedPerTick() * 0.5, 0.05, c.z * speedPerTick() * 0.5);
			}
		}
	}

	private void serverTick(ServerLevel level) {
		if (this.held) {
			if (level.getGameTime() - this.heldSince > HOLD_LIMIT) release(level);
			return;
		}
		if (this.layer.busy()) this.layer.pour(level);
		// обрушение поднимает несколько слоёв: следующий, когда залит предыдущий
		if (!this.layer.busy() && this.riseLeft > 0) {
			int y = findSurface(kind(), getBlockX(), getBlockY() + this.rise, getBlockZ());
			if (kind().fluid() && y != Integer.MIN_VALUE && y < this.peakY && this.layer.plan(level, getBlockX(), y + 1, getBlockZ(), true, kind(), "Волна у " + blockPosition())) this.riseLeft--;
			else this.riseLeft = 0;
		}
		long now = level.getGameTime();
		double t = waveTime(0);
		if (running(t)) {
			if (!this.crashed && t >= travelTicks()) crash(level, t);
			affect(level, t);
			return;
		}
		// слой обрушения ещё заливается: следующая волна пошла бы от старого уровня
		if (this.layer.busy() || this.riseLeft > 0) return;
		if (this.finishPending) {
			this.finishPending = false;
			LavaWaves.FINISHED.invoker().finished(this, group());
		}
		if (this.launchRequested) {
			this.launchRequested = false;
			begin(level, now);
			return;
		}
		// волна идёт только по заказу (триггер уровня, LavaWaves.launch): сам маркер, даже без группы, ничего не пускает (2026-11-20, по просьбе)

	}

	/**
	 * Верхняя клетка лавы в столбце маркера или MIN_VALUE, если лавы нет. Ищет и до {@code PeakY}: маркер поздней волны ждёт внизу, пока другие
	 * волны поднимают лаву на десятки блоков (прежде окно в 8 блоков вверх не находило её, и цепочка волн вставала).
	 */
	private int findSurface(WaveKind kind, int x, int y, int z) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		int top = y + SEARCH_UP;
		if (this.peakY != Integer.MIN_VALUE) top = Math.max(top, this.peakY + 1);
		for (int yy = top; yy >= y - SEARCH_DOWN; yy--) {
			if (kind.matches(level().getBlockState(at.set(x, yy, z))) && !kind.matches(level().getBlockState(at.set(x, yy + 1, z)))) return yy;
		}
		return Integer.MIN_VALUE;
	}

	/**
	 * Вид волны по блоку под маркером в столбце (x, y, z): сначала клетка прямо под {@code y}, потом сверху вниз первый верх любого материала. null — ни
	 * одного материала.
	 */
	private WaveKind detect(int x, int y, int z) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		WaveKind under = WaveKind.of(level().getBlockState(at.set(x, y - 1, z)));
		if (under != null) return under;
		for (int yy = y + SEARCH_UP; yy >= y - SEARCH_DOWN; yy--) {
			WaveKind k = WaveKind.of(level().getBlockState(at.set(x, yy, z)));
			if (k != null && !k.matches(level().getBlockState(at.set(x, yy + 1, z)))) return k;
		}
		return null;
	}

	private void begin(ServerLevel level, long now) {
		// вид волны — по блоку под маркером: его могли заменить с прошлой волны
		WaveKind under = WaveKind.of(level.getBlockState(blockPosition().below()));
		if (under != null && under != kind()) setKind(under);
		int surface = findSurface(kind(), getBlockX(), getBlockY(), getBlockZ());
		if (surface == Integer.MIN_VALUE) {
			WaveKind found = detect(getBlockX(), getBlockY(), getBlockZ());
			if (found == null) return;
			setKind(found);
			surface = findSurface(found, getBlockX(), getBlockY(), getBlockZ());
			if (surface == Integer.MIN_VALUE) return;
		}
		// поставлен через /summon без PeakY
		if (this.peakY == Integer.MIN_VALUE) setPeak(surface + 8);
		this.entityData.set(BASE, surface);
		// лава поднялась: маркер держится у её поверхности, иначе поиск ушёл бы из окна
		if (getBlockY() != surface + 1) setPos(getX(), surface + 1, getZ());
		this.entityData.set(START, (int) now);
		this.crashed = false;
		this.finishPending = true;
		switch (kind()) {
			case LAVA -> level.playSound(null, getX(), surfaceY(), getZ(), SoundEvents.LAVA_POP, SoundSource.BLOCKS, 2f, 0.6f);
			case WATER -> level.playSound(null, getX(), surfaceY(), getZ(), SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 2f, 0.6f);
			case SNOW -> level.playSound(null, getX(), surfaceY(), getZ(), SoundEvents.POWDER_SNOW_BREAK, SoundSource.BLOCKS, 2f, 0.5f);
		}
	}

	/** Гребень дошёл до конца пути: брызги, звук и слой (жидкость озера или снег там, где был гребень). */
	private void crash(ServerLevel level, double t) {
		this.crashed = true;
		WaveKind kind = kind();
		Vec3 c = course(), s = side();
		double a = crest(t);
		double midY = surfaceY();
		for (double u = -halfWidth(); u <= halfWidth(); u += 1.5) {
			double x = getX() + c.x * a + s.x * u, z = getZ() + c.z * a + s.z * u;
			Column col = column(Mth.floor(x), Mth.floor(z));
			if (!col.exists()) continue;
			double y = col.surface();
			if (Math.abs(u) < 1.5) midY = y;
			switch (kind) {
				case LAVA -> {
					level.sendParticles(ParticleTypes.LAVA, x, y + height() * 0.5, z, 6, 0.6, 0.4, 0.6, 0.1);
					level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + height(), z, 2, 0.4, 0.3, 0.4, 0.02);
				}
				case WATER -> {
					level.sendParticles(ParticleTypes.SPLASH, x, y + height() * 0.5, z, 12, 0.6, 0.4, 0.6, 0.2);
					level.sendParticles(ParticleTypes.CLOUD, x, y + height() * 0.6, z, 1, 0.4, 0.3, 0.4, 0.02);
				}
				case SNOW -> {
					level.sendParticles(ParticleTypes.SNOWFLAKE, x, y + height() * 0.5, z, 10, 0.7, 0.5, 0.7, 0.08);
					level.sendParticles(ParticleTypes.POOF, x, y + height() * 0.5, z, 2, 0.5, 0.4, 0.5, 0.03);
				}
			}
		}
		Vec3 mid = new Vec3(getX() + c.x * a, midY, getZ() + c.z * a);
		switch (kind) {
			case LAVA -> {
				level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 3f, 0.5f);
				level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 2f, 0.6f);
			}
			case WATER -> {
				level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 3f, 0.6f);
				level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 2f, 0.5f);
			}
			case SNOW -> {
				level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.SNOW_BREAK, SoundSource.BLOCKS, 3f, 0.5f);
				level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.POWDER_SNOW_BREAK, SoundSource.BLOCKS, 3f, 0.6f);
			}
		}
		if (this.rise <= 0 || this.layer.busy()) return;
		if (kind.fluid()) {
			if (base() < this.peakY && this.layer.plan(level, getBlockX(), base() + 1, getBlockZ(), true, kind, "Волна у " + blockPosition()))
				this.riseLeft = Math.min(this.rise, this.peakY - base()) - 1;
		} else depositSnow(level, a);
	}

	/**
	 * Лавина легла: {@code Rise} слоёв снега на каждый столбец, где гребень был выше четверти высоты (слои снега дополняются до полного, лишнее — слоями
	 * выше). Ставит понемногу за тик, как слой озера. Предел {@code PeakY} у снега не действует.
	 */
	private void depositSnow(ServerLevel level, double crest) {
		List<BlockPos> cells = new java.util.ArrayList<>();
		List<BlockState> blocks = new java.util.ArrayList<>();
		AABB box = band(travelTicks());
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
				double cx = x + 0.5, cz = z + 0.5;
				if (Math.abs(across(cx, cz)) > halfWidth() || heightAt(along(cx, cz) - crest, 1) < height() * 0.25) continue;
				Column col = column(x, z);
				if (!col.exists()) continue;
				BlockPos top = new BlockPos(x, Mth.floor(col.surface() - 1.0e-6), z);
				BlockState state = level.getBlockState(top);
				int add = this.rise;
				if (state.is(Blocks.SNOW)) {
					int total = state.getValue(SnowLayerBlock.LAYERS) + add;
					cells.add(top);
					blocks.add(snow(Math.min(8, total)));
					add = total - 8;
				}
				for (BlockPos up = top.above(); add > 0; up = up.above(), add -= 8) {
					BlockState there = level.getBlockState(up);
					if (!there.isAir() && !there.canBeReplaced()) break;
					cells.add(up);
					blocks.add(snow(Math.min(8, add)));
				}
			}
		}
		if (!cells.isEmpty()) this.layer.planCells(cells, blocks);
	}

	private static BlockState snow(int layers) {
		return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, Math.clamp(layers, 1, 8));
	}

	/**
	 * Что волна делает с телами внутри неё. Лава: горит, как в лаве. Вода и снег: несут по курсу к гребню и поднимают на верх волны (тело, которым
	 * управляет клиент игрока, несёт его клиент — {@link #carryBody}), падение не копится; снег ещё ранит. Вселённого бьёт только его персонаж, игрока в
	 * выживании без персонажа (он в окне выбора) волна не трогает.
	 */
	private void affect(ServerLevel level, double t) {
		WaveKind kind = kind();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, band(t))) {
			if (e instanceof ServerPlayer player && (player.isPassenger() || player.gameMode() == GameType.SURVIVAL)) continue;
			if (e.isSpectator() || e.isRemoved()) continue;
			double top = topAt(e.getX(), e.getZ(), t);
			if (Double.isNaN(top) || e.getBoundingBox().minY >= top - 0.05) continue;
			if (e.getY() < column(Mth.floor(e.getX()), Mth.floor(e.getZ())).surface() - 1.5) continue;
			if (kind == WaveKind.LAVA) {
				e.lavaIgnite();
				e.lavaHurt();
				continue;
			}
			if (!(e instanceof Player) && !(e.getControllingPassenger() instanceof Player)) carry(e, t, top);
			e.resetFallDistance();
			if (kind == WaveKind.SNOW && (this.tickCount + e.getId()) % SNOW_HURT_EVERY == 0) e.hurtServer(level, level.damageSources().freeze(), SNOW_DAMAGE);
		}
	}

	/** Несёт тело волной: вдоль курса к скорости гребня и тяге к нему (пока гребень идёт), вверх — к верху волны {@code top}. */
	private void carry(Entity body, double t, double top) {
		Vec3 c = course(), v = body.getDeltaMovement();
		double dv = 0;
		if (t < travelTicks()) {
			double target = speedPerTick() + Math.clamp((crest(t) - along(body.getX(), body.getZ())) * PULL, -MAX_PULL, MAX_PULL);
			dv = (target - (v.x * c.x + v.z * c.z)) * CARRY_GRIP;
		}
		double vy = v.y, up = top - body.getY();
		if (up > 0) vy = Math.max(vy, Math.min(CARRY_LIFT_MAX, up * CARRY_LIFT));
		body.setDeltaMovement(v.x + c.x * dv, vy, v.z + c.z * dv);
	}

	/**
	 * Своё тело игрока (он сам или его персонаж) несут волны воды и снега его мира: его движение считает клиент, поэтому зовёт клиент каждый тик. Берётся
	 * волна с самым высоким верхом над телом.
	 */
	public static void carryBody(Level level, Entity body) {
		LavaWaveEntity best = null;
		double bestTop = Double.NaN, bestT = 0;
		for (LavaWaveEntity w : ALL) {
			if (w.level() != level || w.isRemoved() || w.kind() == WaveKind.LAVA) continue;
			double t = w.waveTime(0);
			if (!w.running(t)) continue;
			double top = w.topAt(body.getX(), body.getZ(), t);
			if (Double.isNaN(top) || body.getBoundingBox().minY >= top - 0.05) continue;
			if (body.getY() < w.column(Mth.floor(body.getX()), Mth.floor(body.getZ())).surface() - 1.5) continue;
			if (best == null || top > bestTop) {
				best = w;
				bestTop = top;
				bestT = t;
			}
		}
		if (best == null) return;
		best.carry(body, bestT, bestTop);
		body.resetFallDistance();
	}

	// ---------------------------------------------------------------- маркер

	/** Придержана ли волна после возврата из снимка. */
	public boolean isHeld() {
		return this.held;
	}

	/** Отпустить придержанную волну: она идёт с того места, где была в снимке. */
	public void release(ServerLevel level) {
		if (!this.held) return;
		this.held = false;
		if (this.heldElapsed >= 0) this.entityData.set(START, (int) level.getGameTime() - this.heldElapsed);
		this.heldElapsed = -1;
	}

	/** Заказать волну: пойдёт, как только кончится идущая и зальётся слой. Повторный заказ до её начала ничего не добавляет. */
	public void requestLaunch() {
		this.launchRequested = true;
	}

	@Override
	protected Item item() {
		return LavaWaves.item(kind());
	}

	@Override
	protected void turn() {
		this.entityData.set(YAW, Mth.wrapDegrees(Math.round(yaw() / TURN) * TURN + TURN));
	}

	@Override
	protected void tune(int sign) {
		this.entityData.set(LENGTH, Math.clamp(length() + sign * LENGTH_STEP, MIN_LENGTH, MAX_LENGTH));
	}

	/** ПКМ — курс; Ctrl+ПКМ — путь +2; Shift+ПКМ — подъём лавы за обрушение +1 слой; Ctrl+Shift+ПКМ — предел лавы +1. */
	@Override
	protected void click(Player player) {
		boolean ctrl = holdsCtrl(player), shift = holdsShift(player);
		if (!shift) {
			super.click(player);
			return;
		}
		if (ctrl) setPeak((this.peakY == Integer.MIN_VALUE ? lavaTop() + 8 : this.peakY) + 1);
		else setRise(this.rise + 1);
		announceLevel(player, ctrl);
	}

	/** ЛКМ — убрать; Ctrl+ЛКМ — путь −2; Shift+ЛКМ — подъём −1 слой (до 0: волна лаву не поднимает); Ctrl+Shift+ЛКМ — предел лавы −1 (не ниже лавы сейчас). */
	@Override
	protected boolean hit(ServerLevel level, Player player) {
		boolean ctrl = holdsCtrl(player), shift = holdsShift(player);
		if (!shift) return super.hit(level, player);
		if (ctrl) setPeak(Math.max(lavaTop(), (this.peakY == Integer.MIN_VALUE ? lavaTop() + 8 : this.peakY) - 1));
		else setRise(this.rise - 1);
		announceLevel(player, ctrl);
		return false;
	}

	/** Над панелью коротко, иначе строка не влезает в экран: курс и путь (их меняют ПКМ и Ctrl), остальное — отдельными строками при своей правке. */
	@Override
	public void announce(Player player) {
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.lava_wave_set", Component.translatable("wave.gameplaymod.kind." + kind().id),
				Math.floorMod(Math.round(yaw()), 360), Math.round(length())));
	}

	/** Подъём (Shift) или предел (Ctrl+Shift): и где встанет лава после обрушения. */
	private void announceLevel(Player player, boolean peak) {
		if (!kind().fluid()) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.snow_wave_rise", this.rise));
			return;
		}
		player.sendOverlayMessage(peak ? Component.translatable("message.gameplaymod.lava_wave_peak", this.peakY, lavaAfterCrash())
				: Component.translatable("message.gameplaymod.lava_wave_rise", this.rise, lavaAfterCrash(), this.peakY));
	}

	/** Полуширина (колесо). */
	public void announceWidth(Player player) {
		float half = halfWidth();
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.lava_wave_width", half == Math.rint(half) ? Integer.toString(Math.round(half)) : String.format("%.1f", half)));
	}

	/**
	 * Маркер встаёт над поверхностью материала в столбце под прицелом (клик по жидкости проходит до дна); вид — по этому материалу (нет его — вид
	 * предмета); курс — взгляд. Предел жидкости по умолчанию на 8 слоёв выше.
	 */
	@Override
	public void placedBy(Player player, BlockPos pos) {
		WaveKind found = detect(pos.getX(), pos.getY() + 1, pos.getZ());
		if (found != null) setKind(found);
		int surface = findSurface(kind(), pos.getX(), pos.getY(), pos.getZ());
		int y = surface == Integer.MIN_VALUE ? pos.getY() : surface + 1;
		setPos(pos.getX() + 0.5, y, pos.getZ() + 0.5);
		this.entityData.set(YAW, Math.round(player.getYRot() / TURN) * TURN);
		if (this.peakY == Integer.MIN_VALUE) setPeak(y - 1 + 8);
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 256 * 256;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		super.addAdditionalSaveData(out);
		out.putFloat("Yaw", yaw());
		out.putFloat("Length", length());
		out.putFloat("Height", height());
		out.putFloat("Speed", this.entityData.get(SPEED));
		out.putFloat("HalfWidth", halfWidth());
		out.putInt("PeakY", this.peakY == Integer.MIN_VALUE ? getBlockY() - 1 + 8 : this.peakY);
		out.putInt("Rise", this.rise);
		out.putInt("RiseLeft", this.riseLeft);
		out.putString("Kind", kind().id);
		// прерванная сохранением волна после загрузки пойдёт заново, иначе уровень не дождался бы её конца
		out.putBoolean("Launch", this.launchRequested || this.finishPending);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		super.readAdditionalSaveData(in);
		// маркеры, поставленные до волн воды и снега, — лава
		setKind(WaveKind.byId(in.getStringOr("Kind", WaveKind.LAVA.id)));
		this.entityData.set(YAW, in.getFloatOr("Yaw", yaw()));
		this.entityData.set(LENGTH, Math.clamp(in.getFloatOr("Length", length()), MIN_LENGTH, MAX_LENGTH));
		this.entityData.set(HEIGHT, Math.clamp(in.getFloatOr("Height", height()), 0.5f, 16f));
		this.entityData.set(SPEED, Math.clamp(in.getFloatOr("Speed", this.entityData.get(SPEED)), 0.5f, 40f));
		this.entityData.set(HALF_WIDTH, Math.clamp(in.getFloatOr("HalfWidth", halfWidth()), 0.5f, 64f));
		setPeak(in.getIntOr("PeakY", this.peakY));
		setRise(in.getIntOr("Rise", this.rise));
		this.riseLeft = Math.clamp(in.getIntOr("RiseLeft", this.riseLeft), 0, Math.max(0, MAX_RISE - 1));
		this.launchRequested = in.getBooleanOr("Launch", this.launchRequested);
		// прерванная сохранением волна не продолжается, а начинается заново (Launch)
		if (!level().isClientSide()) this.entityData.set(START, -1);
		// снимок уровня (чекпоинт): волна продолжается с того же места, а не начинается заново (см. progress)
		int elapsed = in.getIntOr("ResumeElapsed", Integer.MIN_VALUE);
		if (elapsed != Integer.MIN_VALUE && !level().isClientSide()) {
			this.launchRequested = in.getBooleanOr("ResumeLaunch", false);
			this.finishPending = in.getBooleanOr("ResumeFinish", false);
			this.crashed = in.getBooleanOr("ResumeCrashed", false);
			if (elapsed >= 0) this.entityData.set(BASE, in.getIntOr("ResumeBase", base()));
			// ход не продолжается сразу: игрок после сброса ещё стоит на ожидании, волна идёт после него (release)
			if (elapsed >= 0 || this.launchRequested || this.finishPending) {
				this.held = true;
				this.heldElapsed = elapsed;
				this.heldSince = level().getGameTime();
			}
		}
	}

	/**
	 * Ход волны для снимка уровня: сколько тиков идёт (−1 — не идёт), от какой лавы начата, обрушилась ли, ждёт ли сообщения о конце и заказанный запуск.
	 * В обычное сохранение мира это не пишется (там прерванная волна начинается заново): только в снимок чекпоинта, чтобы возврат вернул волну в то же место.
	 */
	public net.minecraft.nbt.CompoundTag progress() {
		net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
		double t = waveTime(0);
		tag.putInt("ResumeElapsed", running(t) ? (int) t : -1);
		tag.putInt("ResumeBase", base());
		tag.putBoolean("ResumeCrashed", this.crashed);
		tag.putBoolean("ResumeFinish", this.finishPending);
		tag.putBoolean("ResumeLaunch", this.launchRequested);
		return tag;
	}

	/** Волна никого не толкает и не задевается ничем, кроме прицела с маркером. */
	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}
}
