package com.smeshariki.gameplay.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.smeshariki.gameplay.raft.RaftEntity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Добавляет блоки плотов к столкновениям с сущностями: по плоту можно ходить и прыгать. */
@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
	@Inject(method = "getEntityCollisions", at = @At("RETURN"), cancellable = true)
	private void gameplaymod$rafts(Entity entity, AABB box, CallbackInfoReturnable<List<VoxelShape>> cir) {
		// игрока и его персонажа на сервере ведёт клиент: серверный плот опережает клиентский, и учёт его форм откатывал бы их назад
		if (RaftEntity.ALL.isEmpty() || entity instanceof RaftEntity || entity instanceof net.minecraft.server.level.ServerPlayer
				|| entity != null && entity.getControllingPassenger() instanceof net.minecraft.server.level.ServerPlayer) return;
		if (!((Object) this instanceof Level level)) return;
		List<VoxelShape> extra = null;
		for (RaftEntity r : RaftEntity.ALL) {
			if (r.level() != level || r.isRemoved()) continue;
			if (extra == null) extra = new ArrayList<>();
			r.collect(box, extra);
		}
		if (extra == null || extra.isEmpty()) return;
		List<VoxelShape> all = new ArrayList<>(cir.getReturnValue());
		all.addAll(extra);
		cir.setReturnValue(all);
	}
}
