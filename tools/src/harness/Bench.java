package harness;

import io.github.nutant.fastevents.FastDispatch;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.GenericEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventListener;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * 一次 JVM 只测一个场景。之前把多个场景放同一个 JVM 里跑，共享的 invoke 调用点会被
 * 前面的场景污染，最后一个场景的数值会莫名其妙翻倍，所以改成"一个用例一个进程"。
 */
public final class Bench {

    private static long sink;
    private static Object escape;

    public static void main(final String[] args) {
        final String which = args.length > 0 ? args[0] : "eight-native";
        final IEventBus bus = MinecraftForge.EVENT_BUS;
        final EmptyEvent empty = new EmptyEvent();
        final FastDispatch.Holder emptyHolder = FastDispatch.holder();
        final FastDispatch.Holder eightHolder = FastDispatch.holder();
        final FastDispatch.Holder fastEightHolder = FastDispatch.holder();
        final FastDispatch.Holder markedHolder = FastDispatch.holder();
        final FastDispatch.Holder asmHolder = FastDispatch.holder();
        final FastDispatch.Holder arg1Holder = FastDispatch.holder();
        final FastDispatch.Holder guardHolder = FastDispatch.holder();
        final FastDispatch.Holder genericHolder = FastDispatch.holder();

        for (int i = 0; i < 8; i++) {
            bus.addListener(EventPriority.NORMAL, false, EightEvent.class, e -> sink += 1);
        }
        for (int i = 0; i < 8; i++) {
            FastDispatch.addListener(bus, EventPriority.NORMAL, false, FastEightEvent.class, e -> sink += 1);
        }
        bus.register(new AsmListener());
        bus.register(new Generic8());
        final Arg1Listener arg1 = new Arg1Listener();
        FastDispatch.addListener(bus, arg1::a);
        FastDispatch.addListener(bus, arg1::b);
        FastDispatch.addListener(bus, arg1::c);
        FastDispatch.addListener(bus, arg1::d);
        FastDispatch.addListener(bus, arg1::e);
        FastDispatch.addListener(bus, arg1::f);
        FastDispatch.addListener(bus, arg1::g);
        FastDispatch.addListener(bus, arg1::h);
        final Nat1Listener nat1 = new Nat1Listener();
        bus.addListener(nat1::a);
        bus.addListener(nat1::b);
        bus.addListener(nat1::c);
        bus.addListener(nat1::d);
        bus.addListener(nat1::e);
        bus.addListener(nat1::f);
        bus.addListener(nat1::g);
        bus.addListener(nat1::h);
        final EventPriority[] priorities = {
                EventPriority.HIGHEST, EventPriority.HIGHEST, EventPriority.HIGH, EventPriority.HIGH,
                EventPriority.NORMAL, EventPriority.NORMAL, EventPriority.LOW, EventPriority.LOWEST};
        for (int i = 0; i < priorities.length; i++) {
            FastDispatch.addListener(bus, priorities[i], false, MarkedEvent.class, e -> sink += 1);
        }

        final int n = 4_000_000;
        switch (which) {
            case "eight-native" -> time("EventBus.post, 8 consumer listeners", n, () -> {
                if (bus.post(new EightEvent())) sink += 1;
            });
            case "eight-fast" -> time("FastDispatch.post, 8 fast listeners", n, () -> {
                if (FastDispatch.post(bus, new FastEightEvent(), fastEightHolder)) sink += 1;
            });
            case "eight-dispatch-only" -> time("FastDispatch.post, 8 EventBus listeners", n, () -> {
                if (FastDispatch.post(bus, new EightEvent(), eightHolder)) sink += 1;
            });

            case "eight-markers" -> time("FastDispatch.post, 8 listeners/5 priorities", n, () -> {
                if (FastDispatch.post(bus, new MarkedEvent(), markedHolder)) sink += 1;
            });

            case "alloc" -> time("new FastEightEvent() (escaped)", n, () -> {
                escape = new FastEightEvent();
                sink += 1;
            });
            case "asm-native" -> time("EventBus.post, 8 @SubscribeEvent listeners", n, () -> {
                if (bus.post(new AsmEvent())) sink += 1;
            });
            case "asm-fast" -> time("FastDispatch.post, 8 @SubscribeEvent listeners", n, () -> {
                if (FastDispatch.post(bus, new AsmEvent(), asmHolder)) sink += 1;
            });
            case "arg1-native" -> time("EventBus.addListener(1-arg), 8 listeners", n, () -> {
                if (bus.post(new Nat1Event())) sink += 1;
            });
            case "arg1-fast" -> time("FastDispatch.addListener(1-arg), 8 listeners", n, () -> {
                if (FastDispatch.post(bus, new Arg1Event(), arg1Holder)) sink += 1;
            });
            case "generic-native" -> time("EventBus.post, 8 filtered (generic) listeners", n, () -> {
                if (bus.post(new GenericBenchEvent<>(String.class))) sink += 1;
            });
            case "generic-fast" -> time("FastDispatch.post, 8 filtered (generic) listeners", n, () -> {
                if (FastDispatch.post(bus, new GenericBenchEvent<>(String.class), genericHolder)) sink += 1;
            });
            case "asm-wrapper-gen" -> time("ASM listener class generation", 2_000, () -> {
                try {
                    sink += NoCacheFactory.INSTANCE.wrapper(REG_METHOD) == null ? 0 : 1;
                } catch (final Throwable t) {
                    throw new RuntimeException(t);
                }
            });
            case "lambda-gen" -> time("LambdaMetafactory listener creation", 2_000, () -> {
                try {
                    final IEventListener listener = (IEventListener) LAMBDA_FACTORY.invokeExact(new RegListener());
                    sink += listener == null ? 0 : 1;
                } catch (final Throwable t) {
                    throw new RuntimeException(t);
                }
            });
            case "asm-parse" -> time("ASM ClassNode parse (per class)", 200_000, () -> {
                try {
                    new org.objectweb.asm.ClassReader(PARSE_BYTES)
                            .accept(new org.objectweb.asm.tree.ClassNode(), 0);
                    sink += 1;
                } catch (final Throwable t) {
                    throw new RuntimeException(t);
                }
            });
            case "wide-native", "wide-fast" -> {
                final int count = args.length > 1 ? Integer.parseInt(args[1]) : 100;
                final boolean fast = which.endsWith("fast");
                for (int i = 0; i < count; i++) {
                    final Runnable noop = () -> sink += 1;
                    if (fast) {
                        FastDispatch.addListener(bus, EventPriority.NORMAL, false, WideEvent.class, e -> noop.run());
                    } else {
                        bus.addListener(EventPriority.NORMAL, false, WideEvent.class, e -> noop.run());
                    }
                }
                final FastDispatch.Holder wideHolder = FastDispatch.holder();
                final int iterations = 200_000;
                if (fast) {
                    time("FastDispatch.post, " + count + " listeners", iterations, () -> {
                        if (FastDispatch.post(bus, new WideEvent(), wideHolder)) sink += 1;
                    });
                } else {
                    time("EventBus.post, " + count + " listeners", iterations, () -> {
                        if (bus.post(new WideEvent())) sink += 1;
                    });
                }
            }
            case "alt-native", "alt-shared", "alt-split" -> {
                for (int i = 0; i < 8; i++) {
                    bus.addListener(EventPriority.NORMAL, false, AltAEvent.class, e -> sink += 1);
                    bus.addListener(EventPriority.NORMAL, false, AltBEvent.class, e -> sink += 1);
                }
                final FastDispatch.Holder shared = FastDispatch.holder();
                final FastDispatch.Holder holderA = FastDispatch.holder();
                final FastDispatch.Holder holderB = FastDispatch.holder();
                time("alt two event classes: " + which, n, () -> {
                    if ("alt-native".equals(which)) {
                        if (bus.post(new AltAEvent())) sink += 1;
                        if (bus.post(new AltBEvent())) sink += 1;
                    } else if ("alt-shared".equals(which)) {
                        if (FastDispatch.post(bus, new AltAEvent(), shared)) sink += 1;
                        if (FastDispatch.post(bus, new AltBEvent(), shared)) sink += 1;
                    } else {
                        if (FastDispatch.post(bus, new AltAEvent(), holderA)) sink += 1;
                        if (FastDispatch.post(bus, new AltBEvent(), holderB)) sink += 1;
                    }
                });
            }
            case "alt3-native", "alt3-shared", "alt3-split" -> {
                for (int i = 0; i < 8; i++) {
                    bus.addListener(EventPriority.NORMAL, false, AltAEvent.class, e -> sink += 1);
                    bus.addListener(EventPriority.NORMAL, false, AltBEvent.class, e -> sink += 1);
                    bus.addListener(EventPriority.NORMAL, false, AltCEvent.class, e -> sink += 1);
                }
                final FastDispatch.Holder shared = FastDispatch.holder();
                final FastDispatch.Holder holderA = FastDispatch.holder();
                final FastDispatch.Holder holderB = FastDispatch.holder();
                final FastDispatch.Holder holderC = FastDispatch.holder();
                time("alt three event classes: " + which, n, () -> {
                    if ("alt3-native".equals(which)) {
                        if (bus.post(new AltAEvent())) sink += 1;
                        if (bus.post(new AltBEvent())) sink += 1;
                        if (bus.post(new AltCEvent())) sink += 1;
                    } else if ("alt3-shared".equals(which)) {
                        if (FastDispatch.post(bus, new AltAEvent(), shared)) sink += 1;
                        if (FastDispatch.post(bus, new AltBEvent(), shared)) sink += 1;
                        if (FastDispatch.post(bus, new AltCEvent(), shared)) sink += 1;
                    } else {
                        if (FastDispatch.post(bus, new AltAEvent(), holderA)) sink += 1;
                        if (FastDispatch.post(bus, new AltBEvent(), holderB)) sink += 1;
                        if (FastDispatch.post(bus, new AltCEvent(), holderC)) sink += 1;
                    }
                });
            }
            case "dispatcher-churn" -> {
                final int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 500;
                final java.lang.management.MemoryMXBean memory = java.lang.management.ManagementFactory.getMemoryMXBean();
                final java.lang.management.ClassLoadingMXBean classes = java.lang.management.ManagementFactory.getClassLoadingMXBean();
                final long metaspaceBefore = memory.getNonHeapMemoryUsage().getUsed();
                final int classesBefore = classes.getLoadedClassCount();
                final FastDispatch.Holder churnHolder = FastDispatch.holder();
                final long t0 = System.nanoTime();
                for (int r = 0; r < rounds; r++) {
                    final java.util.function.Consumer<ChurnEvent> consumer = e -> sink += 1;
                    FastDispatch.addListener(bus, EventPriority.NORMAL, false, ChurnEvent.class, consumer);
                    FastDispatch.postVoid(bus, new ChurnEvent(), churnHolder);
                    FastDispatch.unregister(bus, consumer);
                }
                final long t1 = System.nanoTime();
                System.gc();
                final long metaspaceAfter = memory.getNonHeapMemoryUsage().getUsed();
                System.out.printf("%d dispatcher rebuilds: %.2f ms total, %.3f ms each, metaspace +%.1f MB, classes +%d%n",
                        rounds, (t1 - t0) / 1e6, (t1 - t0) / 1e6 / rounds,
                        (metaspaceAfter - metaspaceBefore) / 1048576.0,
                        classes.getLoadedClassCount() - classesBefore);
                System.out.println("  retained dispatchers=" + FastDispatch.debugDispatchers());
            }
            case "unregister-n10", "unregister-n1000" -> {
                final int count = which.endsWith("1000") ? 1_000 : 10;
                final net.minecraftforge.eventbus.ListenerList list = new net.minecraftforge.eventbus.ListenerList();
                for (int i = 0; i < count; i++) {
                    list.register(0, EventPriority.NORMAL, e -> sink += 1);
                }
                final IEventListener probe = e -> sink += 2;
                time("ListenerList.unregister, " + count + " listeners", 20_000, () -> {
                    list.register(0, EventPriority.NORMAL, probe);
                    list.unregister(0, probe);
                });
            }
            case "unregister-all", "unregister-one" -> {
                for (int i = 0; i < 1_000; i++) {
                    new net.minecraftforge.eventbus.ListenerList();
                }
                final net.minecraftforge.eventbus.ListenerList list = new net.minecraftforge.eventbus.ListenerList();
                final IEventListener listener = e -> sink += 0;
                if ("unregister-all".equals(which)) {
                    time("ListenerList.unregisterAll (scans 1000 lists)", 20_000, () -> {
                        list.register(0, EventPriority.NORMAL, listener);
                        net.minecraftforge.eventbus.ListenerList.unregisterAll(0, listener);
                    });
                } else {
                    time("ListenerList.unregister (the one list)", 20_000, () -> {
                        list.register(0, EventPriority.NORMAL, listener);
                        list.unregister(0, listener);
                    });
                }
            }
            default -> throw new IllegalArgumentException("unknown case " + which);
        }
        System.out.println("  (sink=" + sink + ")");
    }

