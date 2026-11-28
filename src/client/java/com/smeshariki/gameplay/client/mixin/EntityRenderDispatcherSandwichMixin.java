package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.client.SandwichRenderer;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

/** Морок Бутерброда: живых существ рисует {@link SandwichRenderer} — и снимает состояние, и рисует по нему. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherSandwichMixin {
	@Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$disguise(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		SandwichRenderer disguise = SandwichRenderer.disguises(entity);
		if (disguise != null) cir.setReturnValue(disguise);
	}

	@Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$disguised(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		SandwichRenderer disguise = SandwichRenderer.disguises(state);
		if (disguise != null) cir.setReturnValue(disguise);
	}
}
