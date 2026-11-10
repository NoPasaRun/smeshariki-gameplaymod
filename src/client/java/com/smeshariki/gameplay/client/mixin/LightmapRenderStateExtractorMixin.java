package com.smeshariki.gameplay.client.mixin;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.api.ViewLight;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;

/**
 * Делает дневной свет неба ярче: в тенях и под навесами светлее, чем в ванили. Подсказка направления ({@link ViewLight}) идёт в обратную сторону:
 * чем она темнее, тем слабее свет неба и тем глубже тени (ниже и свет неба, и рассеянный свет в тени).
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {
	/** Во сколько раз усиливается свет неба (1 — как в ванили). */
	@Unique
	private static final float SUN_BOOST = 6.25f;
	/**
	 * Усиление света неба в режиме подсказки: «светло» (бежит к цели) и «самое тёмное» (прочь от цели). Выше единицы всё, что светлее уровня около 5, упирается в максимум
	 * и тени под кронами исчезают (так и устроено «яркое солнце»), поэтому в режиме подсказки свет неба слабее ванильного: под листвой настоящая тень, на просветах светло.
	 */
	@Unique
	private static final float GUIDE_LIGHT = 0.9f, GUIDE_DARK = 0.25f;
	/** Какая доля рассеянного света остаётся в тени в режиме подсказки: при «светло» и при «самом тёмном». */
	@Unique
	private static final float AMBIENT_LIGHT = 0.9f, AMBIENT_DARK = 0.3f;
	/** Что мы сами записали в {@code skyFactor} и {@code ambientColor} в прошлый раз и из чего: если там то же, значение не пересчитывалось и применять к нему поверх нельзя. */
	@Unique
	private float gameplaymod$lastBoosted = Float.NaN, gameplaymod$lastBase;
	@Unique
	private Vector3fc gameplaymod$lastAmbient, gameplaymod$lastAmbientBase;
	@Unique
	private float gameplaymod$lastDarkness = -1;

	@Inject(method = "extract", at = @At("RETURN"))
	private void gameplaymod$brighterSun(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		float dark = ViewLight.darkness(), strength = ViewLight.strength();
		float key = strength * 2 + dark;
		boolean fresh = state.skyFactor != gameplaymod$lastBoosted;
		// ваниль пересчитала значения: берём новые за основу; иначе основа — то, из чего мы получили прошлый результат
		float base = fresh ? state.skyFactor : gameplaymod$lastBase;
		Vector3fc ambient = state.ambientColor == gameplaymod$lastAmbient && gameplaymod$lastAmbientBase != null ? gameplaymod$lastAmbientBase : state.ambientColor;
		if (!state.needsUpdate && !fresh && Math.abs(key - gameplaymod$lastDarkness) < 0.002f) return;
		if (!state.needsUpdate && fresh) return;
		// множители перемножаются, а не складываются: яркость на глаз меняется кратно, и «светло» с «тёмным» не сливаются
		float guide = (float) (GUIDE_LIGHT * Math.pow(GUIDE_DARK / GUIDE_LIGHT, dark));
		float boost = (float) (SUN_BOOST * Math.pow(guide / SUN_BOOST, strength));
		state.skyFactor = base * boost;
		float shade = AMBIENT_LIGHT + (AMBIENT_DARK - AMBIENT_LIGHT) * dark;
		Vector3fc dimmed = strength > 0f ? new Vector3f(ambient).mul(1f + (shade - 1f) * strength) : ambient;
		state.ambientColor = dimmed;
		// изменилось только наше затемнение: ваниль сама текстуру не обновит
		state.needsUpdate = true;
		gameplaymod$lastBase = base;
		gameplaymod$lastBoosted = state.skyFactor;
		gameplaymod$lastAmbientBase = ambient;
		gameplaymod$lastAmbient = dimmed;
		gameplaymod$lastDarkness = key;
	}
}
