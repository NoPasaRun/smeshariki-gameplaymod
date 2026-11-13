package com.smeshariki.gameplay.character;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.api.Calls;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Умения вселённых персонажей по клавише G (пакет {@link SkillUse}). Совунья делает из неорганики еду, Лосяш рисует схемы на бумаге
 * ({@link Schemes}), Бараш замедляет время ({@link TimeSlow}), Нюша зовёт смешарика и говорит с ним или приказывает ({@link Calls}).
 * Всё решает сервер; клиент только открывает окна и шлёт выбор.
 */
public final class Skills {
	/** Слова в названии предмета, по которым он считается неорганикой (еду, инструменты и броню отсекает {@link #inorganic}). */
	private static final List<String> MINERAL = List.of("stone", "cobble", "deepslate", "ingot", "nugget", "sand", "gravel", "clay", "brick", "glass", "quartz", "diamond", "emerald",
			"lapis", "amethyst", "raw_", "iron", "gold", "copper", "netherite", "obsidian", "basalt", "andesite", "diorite", "granite", "tuff", "terracotta", "concrete", "coal",
			"flint", "redstone", "prismarine", "netherrack", "blackstone", "calcite", "dripstone", "glowstone", "end_stone", "purpur", "slime_ball", "bone_meal");
	private static final int COOKING_COOLDOWN = 10;
	private static final Map<UUID, Integer> COOLDOWN = new HashMap<>();

	/** «Позвать к себе»: перенос смешарика к вызвавшей Нюше. Выполняется отдельно (смешарика может ещё не быть в мире). */
	private static final Calls.Action CALL = new Calls.Action() {
		@Override
		public Component label() {
			return Component.translatable("gui.gameplaymod.call.action.call");
		}

		@Override
		public @Nullable Component run(ServerPlayer caller, CharacterEntity target, String option) {
			return null;
		}

		@Override
		public boolean needsPresent() {
			return false;
		}
	};

	/** Кого Нюша может позвать: уровни (LevelMod) не пускают к занятому в другом уровне смешарику. */
	public interface CallFilter {
		boolean allows(ServerPlayer caller, EntityType<CharacterEntity> target);
	}

	private static CallFilter callFilter = (caller, target) -> true;

	public static void setCallFilter(CallFilter filter) {
		callFilter = filter;
	}

	private Skills() {
	}

	public static void init() {
		Schemes.init();
		TimeSlow.init();
		Calls.register("call", CALL);
		PayloadTypeRegistry.serverboundPlay().register(SkillUse.TYPE, SkillUse.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SkillUse.TYPE, (msg, ctx) -> use(ctx.player(), msg.arg()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> COOLDOWN.remove(handler.getPlayer().getUUID()));
		// у рецептов мода нет достижений, которые их открывают, поэтому без этого их нет в книге рецептов верстака
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> unlockRecipes(handler.getPlayer()));
	}

	/** Открыть игроку все рецепты мода (уже открытые ваниль пропускает). */
	public static void unlockRecipes(ServerPlayer player) {
		var mine = player.level().getServer().getRecipeManager().getRecipes().stream()
				.filter(r -> r.id().identifier().getNamespace().equals(com.smeshariki.gameplay.GameplayMod.NAMESPACE)).toList();
		if (!mine.isEmpty()) player.awardRecipes(mine);
	}

	/** У персонажа есть умение по G. */
	public static boolean hasSkill(EntityType<?> type) {
		return type == Characters.SOVUNYA || type == Characters.LOSYASH || type == Characters.BARASH || type == Characters.NYUSHA;
	}

	private static void use(ServerPlayer player, String arg) {
		if (!(player.getVehicle() instanceof CharacterEntity c)) return;
		var type = c.getType();
		if (type == Characters.SOVUNYA) cook(player, c);
		else if (type == Characters.LOSYASH && arg.startsWith("scheme:")) Schemes.draw(player, c, arg.substring(7));
		else if (type == Characters.BARASH) TimeSlow.cast(player, c);
		else if (type == Characters.NYUSHA && arg.startsWith("call:")) call(player, c, arg.substring(5));
	}

	// ---------------------------------------------------------------- Совунья

	/** Неорганика: не еда, не прочный предмет (инструмент, броня) и название говорит о камне, металле, песке и т. п. */
	public static boolean inorganic(ItemStack stack) {
		if (stack.isEmpty() || stack.has(DataComponents.FOOD) || stack.isDamageableItem()) return false;
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		for (String word : MINERAL) if (path.contains(word)) return true;
		return false;
	}

