package com.smeshariki.gameplay.cloth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3fc;

import com.smeshariki.gameplay.raft.Rafts;
import com.smeshariki.gameplay.rope.RopeEntity;
import com.smeshariki.gameplay.rope.RopeHost;
import com.smeshariki.gameplay.wind.WindMarkerEntity;
import com.smeshariki.gameplay.wind.Winds;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Полотнище: прямоугольник W×H клеток, собранный из ширм ({@link Cloths#assemble}), в вертикальной плоскости вдоль оси X или Z.
 * Верхний край держится там, где над ним блок ({@link #pins}), или верёвкой к блоку; без опоры ткань падает. Остальное — сетка точек (метод Верле) со связями растяжения, сдвига и изгиба: висит под тяжестью
 * и развевается ветром маркеров ({@link Winds}), не заходя в блоки. Позиция сущности — середина верхнего края; узлы сетки
 * хранятся от неё. Сетку считают и сервер, и клиент сами (только рядом с игроком), не синхронизируя: складки у них немного
 * разные, зато к узлу можно прицепить верёвку ({@link RopeHost}), и она едет вместе с тканью. Вес висящего ткань не чувствует.
 */
public class ClothEntity extends Entity implements RopeHost {
	public static final int MAX_SIDE = 24, MAX_CELLS = 256;
	/** Узлов сетки не больше (примерно): от этого зависит, сколько узлов приходится на блок (1–4). */
	private static final int MAX_POINTS = 1600;
	/** Тяжесть, блоков за тик² (у сущностей 0,04: ткань легче и медленнее падает). */
	private static final double GRAVITY = 0.03;
	/** Доля скорости узла, остающаяся за тик. */
	private static final double DAMPING = 0.985;
	/** Скорость ветра при полной силе маркера, блоков за тик (как у шара, 25 блоков/с). */
	private static final double WIND_SPEED = 1.25;
	/** Давление ветра на ткань: доля относительной скорости поперёк ткани, переходящая в ускорение за тик. */
	private static final double PRESSURE = 0.08;
	/** Трение воздуха вдоль ткани. */
	private static final double DRAG = 0.01;
	/** Порывы: размах колебания силы ветра (доля). */
	private static final double GUST = 0.35;
	private static final int ITERATIONS = 6;
	/** Жёсткость связей изгиба (растяжение и сдвиг — 1). */
	private static final double BEND = 0.4;
	/** Узел не уходит за тик дальше (защита от разлёта). */
	private static final double MAX_STEP = 1.0;
	/** Доля сдвига вдоль поверхности, которую узел, лёгший на блок, сохраняет за тик (трение). */
	private static final double GROUND_SLIDE = 0.6;
	private static final double EDGE = 1e-4;
	/** Столкновение с телами: запас вокруг коробки тела, высота над ногами, ниже которой узлы не трогаются, и доля скорости узла после толчка. */
	private static final double BODY_MARGIN = 0.08, FEET_SKIP = 0.25, BODY_KEEP = 0.3;
	/**
	 * Опора: наименьшая вертикальная доля нормали, при которой на ткани стоят (0,5 — уклон до 60°); на сколько ноги могут уйти под
	 * поверхность, оставаясь на ней; с какой скоростью вверх относительно ткани тело уже не стоит (прыжок); доля удара и веса тела, уходящая
	 * узлам под ним; вес за тик для тел, которых двигает другая сторона.
	 */
	private static final double FLAT = 0.5, SINK = 0.6, LIFT_OFF = 0.05, IMPACT = 0.5, WEIGHT = 0.03;
	/** Считать сетку, пока игрок ближе. */
	private static final double SIM_RANGE = 96;
	/** До какого расстояния от луча взгляда ловится узел при креплении верёвки. */
	private static final double PICK_MISS = 0.6;

	private static final EntityDataAccessor<Integer> WIDTH = SynchedEntityData.defineId(ClothEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> HEIGHT = SynchedEntityData.defineId(ClothEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> ALONG_X = SynchedEntityData.defineId(ClothEntity.class, EntityDataSerializers.BOOLEAN);
	/** Лежачее полотнище (из лежачих ширм): ширина вдоль X, строки к югу, держится за блоки по краям. */
	private static final EntityDataAccessor<Boolean> FLAT_DATA = SynchedEntityData.defineId(ClothEntity.class, EntityDataSerializers.BOOLEAN);
	/** Состояния клеток для клиента (цвет): номера состояний через запятую, по строкам сверху вниз. */
	private static final EntityDataAccessor<String> CELLS = SynchedEntityData.defineId(ClothEntity.class, EntityDataSerializers.STRING);

	/** Клетки по строкам сверху вниз, слева направо (по возрастанию оси). Только сервер; клиент — {@link #cellStates}. */
	private BlockState[] cells = new BlockState[0];
	private String parsedFrom;
	private BlockState[] parsed = new BlockState[0];

	// сетка: узлов nx × ny, номер i + j·nx; j = 0 — закреплённый верх
	private int seg, nx, ny;
	/** Для какого положения (лежачее или стоячее) построена сетка. */
	private boolean gridFlat;
	private double[] px, py, pz, ox, oy, oz;
	private int[] ca, cb;
	private double[] rest, stiff;

	public ClothEntity(EntityType<? extends ClothEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
		this.setInvulnerable(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(WIDTH, 1);
		builder.define(HEIGHT, 1);
		builder.define(ALONG_X, true);
		builder.define(FLAT_DATA, false);
		builder.define(CELLS, "");
	}

	/** Сборка: левая верхняя клетка, ось ширины, размер и клетки. */
	void setup(BlockPos topLeft, boolean alongX, boolean flat, int width, int height, BlockState[] cells) {
		this.entityData.set(WIDTH, width);
		this.entityData.set(HEIGHT, height);
		this.entityData.set(ALONG_X, alongX || flat);
		this.entityData.set(FLAT_DATA, flat);
		setCells(cells);
		if (flat) {
			// лежит посередине клетки по высоте, как лежачая ширма; позиция — середина северного края
			setPos(topLeft.getX() + width / 2.0, topLeft.getY() + 0.5, topLeft.getZ());
			return;
		}
		setPos(topLeft.getX() + (alongX ? width / 2.0 : 0.5), topLeft.getY() + 1, topLeft.getZ() + (alongX ? 0.5 : width / 2.0));
	}

	private void setCells(BlockState[] cells) {
		this.cells = cells;
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) b.append(',');
			b.append(Block.getId(cells[i]));
		}
		this.entityData.set(CELLS, b.toString());
	}

	public int width() {
		return this.entityData.get(WIDTH);
	}

	public int height() {
		return this.entityData.get(HEIGHT);
	}

	public boolean flat() {
		return this.entityData.get(FLAT_DATA);
	}

	public boolean alongX() {
		return this.entityData.get(ALONG_X);
	}

	/** Единичный вектор ширины (к возрастанию номеров столбцов). */
	public Vec3 along() {
		return alongX() ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1);
	}

	/** Состояния клеток (на обеих сторонах), по строкам сверху вниз. */
	public BlockState[] cellStates() {
		if (!level().isClientSide()) return cells;
		String s = this.entityData.get(CELLS);
		if (!s.equals(parsedFrom)) {
			parsedFrom = s;
			String[] parts = s.isEmpty() ? new String[0] : s.split(",");
			parsed = new BlockState[parts.length];
			for (int i = 0; i < parts.length; i++) {
				try {
					parsed[i] = Block.stateById(Integer.parseInt(parts[i]));
				} catch (NumberFormatException e) {
					parsed[i] = Blocks.AIR.defaultBlockState();
				}
			}
		}
		return parsed;
	}

	/** Блок клетки (столбец c, строка r сверху). */
	public BlockPos cellPos(int c, int r) {
		Vec3 p = position();
		int w = width();
		if (flat()) return new BlockPos((int) Math.round(p.x - w / 2.0) + c, (int) Math.floor(p.y), (int) Math.round(p.z) + r);
		int x = alongX() ? (int) Math.round(p.x - w / 2.0) + c : (int) Math.floor(p.x);
		int z = alongX() ? (int) Math.floor(p.z) : (int) Math.round(p.z - w / 2.0) + c;
		return new BlockPos(x, (int) Math.round(p.y) - 1 - r, z);
	}

	// ---------------------------------------------------------------- сетка

	public int seg() {
		ensureGrid();
		return seg;
	}

	public int nx() {
		ensureGrid();
		return nx;
	}

	public int ny() {
		ensureGrid();
		return ny;
	}

	/** Узел (i, j) от позиции сущности при кадре partialTick, в out[0..2]. */
	public void node(int i, int j, float partialTick, double[] out) {
		ensureGrid();
		int k = i + j * nx;
		out[0] = ox[k] + (px[k] - ox[k]) * partialTick;
		out[1] = oy[k] + (py[k] - oy[k]) * partialTick;
		out[2] = oz[k] + (pz[k] - oz[k]) * partialTick;
	}

	private void ensureGrid() {
		int w = width(), h = height();
		int s = Math.clamp((int) Math.floor(Math.sqrt((double) MAX_POINTS / (w * h))), 1, 4);
		if (px != null && seg == s && nx == w * s + 1 && ny == h * s + 1 && gridFlat == flat()) return;
		gridFlat = flat();
		seg = s;
		nx = w * s + 1;
		ny = h * s + 1;
		int n = nx * ny;
		px = new double[n];
		py = new double[n];
		pz = new double[n];
		ox = new double[n];
		oy = new double[n];
		oz = new double[n];
		resetGrid();
		List<int[]> links = new ArrayList<>();
		List<double[]> params = new ArrayList<>();
		double step = 1.0 / s, diag = Math.sqrt(2) * step;
		for (int j = 0; j < ny; j++) {
			for (int i = 0; i < nx; i++) {
				int k = i + j * nx;
				if (i + 1 < nx) link(links, params, k, k + 1, step, 1);
				if (j + 1 < ny) link(links, params, k, k + nx, step, 1);
				if (i + 1 < nx && j + 1 < ny) {
					link(links, params, k, k + nx + 1, diag, 1);
					link(links, params, k + 1, k + nx, diag, 1);
				}
				if (i + 2 < nx) link(links, params, k, k + 2, 2 * step, BEND);
				if (j + 2 < ny) link(links, params, k, k + 2 * nx, 2 * step, BEND);
			}
		}
		ca = new int[links.size()];
		cb = new int[links.size()];
		rest = new double[links.size()];
		stiff = new double[links.size()];
		for (int i = 0; i < links.size(); i++) {
			ca[i] = links.get(i)[0];
			cb[i] = links.get(i)[1];
			rest[i] = params.get(i)[0];
			stiff[i] = params.get(i)[1];
		}
	}

	private static void link(List<int[]> links, List<double[]> params, int a, int b, double length, double stiffness) {
		links.add(new int[] {a, b});
		params.add(new double[] {length, stiffness});
	}

	/** Ровно висящее полотнище. */
	private void resetGrid() {
		Vec3 along = along();
		double half = width() / 2.0;
		for (int j = 0; j < ny; j++) {
			for (int i = 0; i < nx; i++) {
				int k = i + j * nx;
				double a = (double) i / seg - half, b = (double) j / seg;
				px[k] = ox[k] = along.x * a;
				py[k] = oy[k] = flat() ? 0 : -b;
				pz[k] = oz[k] = flat() ? b : along.z * a;
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		ensureGrid();
		if (!playerNear()) return;
		step();
		setBoundingBox(makeBoundingBox(position()));
	}

	/** Игрок любого режима ближе {@link #SIM_RANGE} (getNearestPlayer не видит творческих). */
	private boolean playerNear() {
		for (Player p : level().players()) if (p.distanceToSqr(this) < SIM_RANGE * SIM_RANGE) return true;
		return false;
	}

	private void step() {
		int w = width(), h = height();
		Vec3 base = position();
		// ветер по клеткам: у маркера зона 5 блоков, поэтому по полотнищу сила меняется
		List<WindMarkerEntity> markers = Winds.near(level(), getBoundingBox());
		Vec3[] wind = new Vec3[w * h];
		if (!markers.isEmpty()) {
			for (int r = 0; r < h; r++) {
				for (int c = 0; c < w; c++) {
					BlockPos cell = cellPos(c, r);
					Vec3 v = Winds.at(markers, level(), new AABB(cell));
					wind[c + r * w] = v == null ? null : v.scale(WIND_SPEED);
				}
			}
		}
		boolean[] pinned = pins();
		List<RopeEntity> ropes = tethers();
		long t = level().getGameTime();
		double seed = (getUUID().getLeastSignificantBits() & 0xFFFF) / 1000.0;
		for (int j = 0; j < ny; j++) {
			for (int i = 0; i < nx; i++) {
				int k = i + j * nx;
				if (pinned[k]) {
					ox[k] = px[k];
					oy[k] = py[k];
					oz[k] = pz[k];
					continue;
				}
				double vx = (px[k] - ox[k]) * DAMPING, vy = (py[k] - oy[k]) * DAMPING, vz = (pz[k] - oz[k]) * DAMPING;
				double ax = 0, ay = -GRAVITY, az = 0;
				Vec3 wv = wind[Math.min(w - 1, i / seg) + Math.min(h - 1, Math.max(0, j - 1) / seg) * w];
				if (wv != null) {
					double gust = 1 + GUST * (Math.sin(t * 0.13 + i * 0.45 + seed) * Math.sin(t * 0.071 + j * 0.37 + seed * 0.5)
							+ 0.5 * Math.sin(t * 0.29 + (i + j) * 0.8 + seed));
					double rx = wv.x * gust - vx, ry = wv.y * gust - vy, rz = wv.z * gust - vz;
					// нормаль ткани в узле по соседям
					int l = i > 0 ? k - 1 : k, rr = i + 1 < nx ? k + 1 : k, u = j > 0 ? k - nx : k, d = j + 1 < ny ? k + nx : k;
					double ux = px[rr] - px[l], uy = py[rr] - py[l], uz = pz[rr] - pz[l];
					double tx = px[d] - px[u], ty = py[d] - py[u], tz = pz[d] - pz[u];
					double nx0 = uy * tz - uz * ty, ny0 = uz * tx - ux * tz, nz0 = ux * ty - uy * tx;
					double len = Math.sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0);
					if (len > 1e-9) {
						nx0 /= len;
						ny0 /= len;
						nz0 /= len;
						double pn = (rx * nx0 + ry * ny0 + rz * nz0) * PRESSURE;
						ax += nx0 * pn;
						ay += ny0 * pn;
						az += nz0 * pn;
					}
					ax += rx * DRAG;
					ay += ry * DRAG;
					az += rz * DRAG;
				}
				double sx = vx + ax, sy = vy + ay, sz = vz + az;
				double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
				if (sl > MAX_STEP) {
					sx *= MAX_STEP / sl;
					sy *= MAX_STEP / sl;
					sz *= MAX_STEP / sl;
				}
				ox[k] = px[k];
				oy[k] = py[k];
				oz[k] = pz[k];
				px[k] += sx;
				py[k] += sy;
				pz[k] += sz;
			}
		}
		// длина каждой привязи на этот шаг: аркан, не дотягивающийся до своей точки, подтягивает узел понемногу, а не прыжком
		double[] tetherLength = new double[ropes.size()];
		for (int n = 0; n < tetherLength.length; n++) {
			RopeEntity rope = ropes.get(n);
			int k = ropeNode(rope);
			Vec3 end = rope.position();
			double dx = base.x + px[k] - end.x, dy = base.y + py[k] - end.y, dz = base.z + pz[k] - end.z;
			// у аркана в руке длина — рычаг (от крепления до руки), а «конец» — рука держащего
			tetherLength[n] = rope.tetherLength(rope.towing() ? rope.armLength() : rope.length(), Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		for (int it = 0; it < ITERATIONS; it++) {
			for (int c = 0; c < ca.length; c++) {
				int a = ca[c], b = cb[c];
				double dx = px[b] - px[a], dy = py[b] - py[a], dz = pz[b] - pz[a];
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (dist < 1e-9) continue;
				double diff = (dist - rest[c]) / dist * stiff[c];
				boolean pa = pinned[a], pb = pinned[b];
				if (pa && pb) continue;
				double fa = pa ? 0 : pb ? 1 : 0.5, fb = pb ? 0 : pa ? 1 : 0.5;
				px[a] += dx * diff * fa;
				py[a] += dy * diff * fa;
				pz[a] += dz * diff * fa;
				px[b] -= dx * diff * fb;
				py[b] -= dy * diff * fb;
				pz[b] -= dz * diff * fb;
			}
			// верёвка, зацепленная другим концом за блок, держит свой узел не дальше длины
			for (int n = 0; n < tetherLength.length; n++) {
				RopeEntity rope = ropes.get(n);
				int k = ropeNode(rope);
				if (pinned[k]) continue;
				Vec3 end = rope.position();
				double dx = base.x + px[k] - end.x, dy = base.y + py[k] - end.y, dz = base.z + pz[k] - end.z;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz), len = tetherLength[n];
				if (dist <= len || dist < 1e-9) continue;
				double f = len / dist - 1;
				px[k] += dx * f;
				py[k] += dy * f;
				pz[k] += dz * f;
			}
		}
		collideBodies(base, pinned);
		supportBodies(base, pinned);
		// узел в блоке: сначала возвращаем высоту (лёг на блок и скользит с трением), если и так внутри — назад целиком
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean broken = false;
		for (int k = 0; k < px.length; k++) {
			if (pinned[k]) continue;
			if (!Double.isFinite(px[k] + py[k] + pz[k])) {
				broken = true;
				break;
			}
			if (!inBlock(base, px[k], py[k], pz[k], pos)) continue;
			if (!inBlock(base, px[k], oy[k], pz[k], pos)) {
				py[k] = oy[k];
				px[k] = ox[k] + (px[k] - ox[k]) * GROUND_SLIDE;
				pz[k] = oz[k] + (pz[k] - oz[k]) * GROUND_SLIDE;
				if (!inBlock(base, px[k], py[k], pz[k], pos)) continue;
			}
			px[k] = ox[k];
			py[k] = oy[k];
			pz[k] = oz[k];
		}
		if (broken) resetGrid();
	}

	/**
	 * Столкновение с чужими хитбоксами: узел, оказавшийся в коробке сущности или блока плота (с запасом {@link #BODY_MARGIN}), выталкивается
	 * к её ближайшей боковой грани или наверх — ткань отодвигается идущим сквозь неё, ложится на плечи, на мяч, на палубу плота и свисает с
	 * них. Вниз не толкается. Берутся все сущности, кроме наблюдателей, пассажиров, верёвок, другой ткани, сидений и маркеров мода (у них нет
	 * тела), а у плотов и шаров — не общая коробка, а блоки. У живых узлы у самых ног ({@link #FEET_SKIP}) не трогаются: по лежащей ткани
	 * ходят, а не гоняют её ногами (стоят на ней — {@link #supportBodies}). Часть скорости от толчка гасится ({@link #BODY_KEEP}), иначе ткань
	 * отлетает. Считают и сервер, и клиент.
	 */
	private void collideBodies(Vec3 base, boolean[] pinned) {
		AABB reach = getBoundingBox().inflate(1.0);
		List<AABB> boxes = new ArrayList<>();
		List<Double> skips = new ArrayList<>();
		for (Entity body : level().getEntities(this, reach, e -> !e.isSpectator() && !e.noPhysics && !e.isPassenger() && !(e instanceof RopeEntity) && !(e instanceof ClothEntity)
				&& !(e instanceof com.smeshariki.gameplay.raft.RaftEntity) && !(e instanceof com.smeshariki.gameplay.raft.SeatEntity)
				&& !(e instanceof com.smeshariki.gameplay.barrage.FireMarkerEntity) && !(e instanceof com.smeshariki.gameplay.current.CurrentMarkerEntity)
				&& !(e instanceof WindMarkerEntity) && !(e instanceof com.smeshariki.gameplay.lava.LavaTideEntity))) {
			boxes.add(body.getBoundingBox());
			skips.add(body instanceof net.minecraft.world.entity.LivingEntity ? FEET_SKIP : 0.0);
		}
		// плоты, шары и брёвна: их блоки (список общий у сервера и клиента одиночной игры, поэтому отбор по миру)
		List<VoxelShape> shapes = new ArrayList<>();
		for (com.smeshariki.gameplay.raft.RaftEntity raft : com.smeshariki.gameplay.raft.RaftEntity.ALL) {
			if (raft.level() == level() && !raft.isRemoved() && raft.getBoundingBox().intersects(reach)) raft.collect(reach, shapes);
		}
		for (VoxelShape shape : shapes) {
			for (AABB box : shape.toAabbs()) {
				boxes.add(box);
				skips.add(0.0);
			}
		}
		for (int n = 0; n < boxes.size(); n++) {
			AABB box = boxes.get(n).inflate(BODY_MARGIN);
			double skip = skips.get(n);
			double x0 = box.minX - base.x, x1 = box.maxX - base.x, y0 = box.minY - base.y, y1 = box.maxY - base.y, z0 = box.minZ - base.z, z1 = box.maxZ - base.z;
			for (int k = 0; k < px.length; k++) {
				if (pinned[k]) continue;
				double x = px[k], y = py[k], z = pz[k];
				if (x <= x0 || x >= x1 || y <= y0 + skip || y >= y1 || z <= z0 || z >= z1) continue;
				double west = x - x0, east = x1 - x, north = z - z0, south = z1 - z, up = y1 - y;
				double least = Math.min(Math.min(west, east), Math.min(Math.min(north, south), up));
				if (least == up) py[k] = y1;
				else if (least == west) px[k] = x0;
				else if (least == east) px[k] = x1;
				else if (least == north) pz[k] = z0;
				else pz[k] = z1;
				ox[k] = px[k] - (px[k] - ox[k]) * BODY_KEEP;
				oy[k] = py[k] - (py[k] - oy[k]) * BODY_KEEP;
				oz[k] = pz[k] - (pz[k] - oz[k]) * BODY_KEEP;
			}
		}
	}

	/**
	 * Ткань как опора: там, где она лежит полого (нормаль ближе к вертикали, чем {@link #FLAT}), на ней стоят. Тело, опустившееся ногами до
	 * поверхности ткани (или ниже неё не больше чем на {@link #SINK}) и не летящее вверх быстрее ткани, ставится на поверхность, считается
	 * стоящим на земле (ходит, прыгает, падение сброшено) и получает скорость поверхности — качнувшаяся вверх ткань подбрасывает. Обратно
	 * тело давит на узлы под собой: удар при посадке и вес каждый тик уходят им в скорость ({@link #IMPACT}), ткань прогибается, а связи и
	 * привязи тянут её назад. Тело двигает тот, кто им управляет (игрока и персонажа с игроком — клиент этого игрока по своей ткани,
	 * остальных живых — сервер); сервер игроку только сбрасывает падение. Ткань у сторон немного разная, поэтому высоты расходятся на доли
	 * блока. Ткани нужна опора по краям (верёвки к блокам, {@link #tethers}; блоки), иначе она просто упадёт с телом.
	 */
	private void supportBodies(Vec3 base, boolean[] pinned) {
		List<Entity> bodies = level().getEntities(this, getBoundingBox().inflate(0.5, SINK + 0.5, 0.5), e -> !e.isSpectator() && !e.noPhysics && !e.isPassenger()
				&& e instanceof net.minecraft.world.entity.LivingEntity);
		if (bodies.isEmpty()) return;
		java.util.Set<Integer> under = new java.util.HashSet<>();
		double[] speed = new double[1];
		for (Entity body : bodies) {
			under.clear();
			double surface = surfaceUnder(body, base, under, speed);
			if (Double.isNaN(surface)) continue;
			Player driver = body instanceof Player p ? p : body.getControllingPassenger() instanceof Player p ? p : null;
			boolean mine = driver != null ? level().isClientSide() && driver.isLocalPlayer() : !level().isClientSide();
			double push = WEIGHT;
			if (mine) {
				Vec3 v = body.getDeltaMovement();
				double relative = v.y - speed[0];
				if (relative > LIFT_OFF) continue;
				push = Math.max(0, -relative) * IMPACT;
				body.setPos(body.getX(), surface, body.getZ());
				body.setDeltaMovement(v.x, Math.max(v.y, speed[0]), v.z);
				body.setOnGround(true);
			}
			body.resetFallDistance();
			for (int k : under) if (!pinned[k]) oy[k] += push;
		}
	}

	/**
	 * Высота поверхности ткани под телом (в мире) — наибольшая под серединой и четырьмя точками его следа, если ткань там пологая, а ноги
	 * тела на ней или продавили её не глубже {@link #SINK}; NaN — тело на ткани не стоит. В {@code under} — узлы ткани под телом,
	 * в {@code speed[0]} — их средняя скорость по высоте за тик.
	 */
	private double surfaceUnder(Entity body, Vec3 base, java.util.Set<Integer> under, double[] speed) {
		if (px == null) return Double.NaN;
		AABB box = body.getBoundingBox();
		double feet = box.minY - base.y, cx = (box.minX + box.maxX) / 2 - base.x, cz = (box.minZ + box.maxZ) / 2 - base.z;
		double hx = (box.maxX - box.minX) * 0.3, hz = (box.maxZ - box.minZ) * 0.3;
		double[][] samples = { { cx, cz }, { cx - hx, cz - hz }, { cx + hx, cz - hz }, { cx - hx, cz + hz }, { cx + hx, cz + hz } };
		double best = Double.NaN;
		for (int j = 0; j + 1 < ny; j++) {
			for (int i = 0; i + 1 < nx; i++) {
				int a = i + j * nx, b = a + 1, c = a + nx, d = c + 1;
				double minX = Math.min(Math.min(px[a], px[b]), Math.min(px[c], px[d])), maxX = Math.max(Math.max(px[a], px[b]), Math.max(px[c], px[d]));
				double minZ = Math.min(Math.min(pz[a], pz[b]), Math.min(pz[c], pz[d])), maxZ = Math.max(Math.max(pz[a], pz[b]), Math.max(pz[c], pz[d]));
				if (maxX < cx - hx || minX > cx + hx || maxZ < cz - hz || minZ > cz + hz) continue;
				for (int[] tri : new int[][] { { a, b, c }, { b, d, c } }) {
					int p = tri[0], q = tri[1], r = tri[2];
					double ux = px[q] - px[p], uy = py[q] - py[p], uz = pz[q] - pz[p], vx = px[r] - px[p], vy = py[r] - py[p], vz = pz[r] - pz[p];
					double nx0 = uy * vz - uz * vy, ny0 = uz * vx - ux * vz, nz0 = ux * vy - uy * vx;
					double length = Math.sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0);
					// det — удвоенная площадь треугольника в плане; у отвесной ткани она нулевая
					double det = ux * vz - uz * vx;
					if (length < 1e-9 || Math.abs(ny0) / length < FLAT || Math.abs(det) < 1e-9) continue;
					for (double[] sample : samples) {
						double sx = sample[0] - px[p], sz = sample[1] - pz[p];
						double s = (sx * vz - sz * vx) / det, t = (ux * sz - uz * sx) / det;
						if (s < 0 || t < 0 || s + t > 1) continue;
						double y = py[p] + uy * s + vy * t;
						if (feet > y + 0.02 || y - feet > SINK) continue;
						if (Double.isNaN(best) || y > best) best = y;
						under.add(p);
						under.add(q);
						under.add(r);
					}
				}
			}
		}
		if (Double.isNaN(best)) return Double.NaN;
		double sum = 0;
		for (int k : under) sum += py[k] - oy[k];
		speed[0] = sum / under.size();
		return best + base.y;
	}

	/** Тело стоит на какой-нибудь ткани (сервер: стоящий на ткани не «летит», под ним нет блоков мира). */
	public static boolean standsOnCloth(Entity body) {
		double[] speed = new double[1];
		for (ClothEntity cloth : body.level().getEntitiesOfClass(ClothEntity.class, body.getBoundingBox().inflate(1.0))) {
			if (!Double.isNaN(cloth.surfaceUnder(body, cloth.position(), new java.util.HashSet<>(), speed))) return true;
		}
		return false;
	}

	/**
	 * Закреплённые узлы: их нет. За блоки ткань не цепляется — ни стоячая за блоки над верхним рядом, ни лежачая за соседние: держат её
	 * только верёвки ({@link #tethers}), без них она падает на то, что под ней.
	 */
	private boolean[] pins() {
		return new boolean[px.length];
	}

	/** Верёвки с креплением на этой ткани и концом, зацепленным за блок мира: они держат свой узел. */
	private List<RopeEntity> tethers() {
		return level().getEntitiesOfClass(RopeEntity.class, getBoundingBox().inflate(RopeEntity.LONGEST + 2),
				r -> r.anchorEntityNetId() == getId() && (r.endOnBlock() || r.towing()));
	}

	private int ropeNode(RopeEntity rope) {
		Vector3fc l = rope.anchorLocal();
		return Math.clamp(Math.round(l.x()), 0, nx - 1) + Math.clamp(Math.round(l.y()), 0, ny - 1) * nx;
	}

	/** Ткань держится: хоть один узел закреплён или висит на верёвке к блоку. */
	public boolean supported() {
		ensureGrid();
		for (boolean b : pins()) if (b) return true;
		return !tethers().isEmpty();
	}

	/** Точка (от base) внутри формы столкновения блока. */
	private boolean inBlock(Vec3 base, double dx, double dy, double dz, BlockPos.MutableBlockPos pos) {
		double x = base.x + dx, y = base.y + dy, z = base.z + dz;
		pos.set(Math.floor(x), Math.floor(y), Math.floor(z));
		BlockState state = level().getBlockState(pos);
		if (state.isAir()) return false;
		VoxelShape shape = state.getCollisionShape(level(), pos);
		if (shape.isEmpty()) return false;
		double lx = x - pos.getX(), ly = y - pos.getY(), lz = z - pos.getZ();
		// строго внутри: узел ровно на грани блока (край лежачего полотнища вплотную к стене) в блоке не считается, иначе он застывает на месте
		for (AABB box : shape.toAabbs()) {
			if (lx > box.minX + EDGE && lx < box.maxX - EDGE && ly > box.minY + EDGE && ly < box.maxY - EDGE && lz > box.minZ + EDGE && lz < box.maxZ - EDGE) return true;
		}
		return false;
	}

	/** Ящик по узлам: по нему прицел и отсечение отрисовки. */
	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (px == null) {
			Vec3 along = along();
			double half = width() / 2.0;
			if (flat()) return new AABB(pos.x - half, pos.y, pos.z, pos.x + half, pos.y, pos.z + height()).inflate(0.1);
			return new AABB(pos.add(along.scale(-half)).add(0, -height(), 0), pos.add(along.scale(half))).inflate(0.1);
		}
		double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
		for (int k = 0; k < px.length; k++) {
			x0 = Math.min(x0, px[k]);
			y0 = Math.min(y0, py[k]);
			z0 = Math.min(z0, pz[k]);
			x1 = Math.max(x1, px[k]);
			y1 = Math.max(y1, py[k]);
			z1 = Math.max(z1, pz[k]);
		}
		return new AABB(pos.x + x0, pos.y + y0, pos.z + z0, pos.x + x1, pos.y + y1, pos.z + z1).inflate(0.1);
	}

	// ---------------------------------------------------------------- верёвки

	@Override
	public Vec3 ropeLocal(Vec3 world) {
		ensureGrid();
		Vec3 base = position();
		int best = nx;
		double bestD = Double.MAX_VALUE;
		for (int k = 0; k < px.length; k++) {
			double dx = base.x + px[k] - world.x, dy = base.y + py[k] - world.y, dz = base.z + pz[k] - world.z;
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = k;
			}
		}
		return new Vec3(best % nx, best / nx, 0);
	}

	@Override
	public Vec3 ropePoint(Vector3fc local, float partialTick) {
		ensureGrid();
		int i = Math.clamp(Math.round(local.x()), 0, nx - 1), j = Math.clamp(Math.round(local.y()), 0, ny - 1);
		double[] p = new double[3];
		node(i, j, partialTick, p);
		Vec3 base = position();
		return new Vec3(base.x + p[0], base.y + p[1], base.z + p[2]);
	}

	@Override
	public Vec3 ropePick(Vec3 from, Vec3 dir, double reach) {
		ensureGrid();
		Vec3 base = position();
		double fx = from.x - base.x, fy = from.y - base.y, fz = from.z - base.z;
		// точное попадание: ближайшее пересечение луча с самой тканью (её треугольниками), как бы она ни была согнута
		double hitT = Double.MAX_VALUE;
		for (int j = 0; j + 1 < ny; j++) {
			for (int i = 0; i + 1 < nx; i++) {
				int a = i + j * nx, b = a + 1, c = a + nx, d = c + 1;
				for (int[] tri : new int[][] { { a, b, c }, { b, d, c } }) {
					int p = tri[0], q = tri[1], r = tri[2];
					double e1x = px[q] - px[p], e1y = py[q] - py[p], e1z = pz[q] - pz[p], e2x = px[r] - px[p], e2y = py[r] - py[p], e2z = pz[r] - pz[p];
					double hx = dir.y * e2z - dir.z * e2y, hy = dir.z * e2x - dir.x * e2z, hz = dir.x * e2y - dir.y * e2x;
					double det = e1x * hx + e1y * hy + e1z * hz;
					if (Math.abs(det) < 1e-9) continue;
					double sx = fx - px[p], sy = fy - py[p], sz = fz - pz[p];
					double u = (sx * hx + sy * hy + sz * hz) / det;
					if (u < 0 || u > 1) continue;
					double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
					double v = (dir.x * qx + dir.y * qy + dir.z * qz) / det;
					if (v < 0 || u + v > 1) continue;
					double t = (e2x * qx + e2y * qy + e2z * qz) / det;
					if (t >= 0 && t <= reach && t < hitT) hitT = t;
				}
			}
		}
		Vec3 best = null;
		if (hitT < Double.MAX_VALUE) {
			// узел, ближайший к точке попадания
			double hx = fx + dir.x * hitT, hy = fy + dir.y * hitT, hz = fz + dir.z * hitT, bestD = Double.MAX_VALUE;
			for (int k = 0; k < px.length; k++) {
				double dx = px[k] - hx, dy = py[k] - hy, dz = pz[k] - hz, d2 = dx * dx + dy * dy + dz * dz;
				if (d2 < bestD) {
					bestD = d2;
					best = new Vec3(base.x + px[k], base.y + py[k], base.z + pz[k]);
				}
			}
			return best;
		}
		// мимо самой ткани (у края): узел, ближайший к лучу, не дальше PICK_MISS
		double bestMiss = PICK_MISS * PICK_MISS;
		for (int k = 0; k < px.length; k++) {
			double qx = px[k] - fx, qy = py[k] - fy, qz = pz[k] - fz;
			double t = qx * dir.x + qy * dir.y + qz * dir.z;
			if (t < 0 || t > reach) continue;
			double mx = qx - dir.x * t, my = qy - dir.y * t, mz = qz - dir.z * t, miss = mx * mx + my * my + mz * mz;
			if (miss >= bestMiss) continue;
			bestMiss = miss;
			best = new Vec3(base.x + px[k], base.y + py[k], base.z + pz[k]);
		}
		return best;
	}

	// ---------------------------------------------------------------- игрок

	/** ПКМ подвесом по ткани — разобрать обратно в ширмы. */
	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().is(Cloths.ITEM)) return InteractionResult.PASS;
		if (level() instanceof ServerLevel server && Rafts.freshClick(player)) dismantle(server);
		return InteractionResult.SUCCESS;
	}

	/** Ширмы встают на свои клетки; где место занято — выпадают предметом. Верёвки на ткани падают предметами. */
	public void dismantle(ServerLevel level) {
		int w = width(), h = height();
		if (!supported()) {
			// упавшая ткань лежит не там, где стояли ширмы: они выпадают предметами посреди неё
			Vec3 mid = getBoundingBox().getCenter();
			BlockPos at = BlockPos.containing(mid);
			for (BlockState state : cells) if (!state.isAir()) Block.dropResources(state, level, at);
			discard();
			return;
		}
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				int idx = c + r * w;
				if (idx >= cells.length || cells[idx].isAir()) continue;
				BlockState state = cells[idx];
				BlockPos pos = cellPos(c, r);
				if (level.getBlockState(pos).canBeReplaced()) level.setBlock(pos, state, Block.UPDATE_ALL);
				else Block.dropResources(state, level, pos);
			}
		}
		discard();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	/** Прицел ловит ткань только с подвесом или верёвкой в руке (у клиента), иначе ткань мешала бы копать и открывать двери за ней. */
	@Override
	public boolean isPickable() {
		return !level().isClientSide() || Cloths.pickableOnClient.getAsBoolean();
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 128 * 128;
	}

	// ---------------------------------------------------------------- сохранение

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		out.putInt("W", width());
		out.putInt("H", height());
		out.putBoolean("AlongX", alongX());
		out.putBoolean("Flat", flat());
		List<BlockState> palette = new ArrayList<>();
		Map<BlockState, Integer> index = new HashMap<>();
		int[] ids = new int[cells.length];
		for (int i = 0; i < cells.length; i++) {
			Integer at = index.get(cells[i]);
			if (at == null) {
				at = palette.size();
				palette.add(cells[i]);
				index.put(cells[i], at);
			}
			ids[i] = at;
		}
		out.store("Palette", BlockState.CODEC.listOf(), palette);
		out.putIntArray("Cells", ids);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		this.entityData.set(WIDTH, Math.clamp(in.getIntOr("W", 1), 1, MAX_SIDE));
		this.entityData.set(HEIGHT, Math.clamp(in.getIntOr("H", 1), 1, MAX_SIDE));
		this.entityData.set(ALONG_X, in.getBooleanOr("AlongX", true));
		this.entityData.set(FLAT_DATA, in.getBooleanOr("Flat", false));
		List<BlockState> palette = in.read("Palette", BlockState.CODEC.listOf()).orElse(List.of());
		int[] ids = in.getIntArray("Cells").orElse(new int[0]);
		BlockState[] loaded = new BlockState[ids.length];
		for (int i = 0; i < ids.length; i++) loaded[i] = ids[i] >= 0 && ids[i] < palette.size() ? palette.get(ids[i]) : Blocks.AIR.defaultBlockState();
		setCells(loaded);
		px = null;
	}
}
