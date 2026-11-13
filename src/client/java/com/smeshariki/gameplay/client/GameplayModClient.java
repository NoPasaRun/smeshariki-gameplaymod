package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.slide.SlideAdjust;
import com.smeshariki.gameplay.slide.FieldSlideBlock;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import org.lwjgl.glfw.GLFW;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.character.CharacterAbility;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Characters;
import com.smeshariki.gameplay.character.ChooseCharacter;
import com.smeshariki.gameplay.character.OpenCharacterGear;
import com.smeshariki.gameplay.rope.AdjustLength;
import com.smeshariki.gameplay.rope.RopeAction;
import com.smeshariki.gameplay.rope.RopeEntity;
import com.smeshariki.gameplay.wind.WindMarkerItem;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

public class GameplayModClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "main"));
	private static final KeyMapping ROPE_LONGER = key("rope_longer", GLFW.GLFW_KEY_RIGHT_BRACKET);
	private static final KeyMapping ROPE_SHORTER = key("rope_shorter", GLFW.GLFW_KEY_LEFT_BRACKET);
	/** Переключает ось колеса у стропы руля: вперёд-назад или вправо-влево. Клавишу можно делить со списком игроков. */
	static final KeyMapping BALLOON_AXIS = key("balloon_axis", GLFW.GLFW_KEY_TAB);
	/** Умение вселённого персонажа: Совунья готовит еду, Лосяш рисует схему, Бараш замедляет время, Нюша зовёт смешарика. */
	private static final KeyMapping SKILL = key("skill", GLFW.GLFW_KEY_G);
	/** Блоков за тик, пока клавиша зажата. */
	private static final float STEP = 0.15f;

	private static KeyMapping key(String name, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + GameplayMod.NAMESPACE + "." + name, code, CATEGORY));
	}

	/** Прыжок был нажат в прошлый тик (чтобы ловить именно нажатие, а не удержание). */
	private static boolean wasJumpDown;
	/** Прыжок нажат именно в этом тике (не удерживается). */
	private static boolean jumpPressed;

	/** Вселённый в персонажа жмёт прыжок: сообщаем ему, он решит, обычный это прыжок или двойной. */
	private static void relayJump(Minecraft mc) {
		boolean down = mc.gui.screen() == null && mc.options.keyJump.isDown();
		jumpPressed = down && !wasJumpDown;
		if (jumpPressed && mc.player.getVehicle() instanceof CharacterEntity character) character.requestJump();
		wasJumpDown = down;
	}

	/** Тройное нажатие W: окно между нажатиями в тиках (у ванильного двойного нажатия бега 7). */
	private static final int TRIPLE_WINDOW = 8;
	private static boolean wasForwardDown;
	private static int forwardTaps, forwardGap;

	/** Игрок только что нажал W третий раз подряд (не реже окна между нажатиями). */
	private static boolean tripleForward(Minecraft mc) {
		boolean down = mc.gui.screen() == null && mc.options.keyUp.isDown();
		boolean pressed = down && !wasForwardDown;
		wasForwardDown = down;
		if (forwardGap < 100) forwardGap++;
		if (!pressed) return false;
		forwardTaps = forwardGap <= TRIPLE_WINDOW ? forwardTaps + 1 : 1;
		forwardGap = 0;
		if (forwardTaps < 3) return false;
		forwardTaps = 0;
		return true;
	}

	/**
	 * Особенность персонажа: Ёжик катится клубком по тройному нажатию W (начать можно только на земле; выключает прыжок, а также
	 * остановка или вода), Карыч планирует по клавише бега (Ctrl; начать можно только в падении, до приземления). Состояние идёт серверу пакетом.
	 */
	private static void relayAbility(Minecraft mc) {
		boolean down = mc.gui.screen() == null && mc.options.keySprint.isDown();
		boolean triple = tripleForward(mc);
		if (!(mc.player.getVehicle() instanceof CharacterEntity c) || !c.hasAbility()) return;
		// с маркером ветра Ctrl — наклон стрелки, а не особенность; уже начатую особенность не прерываем
		if (c.getType() != Characters.YOZHIK && !c.isAbilityActive() && mc.player.getMainHandItem().getItem() instanceof WindMarkerItem) return;
		boolean key = down && !c.isInWater();
		boolean on = c.isAbilityActive();
		boolean want;
		if (c.getType() == Characters.YOZHIK) {
			// тройное W включает; выключает прыжок (или остановка на ровном месте, или вода)
			want = on;
			if (on && (jumpPressed || c.isRollDone() || c.isInWater())) want = false;
			else if (!on && triple && c.onGround() && !c.isInWater()) {
				want = true;
				c.startRoll(mc.player.getYRot());
			}
		} else want = key && (on ? !c.onGround() : !c.onGround() && c.getDeltaMovement().y < 0);
		if (want == on) return;
		c.setAbilityActive(want);
		ClientPlayNetworking.send(new CharacterAbility(want));
	}

	/** Кувырок камеры (рад.), пока вселённый Ёжик катится клубком; только от первого лица. */
	public static float cameraRoll() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !(mc.player.getVehicle() instanceof CharacterEntity c) || c.getType() != Characters.YOZHIK || !mc.options.getCameraType().isFirstPerson()) return 0f;
		return net.minecraft.util.Mth.lerp(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), c.camRollO, c.camRoll);
	}

	/** Колесо мыши, пока игрок держит стропу шара; true — щелчок съеден и панель предметов не листается. */
	public static boolean scrollBalloonRope(double delta) {
		return BalloonRopesClient.scroll(delta) || DeadzoneClient.scroll(delta);
	}

	/** Камера заблокирована: вселённый Ёжик катится клубком. */
	public static boolean cameraLocked(net.minecraft.world.entity.player.Player player) {
		return player.getVehicle() instanceof CharacterEntity c && c.isRolling();
	}

	private static boolean wasUseDown;

	/**
	 * ПКМ с верёвкой в руке: отпустить (клик по пустому воздуху с пустой рукой на сервер вообще не уходит, поэтому пакет шлём сами). С Ctrl — зацепить
	 * конец за плот или шар: их блоки не настоящие, и сервер сам их под прицелом не видит; за блок мира конец цепляет обычное использование блока.
	 * Топливо стропы шара ПКМ заправляет, не отпуская.
	 */
	private static void relayRope(Minecraft mc) {
		boolean down = mc.gui.screen() == null && mc.options.keyUse.isDown();
		boolean pressed = down && !wasUseDown;
		wasUseDown = down;
		RopeEntity held = pressed && !mc.player.isSpectator() ? RopeEntity.heldBy(mc.player) : null;
		if (held == null || held.takesItem(mc.player.getMainHandItem())) return;
		if (!mc.options.keySprint.isDown()) {
			ClientPlayNetworking.send(new RopeAction(RopeAction.RELEASE, -1));
			return;
		}
		var raft = com.smeshariki.gameplay.raft.Rafts.looked(mc.player, mc.level, mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS ? mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition()) : Double.POSITIVE_INFINITY);
		if (raft != null) ClientPlayNetworking.send(new RopeAction(RopeAction.ATTACH_RAFT, raft.getId()));
	}

	/** Игрок только что отпустил верёвку и ещё летит. */
	private static boolean wasHolding, flying;

	/**
	 * Инерция после отпускания: пока игрок в воздухе, возвращаем ему ванильное сопротивление по горизонтали (как и на
	 * верёвке), поэтому он летит дальше по дуге до самого приземления, а не вязнет в воздухе. Вертикаль остаётся ванильной.
	 */
	private static void keepMomentum(Minecraft mc) {
		var p = mc.player;
		boolean holding = RopeEntity.heldBy(p) != null;
		if (wasHolding && !holding) flying = true;
		wasHolding = holding;
		if (!flying) return;
		// вселённый летит персонажем: скорость и касания считаются у него
		var body = CharacterEntity.body(p);
		if (holding || body.onGround() || body.isInWater() || p.isFallFlying() || p.getAbilities().flying || body.horizontalCollision || !body.isAlive()) {
			flying = false;
			return;
		}
		double k = (1 - RopeEntity.SWING_LOSS) / RopeEntity.VANILLA_DRAG_XZ;
		var v = body.getDeltaMovement();
		body.setDeltaMovement(v.x * k, v.y, v.z * k);
	}

	@Override
	public void onInitializeClient() {
		RopeRenderer.init();
		SlideModels.init();
		SpikeModels.init();
		Autorun.init();
		CurrentsClient.init();
		CharacterRenderer.init();
		RaftRenderer.init();
		WindMarkerRenderer.init();
		FireMarkerRenderer.init();
		SmokeParticle.init();
		DeadzoneClient.init();
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(com.smeshariki.gameplay.lava.LavaTides.TYPE, net.minecraft.client.renderer.entity.NoopRenderer::new);
		LavaWaveRenderer.init();
		com.smeshariki.gameplay.barrage.Barrages.holdsMarkerOnClient = FireMarkerRenderer::holdsMarker;
		BalloonRopesClient.init();
		GlassesVision.init();
		SkillClient.init();
		MenuScreens.register(Characters.GEAR_MENU, CharacterGearScreen::new);
		ClientPlayNetworking.registerGlobalReceiver(ChooseCharacter.TYPE, (msg, ctx) -> {
			Minecraft mc = ctx.client();
			if (!(mc.gui.screen() instanceof CharacterChoiceScreen open && open.shows(msg.allowed()))) mc.gui.setScreen(new CharacterChoiceScreen(msg.allowed()));
		});
		// ЛКМ: схватить верёвку под прицелом (или сломать, если прицел на креплении); с верёвкой в руке ничего не делает (отпускает ПКМ), но и блоки не ломает
		ClientPreAttackCallback.EVENT.register((mc, player, clickCount) -> {
			if (player.isSpectator()) return false;
			if (RopeEntity.heldBy(player) != null) return true;
			if (clickCount == 0) return false;
			RopePicker.Pick pick = RopePicker.pick(mc, player);
			if (pick == null) return false;
			ClientPlayNetworking.send(new RopeAction(pick.action(), pick.entityId()));
			player.swing(InteractionHand.MAIN_HAND);
			return true;
		});
		// творческий режим, пустая рука: ПКМ по скату поднимает ближайший угол, Ctrl+ПКМ опускает; решает пакет, обычное использование блока не отправляем
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !player.isCreative() || !player.getMainHandItem().isEmpty()
					|| !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof FieldSlideBlock)) return InteractionResult.PASS;
			ClientPlayNetworking.send(new SlideAdjust(hit.getBlockPos(), (float) (hit.getLocation().x - hit.getBlockPos().getX()), (float) (hit.getLocation().z - hit.getBlockPos().getZ()),
					Minecraft.getInstance().options.keySprint.isDown()));
			player.swing(hand);
			return InteractionResult.FAIL;
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Autorun.tick(mc);
			if (mc.player == null || mc.level == null) return;
			keepMomentum(mc);
			BalloonRopesClient.tick(mc);
			// вселённый: клавиша инвентаря открывает окно с одеждой персонажа вместо обычного инвентаря
			if (mc.player.getVehicle() instanceof CharacterEntity && (mc.gui.screen() instanceof InventoryScreen || mc.gui.screen() instanceof CreativeModeInventoryScreen)) {
				mc.gui.setScreen(null);
				ClientPlayNetworking.send(new OpenCharacterGear());
			}
			relayRope(mc);
			relayJump(mc);
			relayAbility(mc);
			SkillClient.tick(mc, SKILL);
			if (mc.gui.screen() != null) return;
			float delta = (ROPE_LONGER.isDown() ? STEP : 0) - (ROPE_SHORTER.isDown() ? STEP : 0);
			if (delta != 0 && RopeEntity.heldBy(mc.player) != null) ClientPlayNetworking.send(new AdjustLength(delta));
		});
	}
}
