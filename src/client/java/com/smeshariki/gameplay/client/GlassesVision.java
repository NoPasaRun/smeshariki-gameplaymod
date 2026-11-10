package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.GameplayMod;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Characters;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;

/**
 * Зрение вселённого игрока и очки (аксессуар в слоте глаз). Ёжик без очков видит мир в размытии ({@link #BLUR}); в очках зрение
 * нормальное. У остальных персонажей очки дают «рыбий глаз» ({@link #FISHEYE}). Чем больше побиты очки, тем больше трещин на
 * экране (6 стадий, картинки {@code textures/gui/glasses_crack_N.png}); когда они разбиваются, у Ёжика снова размытие.
 * Эффекты накладывает {@code GameRendererMixin}.
 */
public final class GlassesVision {
	public static final Identifier BLUR = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "glasses_blur");
	public static final Identifier FISHEYE = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "glasses_fisheye");
	private static final int STAGES = 6;
	private static final Identifier[] CRACKS = new Identifier[STAGES];
	private static final Identifier AIR = Identifier.withDefaultNamespace("hud/air"), AIR_BURSTING = Identifier.withDefaultNamespace("hud/air_bursting");

	static {
		for (int i = 0; i < STAGES; i++) CRACKS[i] = Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "textures/gui/glasses_crack_" + (i + 1) + ".png");
	}

	private GlassesVision() {
	}

	public static void init() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "glasses"), GlassesVision::extract);
		HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.fromNamespaceAndPath(GameplayMod.NAMESPACE, "character_air"), GlassesVision::extractAir);
		// во вселении сердечки игрока скрыты: здоровье — у персонажа, его сердечки на месте сытости
		HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, vanilla -> (g, delta) -> {
			var player = net.minecraft.client.Minecraft.getInstance().player;
			if (player != null && player.getVehicle() instanceof com.smeshariki.gameplay.character.CharacterEntity) return;
			vanilla.extractRenderState(g, delta);
		});
		// стамина персонажа — на месте полосы опыта и в её виде; число уровня в это время скрыто
		HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, vanilla -> (g, delta) -> {
			if (!extractStamina(g)) vanilla.extractRenderState(g, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, vanilla -> (g, delta) -> {
			if (!staminaShown()) vanilla.extractRenderState(g, delta);
		});
	}

	private static final Identifier BAR_BACKGROUND = Identifier.withDefaultNamespace("hud/experience_bar_background");

	/** Полоса стамины видна, пока персонаж в воде или стамина не полная. */
	private static boolean staminaShown() {
		CharacterEntity c = character(Minecraft.getInstance());
		return c != null && (c.isInWater() || c.stamina() < 1f);
	}

	/** Цвет стамины (по просьбе 2026-11-05 — голубая): заливка, светлая верхняя кромка, тёмная нижняя. */
	private static final int STAMINA_FILL = 0xFF3FA9F5, STAMINA_LIGHT = 0xFF9ADCFF, STAMINA_DARK = 0xFF1F6FB0;

	/** Стамина вселённого персонажа на месте полосы опыта (182×5 над панелью быстрого доступа): подложка ванильная, заливка голубая. Нарисовала — true. */
	private static boolean extractStamina(GuiGraphicsExtractor g) {
		if (!staminaShown()) return false;
		CharacterEntity c = character(Minecraft.getInstance());
		int x = g.guiWidth() / 2 - 91, y = g.guiHeight() - 29;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, BAR_BACKGROUND, x, y, 182, 5);
		int filled = Math.min(182, (int) (c.stamina() * 183f));
		// у ванильного спрайта заливки свой зелёный цвет, подкраской голубой из него не получить: заливка рисуется сама, внутри рамки подложки
		if (filled > 2) {
			g.fill(x + 1, y + 1, x + filled - 1, y + 4, STAMINA_FILL);
			g.fill(x + 1, y + 1, x + filled - 1, y + 2, STAMINA_LIGHT);
			g.fill(x + 1, y + 3, x + filled - 1, y + 4, STAMINA_DARK);
		}
		return true;
	}

	private static CharacterEntity character(Minecraft mc) {
		return mc.player != null && mc.player.getVehicle() instanceof CharacterEntity c ? c : null;
	}

	/** Какой пост-эффект сейчас нужен миру (null — никакого). */
	public static Identifier effect() {
		CharacterEntity c = character(Minecraft.getInstance());
		if (c == null) return null;
		if (c.getType() == Characters.YOZHIK) return c.hasGlasses() ? null : BLUR;
		return c.hasGlasses() ? FISHEYE : null;
	}

	private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		CharacterEntity c = character(Minecraft.getInstance());
		if (c == null || !c.hasGlasses()) return;
		float wear = c.glassesWear();
		if (wear <= 0f) return;
		int stage = Mth.clamp((int) Math.ceil(wear * STAGES), 1, STAGES);
		g.blit(RenderPipelines.GUI_TEXTURED, CRACKS[stage - 1], 0, 0, 0f, 0f, g.guiWidth(), g.guiHeight(), 1024, 576, 1024, 576);
	}

	/**
	 * Пузырьки воздуха вселённого персонажа, как в ванили: над панелью быстрого доступа справа, пока он под водой или воздуха
	 * меньше максимума. В выживании это рисует сама игра (у игрока воздух равен воздуху персонажа); в творческом режиме полос
	 * здоровья нет совсем, поэтому пузырьки рисуем здесь.
	 */
	private static void extractAir(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		CharacterEntity c = character(mc);
		if (c == null || mc.gameMode == null || mc.gameMode.canHurtPlayer()) return;
		int max = c.getMaxAirSupply(), air = Mth.clamp(c.getAirSupply(), 0, max);
		if (!c.isEyeInFluid(FluidTags.WATER) && air >= max) return;
		int full = Mth.ceil((air - 2) * 10.0 / max), total = Mth.ceil(air * 10.0 / max);
		int x = g.guiWidth() / 2 + 91 - 9, y = g.guiHeight() - 49;
		for (int i = 0; i < Math.max(full, total); i++) g.blitSprite(RenderPipelines.GUI_TEXTURED, i < full ? AIR : AIR_BURSTING, x - i * 8, y, 9, 9);
	}
}
