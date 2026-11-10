package com.smeshariki.gameplay.rope;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Верёвка: та же тарзанка, но короче (от 0,2 блока) и для подтягивания. Пока её держат, персонаж проходит сквозь все сущности, а когда отпускает и
 * оказывается в блоке или в другой сущности, его выталкивает на ближайшее свободное место.
 */
public class PullRopeEntity extends RopeEntity {
	public static final float SHORTEST = 0.2f;

	public PullRopeEntity(EntityType<? extends PullRopeEntity> type, Level level) {
		super(type, level);
	}

	@Override
	public float minLength() {
		return SHORTEST;
	}

	@Override
	protected ItemStack dropStack() {
		return new ItemStack(Ropes.PULL_ITEM);
	}

	@Override
	public void tick() {
		super.tick();
		// пока держат — тело держащего не сталкивается с сущностями (флаг гаснет сам через пару тиков после отпускания)
		if (mode() == HELD && level().getEntity(holderId()) instanceof net.minecraft.world.entity.player.Player holder && CharacterEntity.body(holder) instanceof CharacterEntity body) body.passEntities();
	}

	@Override
	public void release() {
		Entity holder = mode() == HELD ? level().getEntity(holderId()) : null;
		super.release();
		if (holder != null && !level().isClientSide()) pushOut(holder);
	}

	/** Вытолкнуть держащего из блоков и сущностей на ближайшее свободное место; не нашлось — остаётся. */
	private static void pushOut(Entity holder) {
		Entity body = holder.getRootVehicle();
		Level level = body.level();
		Vec3 from = body.position();
		Vec3 best = null;
		double bestDistance = Double.MAX_VALUE;
		for (double up = 0; up <= 4; up += 0.5) {
			for (int ring = 0; ring <= 3; ring++) {
				int steps = ring == 0 ? 1 : 8 * ring;
				for (int i = 0; i < steps; i++) {
					double angle = Math.PI * 2 * i / steps;
					Vec3 candidate = from.add(Math.cos(angle) * ring * 0.5, up, Math.sin(angle) * ring * 0.5);
					if (!free(level, body, candidate)) continue;
					double d = candidate.distanceToSqr(from);
					if (d < bestDistance) {
						bestDistance = d;
						best = candidate;
					}
				}
			}
			// ближайшее свободное найдено на этой высоте — выше не ищем
			if (best != null) break;
		}
		if (best == null || best.equals(from)) return;
		body.teleportTo(best.x, best.y, best.z);
		body.setDeltaMovement(Vec3.ZERO);
		if (holder instanceof ServerPlayer player) {
			player.connection.send(net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket.fromEntity(body));
			player.connection.send(new net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket(body.getId(), net.minecraft.world.entity.PositionMoveRotation.of(body), java.util.Set.of(), body.onGround()));
		}
	}

	/** Место свободно от блоков и от живых сущностей (кроме самого тела и его пассажиров). */
	private static boolean free(Level level, Entity body, Vec3 at) {
		AABB box = body.getBoundingBox().move(at.subtract(body.position()));
		if (!level.noCollision(body, box)) return false;
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, box)) if (other != body && !body.hasIndirectPassenger(other) && !other.isPassengerOfSameVehicle(body)) return false;
		return true;
	}
}
