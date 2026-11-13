package com.smeshariki.gameplay.character;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Схемы Лосяша. Он рисует на чистой бумаге или пустой карте схему одной из сущностей мода ({@link #NAMES}); предмет схемы — «scheme_&lt;имя&gt;».
 * Сами сущности крафтятся только со своей схемой в рецепте (данные {@code recipe/*.json}); при крафте схема расходуется.
 */
public final class Schemes {
	/** Что можно нарисовать: по имени предмета-результата в {@code gameplaymod}. */
	public static final List<String> NAMES = List.of("tarzan_rope", "calm_rope", "pull_rope", "raft_binder", "balloon_burner", "balloon_lift_rope", "balloon_helm_rope",
			"current_marker", "wind_marker", "smoke_marker", "launcher_marker");

	private static final Map<String, Item> ITEMS = new LinkedHashMap<>();

	private Schemes() {
	}

	public static void init() {
		for (String name : NAMES) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "scheme_" + name));
			ITEMS.put(name, Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key))));
		}
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities")))
				.register(output -> ITEMS.values().forEach(output::accept));
	}

	/** Бумага или пустая карта в руке — на чём рисуют. */
	private static boolean blank(ItemStack stack) {
		return stack.is(Items.PAPER) || stack.is(Items.MAP);
	}

	/** Лосяш (игрок в нём) рисует схему на бумаге в руке. */
	public static void draw(ServerPlayer player, CharacterEntity losyash, String name) {
		Item scheme = ITEMS.get(name);
		if (scheme == null) return;
		ItemStack held = player.getMainHandItem();
		if (!blank(held)) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.scheme.need_paper"));
			return;
		}
		if (!player.isCreative()) held.shrink(1);
		ItemStack made = new ItemStack(scheme);
		if (!player.getInventory().add(made)) player.drop(made, false);
		losyash.level().playSound(null, losyash.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.scheme.drawn", Component.translatable("item.gameplaymod.scheme_" + name)));
	}

	/** Подпись схемы для окна выбора. */
	public static Component label(String name) {
		return Component.translatable("item.gameplaymod.scheme_" + name);
	}

	public static boolean isPlayerHoldingBlank(Player player) {
		return blank(player.getMainHandItem());
	}
}