	/** Из неорганики в руке — любимая еда случайного другого смешарика (не самой Совуньи). */
	private static void cook(ServerPlayer player, CharacterEntity sovunya) {
		int now = player.level().getServer().getTickCount();
		if (COOLDOWN.getOrDefault(player.getUUID(), -100) + COOKING_COOLDOWN > now) return;
		ItemStack held = player.getMainHandItem();
		if (!inorganic(held)) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.sovunya.need_mineral"));
			return;
		}
		List<EntityType<CharacterEntity>> others = Characters.all().stream().filter(t -> t != Characters.SOVUNYA).toList();
		EntityType<CharacterEntity> forWhom = others.get(sovunya.getRandom().nextInt(others.size()));
		Item food = CharacterEntity.favoriteOf(forWhom);
		COOLDOWN.put(player.getUUID(), now);
		if (!player.isCreative()) held.shrink(1);
		ItemStack made = new ItemStack(food);
		if (!player.getInventory().add(made)) player.drop(made, false);
		ServerLevel level = (ServerLevel) sovunya.level();
		level.playSound(null, sovunya.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 1f, 1.3f);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, sovunya.getX(), sovunya.getY() + 1.2, sovunya.getZ(), 8, 0.4, 0.4, 0.4, 0.02);
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.sovunya.cooked", new ItemStack(food).getHoverName(), Characters.alias(forWhom)));
	}

	// ---------------------------------------------------------------- Нюша

	/** {@code call:<тип>|<действие>|<вариант>}: позвать (или поговорить, приказать) смешарика данного типа. */
	private static void call(ServerPlayer player, CharacterEntity nyusha, String arg) {
		String[] parts = arg.split("\\|", -1);
		if (parts.length < 2) return;
		Identifier id = Identifier.tryParse(parts[0]);
		EntityType<?> raw = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(id);
		if (raw == null || raw == Characters.NYUSHA || !Characters.all().contains(raw)) return;
		@SuppressWarnings("unchecked")
		EntityType<CharacterEntity> type = (EntityType<CharacterEntity>) raw;
		Calls.Action action = Calls.get(parts[1]);
		if (action == null) return;
		if (!callFilter.allows(player, type)) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.call.level_busy", Characters.alias(type)));
			return;
		}
		String option = parts.length > 2 ? parts[2] : "";
		MinecraftServer server = player.level().getServer();
		CharacterEntity found = Singletons.find(server, type);
		if (found != null && found.isVehicle()) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.call.busy", Characters.alias(type)));
			return;
		}
		if (action == CALL) {
			callTo(player, nyusha, type, found);
			return;
		}
		if (found == null) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.call.absent", Characters.alias(type)));
			return;
		}
		Component refusal = action.run(player, found, option);
		if (refusal != null) player.sendOverlayMessage(refusal);
	}

	/** Смешарик появляется (или переносится) перед Нюшей. Из незагруженного чанка достаётся, ушедшего с игроком достать нельзя. */
	private static void callTo(ServerPlayer player, CharacterEntity nyusha, EntityType<CharacterEntity> type, @Nullable CharacterEntity found) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = (ServerLevel) nyusha.level();
		if (found == null && Singletons.busy(server, type, player)) {
			player.sendOverlayMessage(Component.translatable("message.gameplaymod.call.busy", Characters.alias(type)));
			return;
		}
		Vec3 look = nyusha.getLookAngle();
		Vec3 spot = nyusha.position().add(look.x * 1.6, 0, look.z * 1.6);
		if (!level.noCollision(type.getDimensions().makeBoundingBox(spot))) spot = nyusha.position().add(-look.z * 1.4, 0, look.x * 1.4);
		if (!level.noCollision(type.getDimensions().makeBoundingBox(spot))) spot = nyusha.position();
		Vec3 where = spot;
		float yaw = nyusha.getYRot() + 180f;
		java.util.function.Consumer<CharacterEntity> arrive = c -> {
			if (c == null) Singletons.create(level, type, where, yaw);
			else if (!c.isVehicle()) Singletons.move(c, level, where, yaw);
		};
		if (found != null) arrive.accept(found);
		else Singletons.fetch(server, type, arrive::accept);
		level.playSound(null, nyusha.blockPosition(), SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 1f, 1.5f);
		player.sendOverlayMessage(Component.translatable("message.gameplaymod.call.called", Characters.alias(type)));
	}
}
