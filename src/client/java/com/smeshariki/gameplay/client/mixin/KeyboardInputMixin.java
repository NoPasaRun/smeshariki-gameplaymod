package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.client.Autorun;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Автобег: ввод выглядит так, будто зажаты W и бег; клавишу бега не трогаем, иначе Ёжик свернётся в клубок, а Карыч начнёт планировать. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("RETURN"))
	private void gameplaymod$autorun(CallbackInfo ci) {
		if (!Autorun.active()) return;
		Input k = this.keyPresses;
		this.keyPresses = new Input(true, false, k.left(), k.right(), k.jump(), k.shift(), k.sprint() || Autorun.sprints());
		this.moveVector = new Vec2(this.moveVector.x, 1f).normalized();
	}
}
