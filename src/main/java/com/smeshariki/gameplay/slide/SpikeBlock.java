package com.smeshariki.gameplay.slide;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Кол: столкновения нет, задевший получает урон. Один блок несёт весь кол: {@code tip=true} (его ставит рука) — кол высотой {@link #height} и радиусом у основания
 * {@link #radius} от этой клетки вверх, далеко за её пределы. {@code tip=false} — короткое основание той же ширины под колом (старые миры построены парой блоков).
 * Внутри кола ваниль сообщает о сущности только в его клетке, поэтому игроков и вселённых проверяет {@link #tickPlayers} по всей форме.
 */
public class SpikeBlock extends Block {
	public static final BooleanProperty TIP = BooleanProperty.create("tip");
	private static final float DAMAGE = 6f;
	/** Размер исходного кола (по нему считаются множители): высота и радиус основания. */
	private static final double BASE_HEIGHT = 2, BASE_RADIUS = 0.25;
	/** Насколько кол уходит ниже своей клетки, чтобы не висеть над неровной землёй. */
	public static final double SINK = 0.5;
	/** Профиль кола: доля высоты и доля радиуса основания. */
	private static final double[][] PROFILE = { { 0, 1 }, { 0.3, 0.8 }, { 0.55, 0.58 }, { 0.8, 0.3 }, { 1, 0 } };
	/** Самый большой кол: на столько блоков вокруг игрока и вниз смотрим. */
	private static final int SCAN_SIDE = 3, SCAN_DOWN = 11;

	private final int scale;

	public SpikeBlock(Properties properties, int scale) {
		super(properties);
		this.scale = scale;
		registerDefaultState(stateDefinition.any().setValue(TIP, true));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return simpleCodec(properties -> new SpikeBlock(properties, this.scale));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(TIP);
	}

	public double radius() {
		return BASE_RADIUS * this.scale;
	}

	public double height() {
		return BASE_HEIGHT * this.scale;
	}

	/** Кольца формы: высота над низом клетки и радиус; снизу уходят на {@link #SINK} в землю. */
	public double[][] rings(BlockState state) {
		if (!state.getValue(TIP)) return new double[][] { { -SINK, radius() }, { 1, radius() } };
		double[][] out = new double[PROFILE.length + 1][];
		out[0] = new double[] { -SINK, radius() };
		for (int i = 0; i < PROFILE.length; i++) out[i + 1] = new double[] { PROFILE[i][0] * height(), PROFILE[i][1] * radius() };
		return out;
	}

	/** Радиус формы на высоте {@code y} над низом клетки; отрицательный — там кола нет. */
	private double radiusAt(BlockState state, double y) {
		double[][] rings = rings(state);
		if (y < rings[0][0] || y > rings[rings.length - 1][0]) return -1;
		for (int i = 0; i + 1 < rings.length; i++) {
			if (y <= rings[i + 1][0]) return rings[i][1] + (rings[i + 1][1] - rings[i][1]) * (y - rings[i][0]) / (rings[i + 1][0] - rings[i][0]);
		}
		return -1;
	}

	/** Задевает ли ящик форму кола, стоящего в {@code pos}. */
	private boolean touches(BlockState state, BlockPos pos, AABB box) {
		double[][] rings = rings(state);
		// кол сужается кверху: шире всего он в самой нижней точке, где ящик с ним пересекается
		double y = Math.max(box.minY - pos.getY(), rings[0][0]);
		if (box.maxY - pos.getY() < y) return false;
		double r = radiusAt(state, y);
		if (r <= 0) return false;
		double cx = pos.getX() + 0.5, cz = pos.getZ() + 0.5;
		double dx = Math.max(Math.max(box.minX - cx, cx - box.maxX), 0), dz = Math.max(Math.max(box.minZ - cz, cz - box.maxZ), 0);
		return dx * dx + dz * dz < r * r;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		double half = Math.min(0.5, radius());
		return Shapes.box(0.5 - half, 0, 0.5 - half, 0.5 + half, 1, 0.5 + half);
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier applier, boolean precise) {
		if (level instanceof ServerLevel server && !spared(entity) && touches(state, pos, entity.getBoundingBox())) entity.hurtServer(server, level.damageSources().stalagmite(), DAMAGE);
	}

	/** Вселённого бьёт только его персонаж, а без персонажа в выживании игрок выбирает нового: смерть персонажа не должна стоить ему и своей жизни. */
	private static boolean spared(Entity entity) {
		if (!(entity instanceof ServerPlayer player)) return false;
		return player.isPassenger() || player.gameMode() == GameType.SURVIVAL;
	}

	/** Кол выше и шире своей клетки: игрока и его персонажа проверяем по всем колам рядом. */
	public static void tickPlayers(ServerLevel level) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (ServerPlayer player : level.players()) {
			if (!player.isAlive() || player.isSpectator()) continue;
			Entity body = player.getVehicle() != null ? player.getVehicle() : player;
			if (spared(body)) continue;
			AABB box = body.getBoundingBox();
			int x0 = (int) Math.floor(box.minX) - SCAN_SIDE, x1 = (int) Math.floor(box.maxX) + SCAN_SIDE;
			int z0 = (int) Math.floor(box.minZ) - SCAN_SIDE, z1 = (int) Math.floor(box.maxZ) + SCAN_SIDE;
			int y0 = (int) Math.floor(box.minY) - SCAN_DOWN, y1 = (int) Math.floor(box.maxY) + 1;
			for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
				BlockState state = level.getBlockState(at.set(x, y, z));
				if (state.getBlock() instanceof SpikeBlock spike && spike.touches(state, at, box)) body.hurtServer(level, level.damageSources().stalagmite(), DAMAGE);
			}
		}
	}
}
