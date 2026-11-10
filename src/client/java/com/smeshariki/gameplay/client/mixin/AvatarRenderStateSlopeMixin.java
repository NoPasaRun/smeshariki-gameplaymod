package com.smeshariki.gameplay.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.smeshariki.gameplay.client.SlideLying;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateSlopeMixin implements SlideLying {
	@Unique
	private float gameplaymod$tilt = Float.NaN, gameplaymod$drop;

	@Override
	public float gameplaymod$tilt() {
		return gameplaymod$tilt;
	}

	@Override
	public float gameplaymod$drop() {
		return gameplaymod$drop;
	}

	@Override
	public void gameplaymod$set(float tilt, float drop) {
		gameplaymod$tilt = tilt;
		gameplaymod$drop = drop;
	}
}
