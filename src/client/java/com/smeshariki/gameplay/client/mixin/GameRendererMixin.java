package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.client.GlassesVision;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;

/** Включает пост-эффект мира для вселённого: размытие (Ёжик без очков) или «рыбий глаз» (очки у остальных); как у зрения крипера. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Shadow
	private Identifier postEffectId;
	@Shadow
	private boolean effectActive;
	/** Эффект включили мы (чтобы выключить только его, а не чужой пост-эффект). */
	@Unique
	private boolean gameplaymod$blurred;

	@Inject(method = "render", at = @At("HEAD"))
	private void gameplaymod$blur(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		Identifier effect = GlassesVision.effect();
		if (effect != null) {
			this.postEffectId = effect;
			this.effectActive = true;
			this.gameplaymod$blurred = true;
		} else if (this.gameplaymod$blurred) {
			this.gameplaymod$blurred = false;
			this.postEffectId = null;
			this.effectActive = false;
		}
	}
}
