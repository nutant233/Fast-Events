package test;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;

/** 被转换的样本：四种调用形态。 */
public final class TestTarget {

    private TestTarget() {}

    public static boolean postNew() {
        return MinecraftForge.EVENT_BUS.post(new FooEvent(1));
    }

    public static boolean postFromLocal(final Event event) {
        return MinecraftForge.EVENT_BUS.post(event);
    }

    public static boolean postOtherBus(final IEventBus bus) {
        return bus.post(new FooEvent(2));
    }

    public static boolean postWrapped(final IEventBus bus, final net.minecraftforge.eventbus.api.Event event,
                                      final net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher invoker) {
        return bus.post(event, invoker);
    }

    public static boolean postWrappedDirect(final net.minecraftforge.eventbus.api.Event event,
                                            final net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher invoker) {
        return MinecraftForge.EVENT_BUS.post(event, invoker);
    }

    public static void addOnBus(final IEventBus bus, final java.util.function.Consumer<FooEvent> consumer) {
        bus.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, FooEvent.class, consumer);
    }

    public static void addBarListener(final java.util.function.Consumer<BarEvent> consumer) {
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                BarEvent.class, consumer);
    }

    public static void addGenericListenerC(final java.util.function.Consumer<GenericTestEvent<String>> consumer) {
        MinecraftForge.EVENT_BUS.addGenericListener(String.class, consumer);
    }

    public static void removeListener(final Object object) {
        MinecraftForge.EVENT_BUS.unregister(object);
    }

    public static void postGeneric(final net.minecraftforge.eventbus.api.Event event) {
        MinecraftForge.EVENT_BUS.post(event);
    }

    public static void postBar() {
        MinecraftForge.EVENT_BUS.post(new BarEvent());
    }

    public static void registerOn(final IEventBus bus, final Object listener) {
        bus.register(listener);
    }

    public static class BarEvent extends Event {
        public BarEvent() {}
    }

    public static void postUnlistened() {
        MinecraftForge.EVENT_BUS.post(new UnlistenedEvent());
    }

    public static class UnlistenedEvent extends Event {
        public UnlistenedEvent() {
            Counters.constructions++;
        }
    }

    @Cancelable
    public static class FooEvent extends Event {
        public final int value;

        public FooEvent() {
            this(0);
        }

        public FooEvent(final int value) {
            this.value = value;
        }
    }
}
