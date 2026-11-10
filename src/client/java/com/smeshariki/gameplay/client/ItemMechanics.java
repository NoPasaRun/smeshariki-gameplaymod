package com.smeshariki.gameplay.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.smeshariki.gameplay.raft.Balloons;
import com.smeshariki.gameplay.raft.BalloonRopeEntity;
import com.smeshariki.gameplay.raft.BalloonRopeItem;
import com.smeshariki.gameplay.rope.CalmRopeEntity;
import com.smeshariki.gameplay.rope.PullRopeEntity;
import com.smeshariki.gameplay.rope.RopeEntity;
import com.smeshariki.gameplay.rope.Ropes;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Описание механики предмета для окна {@link MechanicsScreen}. Всё берётся из языковых файлов по идентификатору предмета, поэтому описание можно
 * добавить предмету любого мода, не трогая код: {@code mechanics.<мод>.<предмет>.summary} — краткое «что это», и по порядку (с 1, пока ключи идут подряд)
 * {@code mechanics.<мод>.<предмет>.use.N} («Как пользоваться»), {@code .controls.N} («Управление»), {@code .traits.N} («Особенности»).
 * В строках доступны аргументы: {@code %1$s} — клавиша «удлинить верёвку», {@code %2$s} — «укоротить», {@code %3$s} — «сменить ось руля шара».
 */
final class ItemMechanics {
	/** Разделы по порядку: идентификатор (часть ключа и заголовка {@code gui.gameplaymod.mechanics.section.<id>}). */
	static final List<String> SECTIONS = List.of("use", "controls", "traits");

	/** Один раздел описания: заголовок и строки. */
	record Section(Component title, List<Component> lines) {
	}

	/** Описание предмета: название, краткое пояснение и разделы. */
	record Info(ItemStack stack, Component name, Component summary, List<Section> sections) {
	}

	private ItemMechanics() {
	}

	/** Описание предмета игрока, который он сейчас использует: верёвка в руках, иначе предмет в основной руке, иначе в левой. {@code null} — описания нет. */
	static @Nullable Info current(Minecraft mc) {
		if (mc.player == null) return null;
		RopeEntity rope = RopeEntity.heldBy(mc.player);
		if (rope != null) {
			Info info = of(new ItemStack(ropeItem(rope)));
			if (info != null) return info;
		}
		Info main = of(mc.player.getMainHandItem());
		return main != null ? main : of(mc.player.getOffhandItem());
	}

	private static Item ropeItem(RopeEntity rope) {
		if (rope instanceof CalmRopeEntity) return Ropes.CALM_ITEM;
		if (rope instanceof PullRopeEntity) return Ropes.PULL_ITEM;
		if (rope instanceof BalloonRopeEntity balloon) return balloon.kind() == BalloonRopeItem.Kind.LIFT ? Balloons.LIFT_ROPE : Balloons.HELM_ROPE;
		return Ropes.ITEM;
	}

	/** Описание предмета; {@code null} — у него нет описания механики. */
	static @Nullable Info of(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		String base = "mechanics." + id.getNamespace() + "." + id.getPath();
		Language language = Language.getInstance();
		if (!language.has(base + ".summary")) return null;
		Object[] args = {Component.keybind("key.gameplaymod.rope_longer"), Component.keybind("key.gameplaymod.rope_shorter"), Component.keybind("key.gameplaymod.balloon_axis")};
		List<Section> sections = new ArrayList<>();
		for (String section : SECTIONS) {
			List<Component> lines = new ArrayList<>();
			for (int i = 1; language.has(base + "." + section + "." + i); i++) lines.add(Component.translatable(base + "." + section + "." + i, args));
			if (!lines.isEmpty()) sections.add(new Section(Component.translatable("gui.gameplaymod.mechanics.section." + section), lines));
		}
		return new Info(stack.copyWithCount(1), stack.getHoverName(), Component.translatable(base + ".summary", args), sections);
	}
}
