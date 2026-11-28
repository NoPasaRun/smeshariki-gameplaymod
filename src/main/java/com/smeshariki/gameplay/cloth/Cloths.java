package com.smeshariki.gameplay.cloth;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.raft.Rafts;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Ткань: предмет «Подвес ткани» и сущность {@link ClothEntity}. ПКМ подвесом по блоку из тега {@code gameplaymod:cloth}
 * (ширма BuildMod) собирает все соединённые с ним такие блоки той же плоскости в полотнище; ПКМ по полотнищу разбирает его.
 * Блоки ткани задаёт тег, а не код: GameplayMod не зависит от BuildMod.
 */
public final class Cloths {
	public static EntityType<ClothEntity> TYPE;
	public static Item ITEM;
	public static final TagKey<Block> CLOTH = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "cloth"));
	/** Клиент ставит проверку «в руке подвес или верёвка»: только тогда прицел ловит ткань. */
	public static volatile BooleanSupplier pickableOnClient = () -> true;

	private Cloths() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "cloth"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<ClothEntity>of(ClothEntity::new, MobCategory.MISC)
				.sized(0.5f, 0.5f).clientTrackingRange(10).updateInterval(20).fireImmune().noSummon().build(typeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "cloth_hanger"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new Item(new Item.Properties().setId(itemKey).stacksTo(1)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(ITEM));

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).is(ITEM) || player.isSpectator()) return InteractionResult.PASS;
			if (level.isClientSide() || !Rafts.freshClick(player)) return InteractionResult.SUCCESS;
			assemble(player, (ServerLevel) level, hit.getBlockPos());
			return InteractionResult.SUCCESS;
		});
	}

	/** Собирает полотнище из блока start и всех соединённых с ним блоков ткани той же плоскости. */
	public static boolean assemble(Player player, ServerLevel level, BlockPos start) {
		BlockState first = level.getBlockState(start);
		if (!first.is(CLOTH)) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.cloth_not_screen"));
			return false;
		}
		// лежачие ширмы собираются в лежачее полотнище (слой по высоте); стоячие — в плоскости своей оси: смотрит на север или юг — вдоль X
		boolean flat = flat(first);
		boolean alongX = flat || facing(first).getAxis() == Direction.Axis.Z;
		Direction step = alongX ? Direction.EAST : Direction.SOUTH;
		Set<BlockPos> found = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		found.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			for (BlockPos n : flat ? new BlockPos[] {p.east(), p.west(), p.south(), p.north()} : new BlockPos[] {p.relative(step), p.relative(step.getOpposite()), p.above(), p.below()}) {
				if (found.contains(n)) continue;
				BlockState s = level.getBlockState(n);
				if (!s.is(CLOTH) || flat(s) != flat || (!flat && (facing(s).getAxis() == Direction.Axis.Z) != alongX)) continue;
				found.add(n);
				if (found.size() > ClothEntity.MAX_CELLS) {
					player.sendOverlayMessage(Component.translatable("message.gameplaymod.cloth_too_big", ClothEntity.MAX_CELLS, ClothEntity.MAX_SIDE));
					return false;
				}
				queue.add(n);
			}
		}
		// строки: у стоячего — сверху вниз по высоте, у лежачего — к югу
		int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE, minB = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;
		for (BlockPos p : found) {
			int a = alongX ? p.getX() : p.getZ(), b = flat ? p.getZ() : p.getY();
			minA = Math.min(minA, a);
			maxA = Math.max(maxA, a);
			minB = Math.min(minB, b);
			maxB = Math.max(maxB, b);
		}
		int w = maxA - minA + 1, h = maxB - minB + 1;
		if (w > ClothEntity.MAX_SIDE || h > ClothEntity.MAX_SIDE) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.cloth_too_big", ClothEntity.MAX_CELLS, ClothEntity.MAX_SIDE));
			return false;
		}
		if (w * h != found.size()) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.cloth_not_rect"));
			return false;
		}
		BlockPos topLeft = flat ? new BlockPos(minA, start.getY(), minB) : alongX ? new BlockPos(minA, maxB, start.getZ()) : new BlockPos(start.getX(), maxB, minA);
		BlockState[] cells = new BlockState[w * h];
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				BlockPos p = flat ? topLeft.offset(c, 0, r) : topLeft.relative(step, c).below(r);
				cells[c + r * w] = level.getBlockState(p);
			}
		}
		for (BlockPos p : found) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		ClothEntity cloth = new ClothEntity(TYPE, level);
		cloth.setup(topLeft, alongX, flat, w, h, cells);
		level.addFreshEntity(cloth);
		// верёвки, висевшие на ширмах, переходят на ткань (иначе ломаются и отцепляются вместе с пропавшими блоками)
		com.smeshariki.gameplay.rope.RopeEntity.adopt(cloth, found);
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.cloth_assembled", w, h));
		return true;
	}

	/** Ширма лежит плашмя (свойство {@code flat} блока; у блока без него — нет). */
	private static boolean flat(BlockState state) {
		for (var property : state.getProperties()) {
			if (property.getName().equals("flat") && state.getValue(property) instanceof Boolean value) return value;
		}
		return false;
	}

	private static Direction facing(BlockState state) {
		return state.getOptionalValue(BlockStateProperties.HORIZONTAL_FACING).orElse(Direction.NORTH);
	}
}
