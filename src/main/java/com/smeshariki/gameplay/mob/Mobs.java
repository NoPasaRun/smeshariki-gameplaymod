package com.smeshariki.gameplay.mob;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.smeshariki.gameplay.api.Mechanics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;

/** Мобы мода, которые не смешарики (вселиться нельзя): Бутерброд ({@link SandwichEntity}) и его морок ({@link SandwichSpell}). */
public final class Mobs {
	public static EntityType<SandwichEntity> SANDWICH;
	public static Item SANDWICH_EGG;
	/** Морок Бутерброда, см. {@link SandwichSpell}. */
	public static net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> SPELL;

	private Mobs() {
	}

	private static int place(CommandSourceStack source, String place) {
		if (!place.isEmpty() && Mechanics.place(source.getServer(), place) == null) {
			source.sendFailure(Component.translatable("message.gameplaymod.ball.no_place", place));
			return 0;
		}
		UniqueSandwich.setPlace(source.getServer(), place);
		source.sendSuccess(() -> place.isEmpty() ? Component.translatable("message.gameplaymod.sandwich.place_cleared") : Component.translatable("message.gameplaymod.sandwich.placed", place), true);
		return 1;
	}

	private static int hunt(CommandSourceStack source, ServerPlayer target) {
		if (!UniqueSandwich.hunt(source.getServer(), target)) {
			source.sendFailure(Component.translatable("message.gameplaymod.sandwich.no_hunt", target.getDisplayName()));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("message.gameplaymod.sandwich.hunt", target.getDisplayName()), true);
		return 1;
	}

	public static void init() {
		SPELL = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "sandwich_spell"), new SandwichSpell());
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "sandwich"));
		SANDWICH = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<SandwichEntity>of(SandwichEntity::new, MobCategory.CREATURE)
				.sized(0.9f, 0.9f).eyeHeight(1.12f).clientTrackingRange(10).build(key));
		FabricDefaultAttributeRegistry.register(SANDWICH, SandwichEntity.createAttributes());
		ResourceKey<Item> eggKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "sandwich_spawn_egg"));
		SANDWICH_EGG = Registry.register(BuiltInRegistries.ITEM, eggKey, new SandwichEggItem(new Item.Properties().setId(eggKey).spawnEgg(SANDWICH)
				.food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(4).saturationModifier(0.6f).alwaysEdible().build())));
		UniqueSandwich.init();
		// /sandwich info — где Бутерброд и что делает; /sandwich debug — то же над панелью раз в полсекунды; /sandwich tp — перенестись к нему
		// /sandwich place [локация] — локация Бутерброда (без имени — снять: он никого не заражает); /sandwich hunt [игрок] — выбрать цель
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> dispatcher.register(Commands.literal("sandwich")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("place")
						.executes(c -> place(c.getSource(), ""))
						.then(Commands.argument("place", StringArgumentType.greedyString())
								.suggests((c, builder) -> SharedSuggestionProvider.suggest(Mechanics.placeNames(c.getSource().getServer()), builder))
								.executes(c -> place(c.getSource(), StringArgumentType.getString(c, "place")))))
				.then(Commands.literal("info").executes(c -> {
					for (String line : UniqueSandwich.describe(c.getSource().getServer())) c.getSource().sendSuccess(() -> Component.literal(line), false);
					return 1;
				}))
				.then(Commands.literal("debug").executes(c -> {
					boolean on = UniqueSandwich.toggleDebug(c.getSource().getPlayerOrException());
					c.getSource().sendSuccess(() -> Component.literal(on ? "Состояние Бутерброда показывается над панелью" : "Показ состояния Бутерброда выключен"), false);
					return 1;
				}))
				.then(Commands.literal("tp").executes(c -> {
					SandwichEntity sandwich = UniqueSandwich.find(c.getSource().getServer());
					if (sandwich == null) {
						c.getSource().sendFailure(Component.literal(UniqueSandwich.describe(c.getSource().getServer()).get(0)));
						return 0;
					}
					ServerPlayer player = c.getSource().getPlayerOrException();
					player.teleportTo((net.minecraft.server.level.ServerLevel) sandwich.level(), sandwich.getX(), sandwich.getY(), sandwich.getZ() + 3, java.util.Set.of(), 180f, 0f, false);
					return 1;
				}))
				.then(Commands.literal("hunt")
						.executes(c -> hunt(c.getSource(), c.getSource().getPlayerOrException()))
						.then(Commands.argument("target", EntityArgument.player()).executes(c -> hunt(c.getSource(), EntityArgument.getPlayer(c, "target")))))));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("spawn_eggs"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> output.accept(SANDWICH_EGG));
	}
}
