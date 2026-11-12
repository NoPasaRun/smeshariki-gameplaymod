package com.smeshariki.gameplay;

import com.smeshariki.gameplay.barrage.Barrages;
import com.smeshariki.gameplay.current.Currents;
import com.smeshariki.gameplay.character.Characters;
import com.smeshariki.gameplay.raft.Balloons;
import com.smeshariki.gameplay.raft.Rafts;
import com.smeshariki.gameplay.slide.Slides;
import com.smeshariki.gameplay.rope.Ropes;
import com.smeshariki.gameplay.wind.Winds;

import net.fabricmc.api.ModInitializer;

public class GameplayMod implements ModInitializer {
	public static final String NAMESPACE = "gameplaymod";

	@Override
	public void onInitialize() {
		Ropes.init();
		Currents.init();
		Characters.init();
		Rafts.init();
		com.smeshariki.gameplay.raft.Logs.init();
		Winds.init();
		Balloons.init();
		Slides.init();
		LeafPhase.init();
		Barrages.init();
		com.smeshariki.gameplay.launcher.Launchers.init();
		com.smeshariki.gameplay.smoke.Smokes.init();
		com.smeshariki.gameplay.lava.LavaTides.init();
		com.smeshariki.gameplay.lava.LavaWaves.init();
		com.smeshariki.gameplay.character.AutorunControl.init();
		com.smeshariki.gameplay.api.GameplayMechanics.init();
	}
}
