package test;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/** Sample bytecode for the rewriter: discarded result, used result, unknown bus, guard site and hooks. */
public final class TestTarget {

    private TestTarget() {
    }

    public static FooEvent postNew() {
        return NeoForge.EVENT_BUS.post(new FooEvent(1));
    }

    public static void postDiscarded() {
        NeoForge.EVENT_BUS.post(new FooEvent(2));
    }

    public static Event postFromLocal(final Event event) {
        return NeoForge.EVENT_BUS.post(event);
    }

    public static Event postOtherBus(final IEventBus bus) {
        return bus.post(new FooEvent(3));
    }

    public static void postUnlistened() {
        NeoForge.EVENT_BUS.post(new UnlistenedEvent());
    }

    public static void addBarListener(final java.util.function.Consumer<BarEvent> consumer) {
        NeoForge.EVENT_BUS.addListener(BarEvent.class, consumer);
    }

    public static void registerOn(final IEventBus bus, final Object listener) {
        bus.register(listener);
    }

    public static void unregisterOn(final IEventBus bus, final Object listener) {
        bus.unregister(listener);
    }

    public static class BarEvent extends Event {
    }

    public static class UnlistenedEvent extends Event {
        public UnlistenedEvent() {
            Counters.constructions++;
        }
    }

    public static class FooEvent extends Event {
        public final int value;

        public FooEvent(final int value) {
            this.value = value;
        }
    }
}
