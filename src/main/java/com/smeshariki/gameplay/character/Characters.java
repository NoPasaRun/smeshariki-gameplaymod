package com.smeshariki.gameplay.character;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.smeshariki.gameplay.GameplayMod;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Регистрация персонажей-шариков и их яиц призыва. Идентификатор Кроша остался «krosh_tailed» (у него есть хвост-цветок),
 * чтобы призванные раньше существа не пропали.
 */
public final class Characters {
	public static EntityType<CharacterEntity> KROSH, YOZHIK, KARYCH, LOSYASH, NYUSHA, BARASH, SOVUNYA;
	/** Все персонажи в порядке регистрации: новый появляется в окнах и уровнях сам. */
	private static final List<EntityType<CharacterEntity>> ALL = new ArrayList<>();
	public static MenuType<CharacterMenu> GEAR_MENU;
	/** Очки: надеваются в слот глаз; дают вселённому нормальное зрение (Ёжику) или «рыбий глаз» (остальным), трескаются от ударов. */
	public static Item GLASSES;
	public static Item KROSH_EGG, YOZHIK_EGG, KARYCH_EGG, LOSYASH_EGG, NYUSHA_EGG, BARASH_EGG, SOVUNYA_EGG;

	/** Очки выдерживают столько единиц урона по персонажу, потом разбиваются. */
	private static final int GLASSES_DURABILITY = 30;

	/** Во сколько раз увеличены модели (как `CharacterModel.BODY_SCALE`; здесь нужен для высоты глаз). */
	private static final float SCALE = 1.8f / 1.3f;

	private Characters() {
	}

	public static List<EntityType<CharacterEntity>> all() {
		return Collections.unmodifiableList(ALL);
	}

	/** Основной цвет персонажа (RGB), по его текстуре: цвет реплик в чате. Карыч чуть осветлён, чтобы читался на тёмном. */
	private static final java.util.Map<EntityType<?>, Integer> COLORS = new java.util.HashMap<>();

	public static int color(EntityType<?> type) {
		return COLORS.getOrDefault(type, 0xFFFFFF);
	}

	/** Псевдоним персонажа (его реплики подписываются им), цветом персонажа; запись {@code alias.<мод>.<имя>} в языковых файлах. */
	public static Component alias(EntityType<?> type) {
		Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		int color = color(type);
		return Component.translatable("alias." + key.getNamespace() + "." + key.getPath()).withStyle(style -> style.withColor(color));
	}

	/** Псевдоним по идентификатору типа сущности; не смешарик — просто название типа. */
	public static Component alias(Identifier id) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
		return COLORS.containsKey(type) ? alias(type) : type.getDescription();
	}

	public static void init() {
		// вселённый игрок ванильного урона не получает: урон идёт персонажу
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
				!(entity instanceof net.minecraft.world.entity.player.Player && entity.getVehicle() instanceof CharacterEntity)
						|| source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY));
		// хитбокс как у игрока (0,6×1,8); высота глаз — уровень лица с учётом роста модели (к ней садится вселённый игрок)
		KROSH = entity("krosh_tailed", 0.62f * SCALE, 0x5CBDD9);
		YOZHIK = entity("yozhik", 0.59f * SCALE, 0xC85378);
		KARYCH = entity("karych", (0.84f - 0.075f) * SCALE, 0x2E8FD6);
		LOSYASH = entity("losyash", (0.68f - 0.063f + 0.05f) * SCALE, 0xF1A300);
		NYUSHA = entity("nyusha", 0.60f * SCALE, 0xF2849E);
		BARASH = entity("barash", 0.637f * SCALE, 0xB68FCF);
		SOVUNYA = entity("sovunya", 0.64f * SCALE, 0x9B6FC0);
		KROSH_EGG = egg("krosh_tailed_spawn_egg", KROSH);
		YOZHIK_EGG = egg("yozhik_spawn_egg", YOZHIK);
		KARYCH_EGG = egg("karych_spawn_egg", KARYCH);
		LOSYASH_EGG = egg("losyash_spawn_egg", LOSYASH);
		NYUSHA_EGG = egg("nyusha_spawn_egg", NYUSHA);
		BARASH_EGG = egg("barash_spawn_egg", BARASH);
		SOVUNYA_EGG = egg("sovunya_spawn_egg", SOVUNYA);
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("spawn_eggs"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> {
			output.accept(KROSH_EGG);
			output.accept(YOZHIK_EGG);
			output.accept(KARYCH_EGG);
			output.accept(LOSYASH_EGG);
			output.accept(NYUSHA_EGG);
			output.accept(BARASH_EGG);
			output.accept(SOVUNYA_EGG);
		});
		// вселённый персонаж ставит блоки как обычно; ломает слабее обычного (CharacterEntity.BREAK_SPEED_FACTOR, PlayerMixin)
		ResourceKey<Item> glassesKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "glasses"));
		GLASSES = Registry.register(BuiltInRegistries.ITEM, glassesKey, new AccessoryItem(new Item.Properties().setId(glassesKey).durability(GLASSES_DURABILITY), CharacterEntity.GEAR_EYES));
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"))).register(output -> output.accept(GLASSES));
		GEAR_MENU = Registry.register(BuiltInRegistries.MENU, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "character_gear"), new MenuType<>(CharacterMenu::new, FeatureFlags.VANILLA_SET));
		Singletons.init();
		Possession.init();
		PayloadTypeRegistry.serverboundPlay().register(OpenCharacterGear.TYPE, OpenCharacterGear.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenCharacterGear.TYPE, (msg, ctx) -> {
			if (ctx.player().getVehicle() instanceof CharacterEntity character) {
				ctx.player().openMenu(new SimpleMenuProvider((id, inventory, p) -> new CharacterMenu(id, inventory, character.gear), Component.translatable("gui.gameplaymod.gear")));
			}
		});
		PayloadTypeRegistry.serverboundPlay().register(CharacterDismiss.TYPE, CharacterDismiss.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(CharacterDismiss.TYPE, (msg, ctx) -> {
			ServerPlayer player = ctx.player();
			// смешарик в мире один: выход из него в творческом только спешивает, тело остаётся
			if (!player.isCreative() || !(player.getVehicle() instanceof CharacterEntity)) return;
			player.stopRiding();
		});
		PayloadTypeRegistry.serverboundPlay().register(CharacterAbility.TYPE, CharacterAbility.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(CharacterAbility.TYPE, (msg, ctx) -> {
			if (ctx.player().getVehicle() instanceof CharacterEntity character && character.hasAbility()) character.setAbilityActive(msg.active());
		});
		// если игра закрылась, пока игрок сидел в персонаже, невидимость от вселения не должна остаться навсегда
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (CharacterEntity.hasPossessionEffect(player) && !(player.getVehicle() instanceof CharacterEntity)) player.removeEffect(MobEffects.INVISIBILITY);
		});
	}

	private static EntityType<CharacterEntity> entity(String name, float eyeHeight, int color) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, name));
		EntityType<CharacterEntity> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.<CharacterEntity>of(CharacterEntity::new, MobCategory.CREATURE)
				.sized(0.6f, 1.8f).eyeHeight(eyeHeight).clientTrackingRange(10).build(key));
		FabricDefaultAttributeRegistry.register(type, CharacterEntity.createAttributes());
		ALL.add(type);
		COLORS.put(type, color);
		return type;
	}

	private static Item egg(String name, EntityType<CharacterEntity> type) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, name));
		return Registry.register(BuiltInRegistries.ITEM, key, new CharacterEggItem(type, new Item.Properties().setId(key).spawnEgg(type)));
	}
}
