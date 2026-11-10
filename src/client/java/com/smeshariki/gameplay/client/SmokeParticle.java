package com.smeshariki.gameplay.client;

import com.smeshariki.gameplay.smoke.Smokes;

import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * Клуб густого дыма ({@link Smokes#PARTICLE}): спрайт из {@code textures/particle/thick_smoke_*} (вырезки из {@code entity/thick_smoke} с мягким краем),
 * сквозь блоки, без тяжести. Параметры скорости заняты: dx — размер клуба в блоках, dy — скорость подъёма за тик. Живёт 5–8 с: проявляется,
 * немного растёт и медленно поворачивается, тает к концу.
 */
public final class SmokeParticle extends SingleQuadParticle {
	private static final float MAX_ALPHA = 0.55f;
	private final float size, spin;

	private SmokeParticle(ClientLevel level, double x, double y, double z, double size, double rise, SpriteSet sprites, RandomSource random) {
		super(level, x, y, z, sprites.get(random));
		this.size = (float) size;
		this.quadSize = this.size;
		this.xd = 0;
		this.yd = rise;
		this.zd = 0;
		this.hasPhysics = false;
		this.gravity = 0;
		this.friction = 1f;
		this.lifetime = 100 + random.nextInt(60);
		this.roll = this.oRoll = random.nextFloat() * (float) Math.PI * 2;
		this.spin = (random.nextFloat() - 0.5f) * 0.01f;
		float shade = 0.75f + random.nextFloat() * 0.15f;
		setColor(shade, shade * 0.97f, shade * 0.94f);
		setAlpha(0);
	}

	@Override
	public void tick() {
		super.tick();
		float t = (float) this.age / this.lifetime;
		float fade = Math.min(1f, Math.min(t / 0.25f, (1 - t) / 0.35f));
		setAlpha(MAX_ALPHA * Math.max(0f, fade));
		this.quadSize = this.size * (0.85f + 0.3f * t);
		this.oRoll = this.roll;
		this.roll += this.spin;
	}

	@Override
	protected Layer getLayer() {
		return Layer.TRANSLUCENT;
	}

	public static void init() {
		ParticleProviderRegistry.getInstance().register(Smokes.PARTICLE, sprites -> new Provider(sprites));
	}

	private record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz, RandomSource random) {
			return new SmokeParticle(level, x, y, z, dx, dy, this.sprites, random);
		}
	}
}
