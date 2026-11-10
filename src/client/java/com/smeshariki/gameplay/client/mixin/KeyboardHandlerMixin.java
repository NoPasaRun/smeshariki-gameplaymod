package com.smeshariki.gameplay.client.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.character.CharacterDismiss;
import com.smeshariki.gameplay.character.CharacterEntity;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/** В творческом режиме Esc во вселении выводит из персонажа вместо меню паузы; перехват именно клавиши, потому что пауза от потери фокуса не должна никого убивать. Открытые окна Esc закрывает как обычно. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$dismissOnEscape(long window, int action, KeyEvent event, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (action != GLFW.GLFW_PRESS || event.key() != GLFW.GLFW_KEY_ESCAPE || mc.gui.screen() != null || mc.player == null) return;
		if (!mc.player.isCreative() || !CharacterEntity.isPossessing(mc.player)) return;
		ClientPlayNetworking.send(new CharacterDismiss());
		ci.cancel();
	}
}
