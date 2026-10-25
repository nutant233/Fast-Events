package net.neoforged.neoforge.common;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;

/** Harness stub: stands in for NeoForge.EVENT_BUS so the rewriter can be exercised without Minecraft. */
public class NeoForge {

    public static final IEventBus EVENT_BUS = BusBuilder.builder().build();
}