    private static void time(final String name, final int iterations, final Runnable body) {
        for (int round = 0; round < 15; round++) {
            for (int i = 0; i < iterations / 4; i++) body.run();
        }
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 6; round++) {
            final long t0 = System.nanoTime();
            for (int i = 0; i < iterations; i++) body.run();
            final long t1 = System.nanoTime();
            best = Math.min(best, (t1 - t0) / (double) iterations);
        }
        final String unit = name.contains("generation") || name.contains("creation") ? "ns/each" : "ns/post";
        System.out.printf("%-38s %7.2f %s%n", name, best, unit);
    }

    public static class EmptyEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class EightEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class AltAEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class AltBEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class AltCEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class AsmEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static final class AsmListener {
        @SubscribeEvent public void a(AsmEvent e) { sink += 1; }
        @SubscribeEvent public void b(AsmEvent e) { sink += 1; }
        @SubscribeEvent public void c(AsmEvent e) { sink += 1; }
        @SubscribeEvent public void d(AsmEvent e) { sink += 1; }
        @SubscribeEvent public void e(AsmEvent e) { sink += 1; }
        @SubscribeEvent public void f(AsmEvent e) { sink += 1; }
        @SubscribeEvent public void g(AsmEvent e) { sink += 1; }
        @SubscribeEvent public void h(AsmEvent e) { sink += 1; }
    }

    public static final class Generic8 {
        @SubscribeEvent public void a(GenericBenchEvent<String> e) { sink += 1; }
        @SubscribeEvent public void b(GenericBenchEvent<String> e) { sink += 1; }
        @SubscribeEvent public void c(GenericBenchEvent<String> e) { sink += 1; }
        @SubscribeEvent public void d(GenericBenchEvent<String> e) { sink += 1; }
        @SubscribeEvent public void e(GenericBenchEvent<String> e) { sink += 1; }
        @SubscribeEvent public void f(GenericBenchEvent<String> e) { sink += 1; }
        @SubscribeEvent public void g(GenericBenchEvent<String> e) { sink += 1; }
        @SubscribeEvent public void h(GenericBenchEvent<String> e) { sink += 1; }
    }

    public static class GenericBenchEvent<T> extends GenericEvent<T> {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        public GenericBenchEvent() { super(); }

        public GenericBenchEvent(final Class<T> type) { super(type); }

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    private static final Method REG_METHOD = regMethod();

    private static final byte[] PARSE_BYTES = parseBytes();

    private static byte[] parseBytes() {
        try (java.io.InputStream in = Bench.class.getClassLoader()
                .getResourceAsStream("harness/Bench$AsmListener.class")) {
            return in.readAllBytes();
        } catch (final Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static final MethodHandle LAMBDA_FACTORY = lambdaFactory();

    private static Method regMethod() {
        try {
            return RegListener.class.getDeclaredMethod("onReg", RegEvent.class);
        } catch (final Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MethodHandle lambdaFactory() {
        try {
            final MethodHandle handle = MethodHandles.lookup().unreflect(REG_METHOD);
            final MethodType factoryType = MethodType.methodType(IEventListener.class, RegListener.class);
            return LambdaMetafactory.metafactory(MethodHandles.lookup(), "invoke", factoryType,
                    MethodType.methodType(void.class, Event.class), handle,
                    MethodType.methodType(void.class, RegEvent.class)).getTarget();
        } catch (final Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // eventbus 4.x/5.x have no ClassLoaderFactory; ASMEventHandler#createWrapper does the same class generation
    static final class NoCacheFactory {
        static final NoCacheFactory INSTANCE = new NoCacheFactory();

        Class<?> wrapper(final Method method) throws Exception {
            return new net.minecraftforge.eventbus.ASMEventHandler(new RegListener(), method, true)
                    .createWrapper(method);
        }
    }

    public static final class RegListener {
        @SubscribeEvent public void onReg(final RegEvent event) { }
    }

    public static class RegEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class ChurnEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class WideEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class UnlistenedEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));
        public static int constructions;
        public final int marker;

        public UnlistenedEvent() {
            this.marker = ++constructions;
        }

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class Arg1Event extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static final class Arg1Listener {
        public void a(Arg1Event e) { sink += 1; }
        public void b(Arg1Event e) { sink += 1; }
        public void c(Arg1Event e) { sink += 1; }
        public void d(Arg1Event e) { sink += 1; }
        public void e(Arg1Event e) { sink += 1; }
        public void f(Arg1Event e) { sink += 1; }
        public void g(Arg1Event e) { sink += 1; }
        public void h(Arg1Event e) { sink += 1; }
    }

    public static class Nat1Event extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static final class Nat1Listener {
        public void a(Nat1Event e) { sink += 1; }
        public void b(Nat1Event e) { sink += 1; }
        public void c(Nat1Event e) { sink += 1; }
        public void d(Nat1Event e) { sink += 1; }
        public void e(Nat1Event e) { sink += 1; }
        public void f(Nat1Event e) { sink += 1; }
        public void g(Nat1Event e) { sink += 1; }
        public void h(Nat1Event e) { sink += 1; }
    }

    public static class MarkedEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class FastEightEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }
}
