package com.smeshariki.gameplay.rope;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.raft.RaftEntity;
import com.smeshariki.gameplay.raft.Rafts;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Регистрация тарзанки: предмет, сущность свободного конца и действия игрока с зажатым в руке концом. */
public final class Ropes {
	public static EntityType<RopeEntity> TYPE;
	public static Item ITEM;
	/** Тарзанка без размаха ({@link CalmRopeEntity}). */
	public static EntityType<CalmRopeEntity> CALM_TYPE;
	public static Item CALM_ITEM;
	/** Верёвка ({@link PullRopeEntity}): короткая, для подтягивания. */
	public static EntityType<PullRopeEntity> PULL_TYPE;
	public static Item PULL_ITEM;

	private Ropes() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "tarzan_rope"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<RopeEntity>of(RopeEntity::new, MobCategory.MISC)
				.sized(0.4f, 0.4f).clientTrackingRange(10).updateInterval(1).fireImmune().noSummon().build(typeKey));

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "tarzan_rope"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new RopeItem(new Item.Properties().setId(itemKey).stacksTo(16), l -> new RopeEntity(TYPE, l)));
		ResourceKey<EntityType<?>> calmKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "calm_rope"));
		CALM_TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, calmKey, EntityType.Builder.<CalmRopeEntity>of(CalmRopeEntity::new, MobCategory.MISC)
				.sized(0.4f, 0.4f).clientTrackingRange(10).updateInterval(1).fireImmune().noSummon().build(calmKey));
		ResourceKey<Item> calmItemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "calm_rope"));
		CALM_ITEM = Registry.register(BuiltInRegistries.ITEM, calmItemKey, new RopeItem(new Item.Properties().setId(calmItemKey).stacksTo(16), l -> new CalmRopeEntity(CALM_TYPE, l)));
		ResourceKey<EntityType<?>> pullKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "pull_rope"));
		PULL_TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, pullKey, EntityType.Builder.<PullRopeEntity>of(PullRopeEntity::new, MobCategory.MISC)
				.sized(0.4f, 0.4f).clientTrackingRange(10).updateInterval(1).fireImmune().noSummon().build(pullKey));
		ResourceKey<Item> pullItemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "pull_rope"));
		PULL_ITEM = Registry.register(BuiltInRegistries.ITEM, pullItemKey, new RopeItem(new Item.Properties().setId(pullItemKey).stacksTo(16), l -> new PullRopeEntity(PULL_TYPE, l)));
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> {
			output.accept(ITEM);
			output.accept(CALM_ITEM);
			output.accept(PULL_ITEM);
		});

		ServerTickEvents.END_SERVER_TICK.register(Ropes::autoGrab);

		PayloadTypeRegistry.serverboundPlay().register(AdjustLength.TYPE, AdjustLength.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(AdjustLength.TYPE, (msg, ctx) -> {
			RopeEntity rope = RopeEntity.heldBy(ctx.player());
			if (rope == null || Float.isNaN(msg.delta())) return;
			if (!ctx.player().getAbilities().instabuild) {
				// в выживании и приключении длину тарзанок не менять вовсе
				if (lengthFixed(rope)) {
					ctx.player().sendOverlayMessage(Component.translatable("message.gameplaymod.rope_length_fixed"));
					return;
				}
				// у остальных верёвок с креплением на блоке не удлинить, только укоротить
				if (msg.delta() > 0 && rope.anchoredToBlock()) {
					ctx.player().sendOverlayMessage(Component.translatable("message.gameplaymod.rope_length_locked"));
					return;
				}
			}
			float length = rope.adjustLength(Math.clamp(msg.delta(), -AdjustLength.MAX_STEP, AdjustLength.MAX_STEP));
			ctx.player().sendOverlayMessage(Component.translatable("message.gameplaymod.rope_length", String.format("%.1f", length)));
		});

		PayloadTypeRegistry.serverboundPlay().register(RopeAction.TYPE, RopeAction.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RopeAction.TYPE, (msg, ctx) -> {
			Player player = ctx.player();
			if (player.isSpectator()) return;
			if (msg.action() == RopeAction.ATTACH_RAFT) {
				RopeEntity held = RopeEntity.heldBy(player);
				RaftEntity raft = Rafts.looked(player, player.level(), Double.POSITIVE_INFINITY);
				if (!Rafts.freshClick(player)) return;
				if (held != null && raft != null && !held.attachTo(raft, lookPoint(player, raft))) player.sendOverlayMessage(Component.translatable("message.gameplaymod.rope_too_far"));
				return;
			}
			if (msg.action() == RopeAction.RELEASE) {
				RopeEntity held = RopeEntity.heldBy(player);
				if (held != null) held.release();
				return;
			}
			if (!(player.level().getEntity(msg.entityId()) instanceof RopeEntity rope)) return;
			// из рук игрока не отнять; у персонажа без игрока (напарника) можно только перехватить
			if (rope.mode() == RopeEntity.HELD && (msg.action() != RopeAction.GRAB || player.level().getEntity(rope.holderId()) instanceof Player)) return;
			Vec3 eye = player.getEyePosition();
			if (msg.action() == RopeAction.BREAK) {
				if (rope.anchor().distanceTo(eye) <= RopeAction.MAX_DISTANCE) rope.breakBy(player);
			} else if (msg.action() == RopeAction.GRAB && RopeEntity.heldBy(player) == null && rope.distanceToRope(eye) <= RopeAction.MAX_DISTANCE) {
				rope.grabBy(player);
			}
		});

		// конец в руке: Ctrl+ПКМ по блоку — зацепить его там (ПКМ без Ctrl отпускает)
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
			RopeEntity rope = RopeEntity.heldBy(player);
			if (rope != null && rope.takesItem(player.getItemInHand(hand))) {
				if (!level.isClientSide() && Rafts.freshClick(player)) rope.giveItem(player, player.getItemInHand(hand));
				return InteractionResult.SUCCESS;
			}
			double distance = hit.getLocation().distanceTo(player.getEyePosition());
			// зажатый ПКМ не должен сразу зацепить конец там же, где верёвку только что закрепили
			if (!level.isClientSide() && (rope != null || player.getItemInHand(hand).getItem() instanceof RopeItem) && !Rafts.freshClick(player)) return InteractionResult.SUCCESS;
			if (rope == null) return !level.isClientSide() && anchorOnRaft(player, level, hand, distance) ? InteractionResult.SUCCESS : InteractionResult.PASS;
			// ПКМ отпускает верёвку (пакет шлёт клиент), поэтому конец зацепляет только Ctrl+ПКМ; без Ctrl клик по блоку съеден и ничего не делает
			if (!level.isClientSide() && !holdsCtrl(player)) return InteractionResult.SUCCESS;
			// плот ближе блока: конец цепляет за плот клиентский тик пакетом, блок позади не трогаем
			if (Rafts.looked(player, level, distance) != null) return level.isClientSide() ? InteractionResult.FAIL : InteractionResult.SUCCESS;
			if (!level.isClientSide()) {
				Vec3 point = hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()).scale(0.08));
				if (!rope.attachTo(hit.getBlockPos(), point)) player.sendOverlayMessage(Component.translatable("message.gameplaymod.rope_too_far"));
			}
			return InteractionResult.SUCCESS;
		});
		// конец в руке, ПКМ по воздуху предметом, который верёвка берёт (топливо стропы шара); клиент пропускает, пакет уйдёт на сервер
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
			RopeEntity rope = RopeEntity.heldBy(player);
			if (rope == null || !rope.takesItem(player.getItemInHand(hand))) return InteractionResult.PASS;
			if (Rafts.freshClick(player)) rope.giveItem(player, player.getItemInHand(hand));
			return InteractionResult.SUCCESS;
		});
		// тарзанка в руке, ПКМ по существу (кроме себя, своего персонажа, верёвок и плотов): крепление на нём, конец в руке
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || player.isSpectator()) return InteractionResult.PASS;
			ItemStack stack = player.getItemInHand(hand);
			if (!(stack.getItem() instanceof RopeItem item) || RopeEntity.heldBy(player) != null) return InteractionResult.PASS;
			if (entity == player || entity == com.smeshariki.gameplay.character.CharacterEntity.body(player) || entity instanceof RopeEntity || entity instanceof RaftEntity) return InteractionResult.PASS;
			if (!level.isClientSide() && Rafts.freshClick(player)) {
				Vec3 point = hit != null ? hit.getLocation() : entity.position().add(0, entity.getBbHeight(), 0);
				RopeEntity rope = item.create(level);
				rope.setupAnchor(entity, point, player);
				level.addFreshEntity(rope);
				if (!player.hasInfiniteMaterials()) stack.shrink(1);
			}
			return InteractionResult.SUCCESS;
		});
		// тарзанка в руке, за плотом нет блока: пакет использования всё равно уходит на сервер
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator() || RopeEntity.heldBy(player) != null || !(player.getItemInHand(hand).getItem() instanceof RopeItem) || !Rafts.freshClick(player)) return InteractionResult.PASS;
			return anchorOnRaft(player, level, hand, Double.POSITIVE_INFINITY) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
	}

	/**
	 * Длину тарзанок (обычной и без размаха) в выживании и приключении менять нельзя ни в какую сторону. Верёвка-подтяжка и стропы шара не в счёте,
	 * у них своё правило (с креплением на блоке не удлинить). В творческом режиме длина меняется у всех.
	 */
	public static boolean lengthFixed(RopeEntity rope) {
		return !(rope instanceof PullRopeEntity) && !(rope instanceof com.smeshariki.gameplay.raft.BalloonRopeEntity);
	}

	/** Ctrl у игрока (сервер читает его из пакета ввода, как у маркеров). */
	private static boolean holdsCtrl(Player player) {
		return player instanceof ServerPlayer sp && sp.getLastClientInput().sprint();
	}

	/** Какие верёвки игрок только что отпустил и ещё не вышел из их клеток: за них он снова не цепляется. Только в памяти. */
	private static final Map<UUID, Set<Integer>> RELEASED = new HashMap<>();

	static void markReleased(UUID player, int rope) {
		RELEASED.computeIfAbsent(player, k -> new HashSet<>()).add(rope);
	}

	/**
	 * Автозацеп: игрок (или его персонаж), чьё тело пересекает висящую или зацепленную верёвку, хватается за неё сам, как если бы схватил ЛКМ там, где рука.
	 * Слезть — ПКМ; отпустивший цепляется снова, только выйдя из клетки этой верёвки и вернувшись.
	 */
	private static void autoGrab(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() || !player.isAlive() || player.getAbilities().flying || RopeEntity.heldBy(player) != null) continue;
			Entity body = com.smeshariki.gameplay.character.CharacterEntity.body(player);
			AABB box = body.getBoundingBox().inflate(0.1);
			Set<Integer> released = RELEASED.getOrDefault(player.getUUID(), Set.of());
			Set<Integer> touching = new HashSet<>();
			RopeEntity grab = null;
			// автозацеп только у обычной тарзанки с большим раскачиванием
			for (RopeEntity rope : player.level().getEntitiesOfClass(RopeEntity.class, box.inflate(RopeEntity.LONGEST + 1), r -> r.getClass() == RopeEntity.class && r.mode() != RopeEntity.HELD)) {
				if (!rope.touches(box)) continue;
				touching.add(rope.getId());
				if (grab == null && !released.contains(rope.getId())) grab = rope;
			}
			if (!released.isEmpty()) {
				Set<Integer> still = RELEASED.get(player.getUUID());
				still.retainAll(touching);
				if (still.isEmpty()) RELEASED.remove(player.getUUID());
			}
			if (grab != null) grab.grabBy(player);
		}
		RELEASED.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	/** Сервер: ПКМ тарзанкой по плоту или шару ближе maxDistance — крепление на нём, второй конец в руке. */
	private static boolean anchorOnRaft(Player player, Level level, InteractionHand hand, double maxDistance) {
		ItemStack stack = player.getItemInHand(hand);
		if (!(stack.getItem() instanceof RopeItem item)) return false;
		RaftEntity raft = Rafts.looked(player, level, maxDistance);
		if (raft == null) return false;
		RopeEntity rope = item.create(level);
		rope.setupAnchor(raft, lookPoint(player, raft), player);
		level.addFreshEntity(rope);
		if (!player.hasInfiniteMaterials()) stack.shrink(1);
		return true;
	}

	/** Точка на блоках плота под прицелом; если луч уже не попадает — сам плот. */
	public static Vec3 lookPoint(Player player, RaftEntity raft) {
		Vec3 eye = player.getEyePosition(), look = player.getLookAngle();
		double distance = raft.rayDistance(eye, eye.add(look.scale(Rafts.REACH)));
		return Double.isNaN(distance) ? raft.position() : eye.add(look.scale(distance));
	}
}
