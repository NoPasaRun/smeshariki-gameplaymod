package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;

/**
 * Попадание лучом (удар, стрела, прицел) по смешарику идёт по его хитбоксу урона ({@link CharacterEntity#hurtBox()}), по форме его модели, а не по
 * хитбоксу столкновений: тот узкий (0,6 блока) и пропускал удары по бокам круглого тела.
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
	@Redirect(method = { "getHitResult", "getEntityHitResult", "getManyEntityHitResult", "getHitEntitiesAlong" },
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
	private static AABB gameplaymod$hurtBox(Entity entity) {
		return entity instanceof CharacterEntity character ? character.hurtBox() : entity.getBoundingBox();
	}
}
