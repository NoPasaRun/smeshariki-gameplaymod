package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.current.CurrentField;
import com.smeshariki.gameplay.current.CurrentMarkerEntity;
import com.smeshariki.gameplay.current.Currents;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Клиентская часть течений: свои маркеры, поле и толкание своего игрока (его позицию ведёт его клиент). */
final class CurrentsClient {
	private CurrentsClient() {
	}

	static void init() {
		CurrentMarkerRenderer.init();
		Currents.holdsMarkerOnClient = CurrentMarkerRenderer::holdsMarker;
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof CurrentMarkerEntity m) CurrentField.add(m);
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof CurrentMarkerEntity m) CurrentField.remove(m);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CurrentField.clear());
		ClientTickEvents.END_LEVEL_TICK.register(level -> {
			CurrentField field = CurrentField.of(level);
			var player = Minecraft.getInstance().player;
			if (field == null) return;
			field.tick(level);
			if (field.isEmpty() || player == null) return;
			Entity body = CharacterEntity.body(player);
			if (!body.isInWater() || body == player && player.getAbilities().flying) return;
			Vec3 dir = Currents.flowAt(field, body);
			if (dir != null) Currents.push(body, dir);
		});
	}
}
