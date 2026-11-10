package com.smeshariki.gameplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Shadow;

import com.smeshariki.gameplay.raft.RaftEntity;
import com.smeshariki.gameplay.rope.RopeEntity;

import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Игрок на плоту не «летит»: под ним нет блоков мира, но есть блоки плота (иначе в выживании кикнет за полёт). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Inject(method = "noBlocksAround", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$raft(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (RaftEntity.standsOnRaft(entity)) cir.setReturnValue(false);
		// висящий на тарзанке тоже не летит; вселённый приходит сюда своим персонажем
		for (Entity e : entity.getIndirectPassengers()) if (e instanceof Player p && RopeEntity.heldBy(p) != null) cir.setReturnValue(false);
		if (entity instanceof Player p && RopeEntity.heldBy(p) != null) cir.setReturnValue(false);
	}

	@Shadow
	public ServerPlayer player;
	@Shadow
	private double vehicleFirstGoodX, vehicleFirstGoodY, vehicleFirstGoodZ, vehicleLastGoodX, vehicleLastGoodY, vehicleLastGoodZ;

	/** Дальше этого от носителя на сервере клиент не может быть за один пакет, блоков. */
	private static final double MAX_CLAIM = 48;

	/**
	 * Клиент вселённого игрока после дальнего переноса носителя ещё шлёт его старое положение (за сотни блоков). Ваниль двигает носитель на эту
	 * разницу через {@code Entity.move} и считает коллизии вдоль всего пути: сервер вставал на 15–30 секунд (проверка «moved too quickly»
	 * хосту одиночной игры не применяется). Такой пакет отбрасывается, а клиенту возвращается положение носителя на сервере.
	 */
	@Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
	private void gameplaymod$farClaim(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
		Entity root = this.player.getRootVehicle();
		if (root == this.player || root.getControllingPassenger() != this.player) return;
		// ваниль двигает носитель на (заявленное − последнее «хорошее»), а оно обновляется только в тике сервера: после переноса носителя на сотни
		// блоков первые пакеты считали бы путь через весь мир, а клиент шлёт их по 20 в секунду — тик не доходил до обновления и стоял десятки секунд
		double sx = this.vehicleLastGoodX - root.getX(), sy = this.vehicleLastGoodY - root.getY(), sz = this.vehicleLastGoodZ - root.getZ();
		if (sx * sx + sy * sy + sz * sz > MAX_CLAIM * MAX_CLAIM) {
			this.vehicleFirstGoodX = this.vehicleLastGoodX = root.getX();
			this.vehicleFirstGoodY = this.vehicleLastGoodY = root.getY();
			this.vehicleFirstGoodZ = this.vehicleLastGoodZ = root.getZ();
		}
		var claim = packet.position();
		double dx = claim.x() - root.getX(), dy = claim.y() - root.getY(), dz = claim.z() - root.getZ();
		if (dx * dx + dy * dy + dz * dz <= MAX_CLAIM * MAX_CLAIM) return;
		((ServerGamePacketListenerImpl) (Object) this).send(ClientboundMoveVehiclePacket.fromEntity(root));
		ci.cancel();
	}
}
