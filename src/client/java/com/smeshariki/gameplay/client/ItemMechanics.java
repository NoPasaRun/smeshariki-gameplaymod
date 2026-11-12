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
 * Описание механики предмета для окна «Описание механики» (его рисует LevelMod). Всё берётся из языковых файлов по идентификатору предмета, поэтому описание можно
 * добавить предмету любого мода, не трогая код: {@code mechanics.<мод>.<предмет>.summary} — краткое «что это», и по порядку (с 1, пока ключи идут подряд)
 * {@code mechanics.<мод>.<предмет>.use.N} («Как пользоваться»), {@code .controls.N} («Управление»), {@code .traits.N} («Особенности»).
 * В строках доступны аргументы: {@code %1$s} — клавиша «удлинить верёвку», {@code %2$s} — «укоротить», {@code %3$s} — «сменить ось руля шара».
 */
public final class ItemMechanics {
	/** Разделы по порядку: идентификатор (часть ключа и заголовка {@code gui.gameplaymod.mechanics.section.<id>}). */
	public static final List<String> SECTIONS = List.of("use", "controls", "traits");

	/** Один раздел описания: идентификатор (по нему окно берёт заголовок) и строки. В строках встречаются значки {@code {lmb}}, {@code {rmb}}, {@code {wheel}}, {@code {ctrl}}, {@code {shift}}, {@code {key:название}}. */
	public record Section(String id, List<Component> lines) {
	}

	/**
	 * Описание: название, краткое пояснение и разделы. У группы смежных предметов (ключ {@code .group} у каждого) название и разделы — общие, а
	 * {@code members} — описания самих предметов (используемый — первым); у одиночного предмета {@code members} пуст.
	 */
	public record Info(ItemStack stack, Component name, Component summary, List<Section> sections, List<Info> members) {
	}

	private ItemMechanics() {
	}

	/** Описание предмета игрока, который он сейчас использует: верёвка в руках, иначе предмет в основной руке, иначе в левой. {@code null} — описания нет. */
	public static @Nullable Info current(Minecraft mc) {
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

	private static final Object[] ARGS = {Component.keybind("key.gameplaymod.rope_longer"), Component.keybind("key.gameplaymod.rope_shorter"), Component.keybind("key.gameplaymod.balloon_axis")};

	private static List<Section> sections(String base, Language language) {
		List<Section> sections = new ArrayList<>();
		for (String section : SECTIONS) {
			List<Component> lines = new ArrayList<>();
			for (int i = 1; language.has(base + "." + section + "." + i); i++) lines.add(Component.translatable(base + "." + section + "." + i, ARGS));
			if (!lines.isEmpty()) sections.add(new Section(section, lines));
		}
		return sections;
	}

	private static String base(Item item) {
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return "mechanics." + id.getNamespace() + "." + id.getPath();
	}

	/** Описание одного предмета без группы; {@code null} — у него нет краткого пояснения. */
	private static @Nullable Info single(ItemStack stack, Language language) {
		String base = base(stack.getItem());
		if (!language.has(base + ".summary")) return null;
		return new Info(stack.copyWithCount(1), stack.getHoverName(), Component.translatable(base + ".summary", ARGS), sections(base, language), List.of());
	}

	/**
	 * Описание предмета; {@code null} — у него нет описания механики. Если у предмета есть ключ {@code mechanics.<мод>.<предмет>.group} (значение — имя группы),
	 * описание общее на всю группу: {@code mechanics.group.<группа>.title}, {@code .summary}, {@code .use.N}, {@code .controls.N}, {@code .traits.N}, а предметы
	 * группы идут отдельными блоками со своими строками.
	 */
	public static @Nullable Info of(ItemStack stack) {
		if (stack.isEmpty()) return null;
		Language language = Language.getInstance();
		Info own = single(stack, language);
		String base = base(stack.getItem());
		if (!language.has(base + ".group")) return own;
		String group = language.getOrDefault(base + ".group");
		String gb = "mechanics.group." + group;
		if (!language.has(gb + ".title")) return own;
		List<Info> members = new ArrayList<>();
		if (own != null) members.add(own);
		for (Item item : BuiltInRegistries.ITEM) {
			if (item == stack.getItem()) continue;
			String b = base(item);
			if (!language.has(b + ".group") || !group.equals(language.getOrDefault(b + ".group"))) continue;
			Info member = single(new ItemStack(item), language);
			if (member != null) members.add(member);
		}
		Component summary = language.has(gb + ".summary") ? Component.translatable(gb + ".summary", ARGS) : Component.empty();
		return new Info(stack.copyWithCount(1), Component.translatable(gb + ".title"), summary, sections(gb, language), members);
	}
}
