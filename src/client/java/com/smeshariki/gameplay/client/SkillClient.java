package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.gameplay.api.Calls;
import com.smeshariki.gameplay.character.CharacterEntity;
import com.smeshariki.gameplay.character.Characters;
import com.smeshariki.gameplay.character.Schemes;
import com.smeshariki.gameplay.character.SkillUse;
import com.smeshariki.gameplay.character.TimeSlow;
import com.smeshariki.gameplay.character.TimeSlowState;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

/** Клавиша умения (G) вселённого персонажа: окна выбора схемы (Лосяш) и смешарика (Нюша), нажатие для остальных; состояние замедления времени. */
final class SkillClient {
	private SkillClient() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(TimeSlowState.TYPE, (msg, ctx) -> TimeSlow.setClient(msg.owner(), msg.radius()));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TimeSlow.setClient(-1, 0f));
	}

	static void tick(Minecraft mc, KeyMapping key) {
		if (!(mc.player.getVehicle() instanceof CharacterEntity c)) {
			while (key.consumeClick()) {
				// нажатия вне персонажа не копятся
			}
			return;
		}
		while (key.consumeClick()) {
			if (mc.gui.screen() != null) continue;
			EntityType<?> type = c.getType();
			if (type == Characters.SOVUNYA || type == Characters.BARASH) ClientPlayNetworking.send(new SkillUse(""));
			else if (type == Characters.LOSYASH) openSchemes(mc);
			else if (type == Characters.NYUSHA) openCall(mc);
		}
	}

	private static void close(Minecraft mc) {
		mc.gui.setScreen(null);
	}

	/** Лосяш: какую схему нарисовать на бумаге в руке. */
	private static void openSchemes(Minecraft mc) {
		List<MenuScreen.Entry> entries = new ArrayList<>();
		for (String name : Schemes.NAMES) {
			entries.add(new MenuScreen.Entry(Schemes.label(name), () -> {
				ClientPlayNetworking.send(new SkillUse("scheme:" + name));
				close(mc);
			}));
		}
		mc.gui.setScreen(new MenuScreen(Component.translatable("gui.gameplaymod.schemes.title"), null, entries));
	}

	/** Нюша: кого позвать, затем что с ним сделать (и какой приказ). */
	private static void openCall(Minecraft mc) {
		List<MenuScreen.Entry> who = new ArrayList<>();
		MenuScreen[] first = new MenuScreen[1];
		for (EntityType<CharacterEntity> type : Characters.all()) {
			if (type == Characters.NYUSHA) continue;
			String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
			who.add(new MenuScreen.Entry(Characters.alias(type), () -> openActions(mc, first[0], type, id)));
		}
		first[0] = new MenuScreen(Component.translatable("gui.gameplaymod.call.title"), null, who);
		mc.gui.setScreen(first[0]);
	}

	private static void openActions(Minecraft mc, MenuScreen back, EntityType<CharacterEntity> type, String id) {
		List<MenuScreen.Entry> actions = new ArrayList<>();
		MenuScreen[] self = new MenuScreen[1];
		for (var e : Calls.all().entrySet()) {
			String actionId = e.getKey();
			Calls.Action action = e.getValue();
			actions.add(new MenuScreen.Entry(action.label(), () -> {
				if (action.options().isEmpty()) {
					ClientPlayNetworking.send(new SkillUse("call:" + id + "|" + actionId + "|"));
					close(mc);
					return;
				}
				List<MenuScreen.Entry> options = new ArrayList<>();
				for (Calls.Option option : action.options()) {
					options.add(new MenuScreen.Entry(option.label(), () -> {
						ClientPlayNetworking.send(new SkillUse("call:" + id + "|" + actionId + "|" + option.id()));
						close(mc);
					}));
				}
				mc.gui.setScreen(new MenuScreen(action.label(), self[0], options));
			}));
		}
		self[0] = new MenuScreen(Characters.alias(type), back, actions);
		mc.gui.setScreen(self[0]);
	}
}
