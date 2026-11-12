package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.smeshariki.gameplay.character.CharacterEntity;

import net.minecraft.client.player.LocalPlayer;

/**
 * Вселённый игрок сидит внутри персонажа ниже его ног (на 0,76 блока). На бревне это под палубой, в воде реки, и ваниль считала, что игрок
 * стоит в мелкой воде, где начать бег нельзя: на плывущем бревне разбежаться не получалось, на лавовых плотах получалось (2026-11-05,
 * найдено клиентским gametest). Мелкая вода для бега теперь по самому персонажу.
 * <p>
 * Столкновение для остановки бега — тоже не игрока: его коробка шире персонажа и задевает стену раньше, а ваниль гасила бег на любом таком
 * касании (на автобеге задел стену боком — и дальше шагом). У вселённого бег о стену гасит сам персонаж, только при ударе в лоб
 * ({@code CharacterEntity.tickRidden}).
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintMixin {
	@Redirect(method = "isSprintingPossible", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isInShallowWater()Z"))
	private boolean gameplaymod$shallowByCharacter(LocalPlayer player) {
		// вселённому бег разрешён и в воде (ваниль запрещает в мелкой воде)
		return !(player.getVehicle() instanceof CharacterEntity) && player.isInShallowWater();
	}

	@Redirect(method = "shouldStopRunSprinting", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;horizontalCollision:Z"))
	private boolean gameplaymod$collisionByCharacter(LocalPlayer player) {
		return !(player.getVehicle() instanceof CharacterEntity) && player.horizontalCollision;
	}
}
