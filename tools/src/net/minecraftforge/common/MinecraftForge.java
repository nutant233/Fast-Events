package net.minecraftforge.common;

import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;

/** Test stand-in: same field name and descriptor as the real MinecraftForge. */
public final class MinecraftForge {

    public static final IEventBus EVENT_BUS = BusBuilder.builder().build();

    private MinecraftForge() {}
}