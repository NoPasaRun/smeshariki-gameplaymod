package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.smeshariki.gameplay.raft.RaftEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Блоки плотов, шаров и брёвен — не блоки мира, и ванильный прицел шёл сквозь них: можно было ломать блоки и бить существ за ними.
 * Если блок плота ближе того, что выбрал прицел, прицел упирается в него и ничего не выбирает.
 */
@Mixin(Minecraft.class)
public class MinecraftPickMixin {
	@Inject(method = "pick", at = @At("TAIL"))
	private void gameplaymod$stopAtRafts(float partialTick, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		Entity camera = mc.getCameraEntity();
		if (camera == null || mc.level == null || mc.player == null || RaftEntity.ALL.isEmpty()) return;
		Vec3 eye = camera.getEyePosition(partialTick);
		HitResult hit = mc.hitResult;
		double limit = hit != null && hit.getType() != HitResult.Type.MISS ? hit.getLocation().distanceTo(eye)
				: Math.max(mc.player.blockInteractionRange(), mc.player.entityInteractionRange());
		Vec3 view = camera.getViewVector(partialTick);
		Vec3 end = eye.add(view.scale(limit));
		double best = Double.NaN;
		for (RaftEntity r : RaftEntity.ALL) {
			if (r.level() != mc.level || r.isRemoved() || r.destroyed()) continue;
			double d = r.rayDistance(eye, end);
			if (!Double.isNaN(d) && (Double.isNaN(best) || d < best)) best = d;
		}
		if (Double.isNaN(best) || best >= limit - 1.0e-4) return;
		Vec3 at = eye.add(view.scale(best));
		mc.hitResult = BlockHitResult.miss(at, Direction.getApproximateNearest(view.x, view.y, view.z).getOpposite(), BlockPos.containing(at));
		mc.crosshairPickEntity = null;
	}
}
