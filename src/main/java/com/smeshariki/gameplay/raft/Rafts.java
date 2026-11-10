package com.smeshariki.gameplay.raft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Плоты по течению: предмет-связка, сущность {@link RaftEntity} и сборка/разборка. ПКМ связкой по блоку собирает в плот
 * всю связную постройку; ПКМ связкой по плоту — разбирает его обратно в блоки. Воздушный шар
 * ({@link Balloons}) собирается и хранится так же.
 */
public final class Rafts {
	public static EntityType<RaftEntity> TYPE;
	public static Item ITEM;
	/** Больше блоков в один плот не собираем. */
	public static final int MAX_BLOCKS = 300;
	/** Досягаемость при разборке плота. */
	public static final double REACH = 5.0;

	/** Игра повторяет зажатый ПКМ каждые 4 тика; перерыв длиннее этого — уже новое нажатие. */
	private static final int HOLD_GAP = 8;
	private static final Map<Player, Long> LAST_USE = new java.util.WeakHashMap<>();

	private Rafts() {
	}

	/**
	 * Сервер: новое ли это нажатие, а не повтор зажатого ПКМ. Сервер видит только повторы, поэтому считаем их одним кликом,
	 * пока между ними нет перерыва. Иначе зажатая кнопка разбирала только что собранный плот и тут же собирала его снова.
	 */
	public static boolean freshClick(Player player) {
		long now = player.level().getGameTime();
		Long last = LAST_USE.put(player, now);
		return last == null || now - last > HOLD_GAP;
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "raft"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<RaftEntity>of(RaftEntity::new, MobCategory.MISC)
				.sized(0.5f, 0.5f).clientTrackingRange(12).updateInterval(1).fireImmune().noSummon().build(typeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "raft_binder"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new Item(new Item.Properties().setId(itemKey).stacksTo(1)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));

		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof RaftEntity r) RaftEntity.ALL.add(r);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof RaftEntity r) RaftEntity.ALL.remove(r);
		});
		PayloadTypeRegistry.clientboundPlay().register(RaftBlocks.TYPE, RaftBlocks.CODEC);
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof RaftEntity r) ServerPlayNetworking.send(player, blocks(r));
		});

		// клиент пропускает дальше: пакет использования уйдёт на сервер, где всё решается
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (hand != InteractionHand.MAIN_HAND || !stack.is(ITEM) || player.isSpectator() || level.isClientSide() || !freshClick(player)) return InteractionResult.PASS;
			return dismantleLooked(player, level, Double.POSITIVE_INFINITY) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (hand != InteractionHand.MAIN_HAND || !stack.is(ITEM) || player.isSpectator()) return InteractionResult.PASS;
			// клиент гасит клик сам: иначе следом уйдёт пакет «использовать предмет» и тут же разберёт только что собранный плот
			if (level.isClientSide() || !freshClick(player)) return InteractionResult.SUCCESS;
			if (dismantleLooked(player, level, hit.getLocation().distanceTo(player.getEyePosition()))) return InteractionResult.SUCCESS;
			assemble(player, (net.minecraft.server.level.ServerLevel) level, hit.getBlockPos(), false);
			return InteractionResult.SUCCESS;
		});
	}

	/** Блоки плота одним пакетом. */
	public static RaftBlocks blocks(RaftEntity r) {
		Map<BlockState, Integer> index = new HashMap<>();
		List<Integer> palette = new ArrayList<>();
		List<int[]> runs = new ArrayList<>();
		int last = -1;
		for (BlockState raw : r.cells()) {
			BlockState s = raw == null ? Blocks.AIR.defaultBlockState() : raw;
			Integer at = index.get(s);
			if (at == null) {
				at = palette.size();
				palette.add(Block.BLOCK_STATE_REGISTRY.getId(s));
				index.put(s, at);
			}
			if (at == last) runs.get(runs.size() - 1)[1]++;
			else {
				runs.add(new int[] { at, 1 });
				last = at;
			}
		}
		return new RaftBlocks(r.getId(), r.sx(), r.sy(), r.sz(), palette, runs);
	}

	/** Разворачивает пакет обратно в массив блоков. */
	public static BlockState[] cellsOf(RaftBlocks m) {
		BlockState[] out = new BlockState[Math.max(0, m.sx() * m.sy() * m.sz())];
		BlockState[] palette = new BlockState[m.palette().size()];
		for (int i = 0; i < palette.length; i++) {
			BlockState s = Block.BLOCK_STATE_REGISTRY.byId(m.palette().get(i));
			palette[i] = s == null ? Blocks.AIR.defaultBlockState() : s;
		}
		int at = 0;
		for (int[] run : m.runs()) {
			BlockState s = run[0] >= 0 && run[0] < palette.length ? palette[run[0]] : Blocks.AIR.defaultBlockState();
			for (int i = 0; i < run[1] && at < out.length; i++) out[at++] = s;
		}
		for (int i = at; i < out.length; i++) out[i] = Blocks.AIR.defaultBlockState();
		return out;
	}

	// ---------------------------------------------------------------- сборка

	public static boolean isTerrain(BlockState s) {
		// базальт собирается в плот: из него платформы на лавовых волнах (LavaWaveEntity)
		if (s.is(Blocks.BASALT) || s.is(Blocks.POLISHED_BASALT) || s.is(Blocks.SMOOTH_BASALT)) return false;
		// SUBSTRATE_OVERWORLD, а не DIRT: в DIRT нет травы, подзола, мицелия, мха и грязи
		return s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.NYLIUM)
				|| s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.BEDROCK) || s.is(Blocks.FARMLAND) || s.is(Blocks.DIRT_PATH);
	}

	/**
	 * Собирает из связной постройки, в которой лежит блок start, плот или воздушный шар. Вода не нужна, постройка может
	 * стоять на земле: земля в неё не входит, а только ограничивает обход. Плот на суше просто лежит, пока не окажется в воде.
	 */
	public static void assemble(Player player, net.minecraft.server.level.ServerLevel level, BlockPos start, boolean balloon) {
		String prefix = balloon ? "message.gameplaymod.balloon_" : "message.gameplaymod.raft_";
		int maxBlocks = balloon ? BalloonEntity.MAX_BLOCKS : MAX_BLOCKS;
		Set<BlockPos> found = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		BlockState first = level.getBlockState(start);
		if (first.isAir() || first.getBlock() == Blocks.WATER) return;
		found.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			BlockState s = level.getBlockState(p);
			if (isTerrain(s)) {
				player.sendOverlayMessage(Component.translatable(prefix + "terrain"));
				return;
			}
			if (level.getBlockEntity(p) != null) {
				player.sendOverlayMessage(Component.translatable(prefix + "entity"));
				return;
			}
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				BlockState ns = level.getBlockState(n);
				if (found.contains(n) || ns.isAir() || ns.getBlock() == Blocks.WATER || ns.getBlock() == Blocks.LAVA || ns.canBeReplaced()) continue;
				if (isTerrain(ns)) continue;
				if (found.size() >= maxBlocks) {
					player.sendOverlayMessage(Component.translatable(prefix + "too_big", maxBlocks));
					return;
				}
				found.add(n);
				queue.add(n);
			}
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : found) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
		BlockState[] cells = new BlockState[sx * sy * sz];
		java.util.Arrays.fill(cells, Blocks.AIR.defaultBlockState());
		for (BlockPos p : found) cells[(p.getX() - minX) + sx * ((p.getZ() - minZ) + sz * (p.getY() - minY))] = level.getBlockState(p);
		for (BlockPos p : found) {
			// на месте блока остаётся вода, если он стоял в ней
			var fluid = level.getBlockState(p).getFluidState();
			level.setBlock(p, fluid.isEmpty() ? Blocks.AIR.defaultBlockState() : fluid.createLegacyBlock(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
		}
		RaftEntity raft = balloon ? new BalloonEntity(Balloons.TYPE, level) : new RaftEntity(TYPE, level);
		raft.setCells(sx, sy, sz, cells);
		raft.setPos(minX + sx / 2.0, minY, minZ + sz / 2.0);
		if (raft instanceof BalloonEntity b) {
			b.setTargetY(minY);
			b.setHeading(Math.round(player.getYRot() / 90f));
		}
		level.addFreshEntity(raft);
		com.smeshariki.gameplay.rope.RopeEntity.adopt(raft, found);
		if (balloon) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.balloon_built", found.size()));
			return;
		}
		var field = com.smeshariki.gameplay.current.CurrentField.of(level);
		boolean current = false;
		if (field != null) {
			for (BlockPos p : found) {
				if (field.flowAt(p) != null || field.flowAt(p.below()) != null) {
					current = true;
					break;
				}
			}
		}
		player.sendOverlayMessage(Component.translatable(current ? "message.gameplaymod.raft_built" : "message.gameplaymod.raft_no_current", found.size()));
	}

	// ---------------------------------------------------------------- разборка

	/** Разбирает плот под прицелом, если он ближе maxDistance (расстояния до блока мира под прицелом). */
	public static boolean dismantleLooked(Player player, Level level, double maxDistance) {
		RaftEntity best = looked(player, level, maxDistance);
		if (best == null) return false;
		dismantle(best, (net.minecraft.server.level.ServerLevel) level);
		return true;
	}

	/** Плот или шар под прицелом не дальше maxDistance или null. */
	public static RaftEntity looked(Player player, Level level, double maxDistance) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(REACH));
		RaftEntity best = null;
		double bestDistance = Math.min(maxDistance, REACH);
		for (RaftEntity r : RaftEntity.ALL) {
			if (r.level() != level || r.isRemoved() || r.tickCount < 10 || r instanceof LogRaftEntity) continue;
			double d = r.rayDistance(eye, end);
			if (!Double.isNaN(d) && d < bestDistance) {
				bestDistance = d;
				best = r;
			}
		}
		return best;
	}

	/** Ставит блоки плота обратно в мир по ближайшей клетке; что не помещается — выпадает предметами. */
	public static void dismantle(RaftEntity raft, net.minecraft.server.level.ServerLevel level) {
		int ox = (int) Math.round(raft.originX()), oy = (int) Math.round(raft.getY()), oz = (int) Math.round(raft.originZ());
		for (int y = 0; y < raft.sy(); y++) {
			for (int z = 0; z < raft.sz(); z++) {
				for (int x = 0; x < raft.sx(); x++) {
					BlockState s = raft.cellAt(x, y, z);
					if (s.isAir()) continue;
					BlockPos pos = new BlockPos(ox + x, oy + y, oz + z);
					BlockState there = level.getBlockState(pos);
					if (there.isAir() || there.canBeReplaced() || there.getBlock() == Blocks.WATER) level.setBlock(pos, s, Block.UPDATE_ALL);
					else Block.popResource(level, pos, new ItemStack(s.getBlock()));
				}
			}
		}
		raft.discard();
	}
}
