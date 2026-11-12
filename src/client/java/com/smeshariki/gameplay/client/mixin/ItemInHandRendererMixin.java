package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.client.CharacterRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;

/** От первого лица вселённый видит руки своего персонажа. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@Shadow private float mainHandHeight, oMainHandHeight, offHandHeight, oOffHandHeight;

	@Inject(method = "submitHandsWithItems", at = @At("HEAD"))
	private void gameplaymod$characterHands(float partialTick, PoseStack pose, SubmitNodeCollector collector, LocalPlayer player, int light, CallbackInfo ci) {
		if (player.getVehicle() instanceof CharacterEntity character && Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(character) instanceof CharacterRenderer renderer)
			renderer.submitHands(character, player, partialTick, pose, collector, light, 1f - Mth.lerp(partialTick, oMainHandHeight, mainHandHeight), 1f - Mth.lerp(partialTick, oOffHandHeight, offHandHeight));
	}
}
