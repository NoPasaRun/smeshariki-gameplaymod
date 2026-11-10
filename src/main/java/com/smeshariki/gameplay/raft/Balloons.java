package com.smeshariki.gameplay.raft;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.rope.RopeEntity;
import com.smeshariki.gameplay.rope.Ropes;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Воздушные шары: предмет-горелка и сущность {@link BalloonEntity}. ПКМ горелкой по блоку собирает постройку в шар, ПКМ
 * горелкой по шару разбирает его. Загрузку сущности и отправку блоков клиенту делает {@link Rafts}: шар — тоже плот.
 * Стропы управления ({@link BalloonRopeEntity}) висят на шаре; пока игрок держит стропу, колесо мыши управляет шаром.
 */
public final class Balloons {
	public static EntityType<BalloonEntity> TYPE;
	public static EntityType<BalloonRopeEntity> ROPE_TYPE;
	public static Item ITEM, LIFT_ROPE, HELM_ROPE;
	/** События шара с группой (см. {@link BalloonEntity}): {@code имя:группа}. */
	public static final String EVENT_STRANDED = "gameplaymod:balloon_stranded", EVENT_LIFTED = "gameplaymod:balloon_lifted";

	private Balloons() {
	}

	public static void init() {
		ResourceKey<EntityType<?>> typeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "balloon"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, typeKey, EntityType.Builder.<BalloonEntity>of(BalloonEntity::new, MobCategory.MISC)
				.sized(0.5f, 0.5f).clientTrackingRange(16).updateInterval(1).fireImmune().noSummon().build(typeKey));
		ResourceKey<EntityType<?>> ropeKey = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "balloon_rope"));
		ROPE_TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, ropeKey, EntityType.Builder.<BalloonRopeEntity>of(BalloonRopeEntity::new, MobCategory.MISC)
				.sized(0.4f, 0.4f).clientTrackingRange(10).updateInterval(1).fireImmune().noSummon().build(ropeKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "balloon_burner"));
		ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new Item(new Item.Properties().setId(itemKey).stacksTo(1)));
		LIFT_ROPE = rope("balloon_lift_rope", BalloonRopeItem.Kind.LIFT);
		HELM_ROPE = rope("balloon_helm_rope", BalloonRopeItem.Kind.HELM);
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> {
			output.accept(ITEM);
			output.accept(LIFT_ROPE);
			output.accept(HELM_ROPE);
		});

		// клиент пропускает дальше: пакет использования уйдёт на сервер, где всё решается
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).is(ITEM) || player.isSpectator() || level.isClientSide() || !Rafts.freshClick(player)) return InteractionResult.PASS;
			return Rafts.dismantleLooked(player, level, Double.POSITIVE_INFINITY) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || !player.getItemInHand(hand).is(ITEM) || player.isSpectator()) return InteractionResult.PASS;
			// клиент гасит клик сам: иначе следом уйдёт пакет «использовать предмет» и тут же разберёт только что собранный шар
			if (level.isClientSide() || !Rafts.freshClick(player)) return InteractionResult.SUCCESS;
			if (Rafts.dismantleLooked(player, level, hit.getLocation().distanceTo(player.getEyePosition()))) return InteractionResult.SUCCESS;
			Rafts.assemble(player, (ServerLevel) level, hit.getBlockPos(), true);
			return InteractionResult.SUCCESS;
		});

		// стропа: ПКМ по шару вешает её на него, конец остаётся в руке; ПКМ по блоку с концом в руке решает тарзанка (Ropes)
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player.getItemInHand(hand).getItem() instanceof BalloonRopeItem) || player.isSpectator()) return InteractionResult.PASS;
			if (RopeEntity.heldBy(player) != null || !Rafts.freshClick(player)) return InteractionResult.PASS;
			return hangRope(player, level, Double.POSITIVE_INFINITY) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (hand != InteractionHand.MAIN_HAND || !(player.getItemInHand(hand).getItem() instanceof BalloonRopeItem) || player.isSpectator()) return InteractionResult.PASS;
			if (level.isClientSide() || !Rafts.freshClick(player)) return InteractionResult.SUCCESS;
			hangRope(player, level, hit.getLocation().distanceTo(player.getEyePosition()));
			return InteractionResult.SUCCESS;
		});
		PayloadTypeRegistry.serverboundPlay().register(BalloonRopeAction.TYPE, BalloonRopeAction.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BalloonNudged.TYPE, BalloonNudged.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BalloonRopeAction.TYPE, (msg, ctx) -> control(ctx.player(), msg.action()));
	}

	private static Item rope(String name, BalloonRopeItem.Kind kind) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, name));
		return Registry.register(BuiltInRegistries.ITEM, key, new BalloonRopeItem(kind, new Item.Properties().setId(key).durability(BalloonRopeItem.MAX_FUEL)));
	}

	/** Вешает стропу из руки на шар под прицелом. */
	private static boolean hangRope(Player player, Level level, double maxDistance) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof BalloonRopeItem item) || !(Rafts.looked(player, level, maxDistance) instanceof BalloonEntity balloon)) return false;
		BalloonRopeEntity rope = new BalloonRopeEntity(ROPE_TYPE, level);
		rope.setKind(item.kind());
		rope.setFuel(BalloonRopeItem.fuel(stack));
		rope.setupAnchor(balloon, Ropes.lookPoint(player, balloon), player);
		level.addFreshEntity(rope);
		if (!player.hasInfiniteMaterials()) stack.shrink(1);
		boolean lift = item.kind() == BalloonRopeItem.Kind.LIFT;
		player.sendOverlayMessage(lift ? Component.translatable("message.gameplaymod.rope_lift_hung") : Component.translatable("message.gameplaymod.rope_helm_hung", Component.keybind("key.gameplaymod.balloon_axis")));
		return true;
	}

	/** Щелчок колеса от клиента: игрок должен держать стропу подходящего вида. */
	private static void control(ServerPlayer player, int action) {
		if (!(RopeEntity.heldBy(player) instanceof BalloonRopeEntity rope)) return;
		Result result = operate(rope, action, player.hasInfiniteMaterials());
		switch (result) {
			case NO_FUEL -> player.sendOverlayMessage(Component.translatable("message.gameplaymod.balloon_no_fuel"));
			case CLIMBED, BLOCKED -> {
				if (rope.balloon() != null && rope.kind() == BalloonRopeItem.Kind.LIFT) report(player, rope.balloon(), rope);
			}
			case NUDGED -> player.sendOverlayMessage(Component.translatable("message.gameplaymod.balloon_fuel", rope.fuel(), BalloonRopeItem.MAX_FUEL));
			default -> {
			}
		}
	}

	/** Чем кончился щелчок стропой. */
	public enum Result {
		/** Стропа не на шаре или щелчок не для этой стропы (руль у стропы высоты и наоборот). */
		WRONG,
		NO_FUEL,
		/** Высота: цель сдвинулась. */
		CLIMBED,
		/** Высота у предела, руль у стоящего на блоках шара: топливо не тратится. */
		BLOCKED,
		NUDGED
	}

	/**
	 * Щелчок колеса стропой {@code action} ({@link BalloonRopeAction}) — тот, кто держит стропу: игрок или персонаж без игрока. Тратит
	 * единицу топлива, если сработал и {@code free} = false; толчок рулём рассылается клиентам.
	 */
	public static Result operate(BalloonRopeEntity rope, int action, boolean free) {
		BalloonEntity balloon = rope.balloon();
		if (balloon == null) return Result.WRONG;
		boolean lift = rope.kind() == BalloonRopeItem.Kind.LIFT;
		boolean climb = action == BalloonRopeAction.UP || action == BalloonRopeAction.DOWN;
		if (climb != lift) return Result.WRONG;
		if (rope.fuel() <= 0) return Result.NO_FUEL;
		if (climb) {
			double before = balloon.targetY();
			balloon.climb(action == BalloonRopeAction.UP ? 1 : -1);
			if (balloon.targetY() == before) return Result.BLOCKED;
			if (!free) rope.setFuel(rope.fuel() - 1);
			return Result.CLIMBED;
		}
		int forward = action == BalloonRopeAction.FORWARD ? 1 : action == BalloonRopeAction.BACK ? -1 : 0;
		int left = action == BalloonRopeAction.LEFT ? 1 : action == BalloonRopeAction.RIGHT ? -1 : 0;
		if (!balloon.nudge(forward, left)) return Result.BLOCKED;
		if (!free) rope.setFuel(rope.fuel() - 1);
		BalloonNudged packet = new BalloonNudged(balloon.getId(), forward, left);
		for (ServerPlayer watcher : PlayerLookup.tracking(balloon)) ServerPlayNetworking.send(watcher, packet);
		return Result.NUDGED;
	}

	private static void report(ServerPlayer player, BalloonEntity balloon, BalloonRopeEntity rope) {
		Vec3 wind = balloon.wind();
		Component text = wind.lengthSqr() == 0
				? Component.translatable("message.gameplaymod.balloon_calm", Math.round(balloon.targetY()), rope.fuel())
				: Component.translatable("message.gameplaymod.balloon_wind", Math.round(balloon.targetY()), Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-wind.x, wind.z))), 360L), Math.round(Math.toDegrees(Math.asin(Math.clamp(wind.y / wind.length(), -1.0, 1.0)))), rope.fuel());
		player.sendOverlayMessage(text);
	}
}
