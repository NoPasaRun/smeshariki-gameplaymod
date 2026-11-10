package com.smeshariki.gameplay.slide;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Регистрация горки: скаты четырёх наклонов из льда (старые имена, ими построены миры), камня и земли, а также кол и тёмный слой. */
public final class Slides {
	public static Block BLOCK;
	public static Item ITEM;
	public static Block GENTLE, STEEP, SHALLOW, FIELD, SPIKE, VEIL;
	private static final SoundType ICE = SoundType.GLASS;

	private Slides() {
	}

	public static void init() {
		BLOCK = register("slide", SlideBlock::new, ICE, 0.5f);
		ITEM = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "slide"));
		GENTLE = register("slide_gentle", GentleSlideBlock::new, ICE, 0.5f);
		STEEP = register("slide_steep", SteepSlideBlock::new, ICE, 0.5f);
		SHALLOW = register("slide_shallow", ShallowSlideBlock::new, ICE, 0.5f);
		FIELD = register("slide_field", FieldSlideBlock::new, ICE, 0.5f);
		register("slide_field_west", WestFieldSlideBlock::new, ICE, 0.5f);
		for (String shape : new String[] {"slide", "slide_gentle", "slide_steep", "slide_shallow", "slide_field", "slide_field_west"}) {
			java.util.function.Function<BlockBehaviour.Properties, Block> factory = switch (shape) {
				case "slide_gentle" -> GentleSlideBlock::new;
				case "slide_steep" -> SteepSlideBlock::new;
				case "slide_shallow" -> ShallowSlideBlock::new;
				case "slide_field" -> FieldSlideBlock::new;
				case "slide_field_west" -> WestFieldSlideBlock::new;
				default -> SlideBlock::new;
			};
			register(shape + "_stone", factory, SoundType.STONE, 1.5f);
			register(shape + "_dirt", factory, SoundType.GRAVEL, 0.5f);
		}
		// исходный кол был 2 блока высотой и полблока шириной: id ice_spike уже стоит в мирах, поэтому это он, втрое больше
		SPIKE = register("ice_spike", p -> new SpikeBlock(p, 3), ICE, 0.5f);
		register("stone_spike", p -> new SpikeBlock(p, 3), SoundType.STONE, 1.5f);
		register("small_ice_spike", p -> new SpikeBlock(p, 1), ICE, 0.5f);
		register("small_stone_spike", p -> new SpikeBlock(p, 1), SoundType.STONE, 1.5f);
		register("big_ice_spike", p -> new SpikeBlock(p, 5), ICE, 0.5f);
		register("big_stone_spike", p -> new SpikeBlock(p, 5), SoundType.STONE, 1.5f);
		register("ice_column", ColumnBlock::new, ICE, 0.5f);
		register("stone_column", ColumnBlock::new, SoundType.STONE, 1.5f);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(SpikeBlock::tickPlayers);
		PayloadTypeRegistry.serverboundPlay().register(SlideAdjust.TYPE, SlideAdjust.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SlideAdjust.TYPE, (msg, ctx) -> adjust(ctx.player(), msg));
		VEIL = register("dark_veil", DarkVeilBlock::new, ICE, 0.5f);
		// скользящий не приземляется, поэтому сервер сам сбрасывает ему высоту падения
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
				net.minecraft.world.entity.Entity e = p.getVehicle() != null ? p.getVehicle() : p;
				net.minecraft.core.BlockPos at = e.blockPosition();
				for (int dy = 1; dy >= -2; dy--)
					if (e.level().getBlockState(at.above(dy)).getBlock() instanceof SlideBlock) {
						e.resetFallDistance();
						p.resetFallDistance();
						break;
					}
			}
		});
	}

	/** ПКМ по скату. Сервер не верит клиенту: только творческий режим, пустая рука, блок в пределах досягаемости. */
	private static void adjust(net.minecraft.server.level.ServerPlayer player, SlideAdjust msg) {
		BlockState state = player.level().getBlockState(msg.pos());
		if (!player.isCreative() || !player.getMainHandItem().isEmpty() || player.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(msg.pos())) > player.blockInteractionRange() + 1.5 || !(state.getBlock() instanceof FieldSlideBlock)) return;
		FieldSlideBlock.Adjusted next = FieldSlideBlock.adjusted(state, Math.clamp(msg.fx(), 0f, 1f), Math.clamp(msg.fz(), 0f, 1f), msg.lower());
		BlockPos target = next == null ? msg.pos() : msg.pos().above(next.shift());
		// за границей клетки блок переезжает на соседнюю, но чужой блок не затирает
		if (next == null || next.shift() != 0 && !player.level().getBlockState(target).canBeReplaced()) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.slide_field_limit"));
			return;
		}
		if (next.shift() != 0) player.level().setBlock(msg.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		player.level().setBlock(target, next.state(), Block.UPDATE_ALL);
		int[] c = next.corners();
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.slide_field", c[0], c[1], c[2], c[3]));
	}

	private static Block register(String name, java.util.function.Function<BlockBehaviour.Properties, Block> factory, SoundType sound, float strength) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(BlockBehaviour.Properties.of().setId(blockKey)
				.strength(strength).friction(0.98f).sound(sound).noOcclusion()));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, name));
		Item item = Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("building_blocks"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(item));
		return block;
	}
}
