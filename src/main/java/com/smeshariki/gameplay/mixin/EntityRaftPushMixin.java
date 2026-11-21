package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.raft.RaftEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/** Тело, упёршееся своим движением в плот, толкает его ({@link RaftEntity#pushedBy}). */
@Mixin(Entity.class)
public abstract class EntityRaftPushMixin {
	@Unique
	private double gameplaymod$fromX, gameplaymod$fromZ;

	@Inject(method = "move", at = @At("HEAD"))
	private void gameplaymod$rememberStart(MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		gameplaymod$fromX = self.getX();
		gameplaymod$fromZ = self.getZ();
	}

	@Inject(method = "move", at = @At("TAIL"))
	private void gameplaymod$pushRaft(MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (type == MoverType.SELF && self.horizontalCollision && !RaftEntity.ALL.isEmpty()) RaftEntity.pushedBy(self, movement, gameplaymod$fromX, gameplaymod$fromZ);
	}
}
