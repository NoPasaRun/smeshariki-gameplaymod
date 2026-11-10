package com.smeshariki.gameplay.slide;

/** Живое тело помнит, что его вынесло со ската: в полёте сохраняет скорость, пока не приземлится. Реализует {@code LivingEntitySlopeMixin}. */
public interface SlideRider {
	boolean gameplaymod$launched();

	void gameplaymod$setLaunched(boolean launched);

	/** Сколько тиков тело уже не скользит: поза держится ещё немного, чтобы расхождение сервера и клиента на тик не поднимало лежащего. */
	int gameplaymod$offSlope();

	void gameplaymod$setOffSlope(int ticks);

	/** Упёрся и получил обычные ноги, пока не сойдёт со ската. */
	boolean gameplaymod$walkingOut();

	void gameplaymod$setWalkingOut(boolean walkingOut);

	/** Где тело упёрлось. */
	net.minecraft.world.phys.Vec3 gameplaymod$walkOutFrom();

	void gameplaymod$setWalkOutFrom(net.minecraft.world.phys.Vec3 at);

	/** В полёте со ската уже был тик на земле: следующий такой — настоящая посадка. */
	boolean gameplaymod$landedTick();

	void gameplaymod$setLandedTick(boolean landed);
}
