package harness;

import io.github.nutant.fastevents.FastDispatch;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;

/** 一次 JVM 只测一个场景；数值为每次 post 的纳秒数（取 6 轮最小值）。 */
public final class Bench {

    private static long sink;

    private static Object escape;

    public static void main(final String[] args) throws Exception {
        final String which = args.length > 0 ? args[0] : "eight-native";
        final IEventBus bus = NeoForge.EVENT_BUS;
        final int n = 4_000_000;

        switch (which) {
            case "post-native", "post-fast" -> {
                for (int i = 0; i < 8; i++) {
                    bus.addListener(EventPriority.NORMAL, false, BenchEvent.class, e -> sink += 1);
                }
                final FastDispatch.Holder holder = FastDispatch.holder();
                final boolean fast = which.endsWith("fast");
                time(fast ? "FastDispatch.post, 8 consumer listeners" : "EventBus.post, 8 consumer listeners", n, () -> {
                    if (fast) {
                        FastDispatch.post(bus, new BenchEvent(), holder);
                    } else {
                        bus.post(new BenchEvent());
                    }
                });
            }
            case "subscribe-native", "subscribe-fast" -> {
                bus.register(new SubscribeBench());
                final FastDispatch.Holder holder = FastDispatch.holder();
                final boolean fast = which.endsWith("fast");
                time(fast ? "FastDispatch.post, 8 @SubscribeEvent" : "EventBus.post, 8 @SubscribeEvent", n, () -> {
                    if (fast) {
                        FastDispatch.post(bus, new SubscribeEventHolder(), holder);
                    } else {
                        bus.post(new SubscribeEventHolder());
                    }
                });
            }
            case "alloc" -> time("new BenchEvent() (escaped)", n, () -> {
                escape = new BenchEvent();
                sink += 1;
            });
            case "mixed-shared", "mixed-split" -> {
                for (int i = 0; i < 8; i++) {
                    bus.addListener(EventPriority.NORMAL, false, MixedA.class, e -> sink += 1);
                    bus.addListener(EventPriority.NORMAL, false, MixedB.class, e -> sink += 1);
                }
                final FastDispatch.Holder shared = FastDispatch.holder();
                final FastDispatch.Holder holderA = FastDispatch.holder();
                final FastDispatch.Holder holderB = FastDispatch.holder();
                final boolean sharedMode = "mixed-shared".equals(which);
                time("mixed event classes per call site: " + which, n, () -> {
                    if (sharedMode) {
                        FastDispatch.post(bus, new MixedA(), shared);
                        FastDispatch.post(bus, new MixedB(), shared);
                    } else {
                        FastDispatch.post(bus, new MixedA(), holderA);
                        FastDispatch.post(bus, new MixedB(), holderB);
                    }
                });
            }
            case "rewrite" -> {
                final byte[] bytes = Bench.class.getClassLoader()
                        .getResourceAsStream("test/TestTarget.class").readAllBytes();
                time("rewrite one class (parse + analyse + edit)", 20_000, () -> {
                    final org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                    new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
                    new io.github.nutant.fastevents.PostCallRewriter().rewrite(node);
                    sink += 1;
                });
            }
            default -> throw new IllegalArgumentException("unknown case " + which);
        }
        System.out.println("  (sink=" + sink + ")");
    }

    private static void time(final String name, final int iterations, final Runnable body) {
        for (int round = 0; round < 15; round++) {
            for (int i = 0; i < iterations / 4; i++) {
                body.run();
            }
        }
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 6; round++) {
            final long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                body.run();
            }
            final long end = System.nanoTime();
            best = Math.min(best, (end - start) / (double) iterations);
        }
        System.out.printf("%-40s %7.2f ns/post%n", name, best);
    }

    public static class BenchEvent extends Event {
    }

    public static class SubscribeEventHolder extends Event {
    }

    public static class MixedA extends Event {
    }

    public static class MixedB extends Event {
    }

    public static final class SubscribeBench {
        @SubscribeEvent public void a(SubscribeEventHolder e) { sink += 1; }
        @SubscribeEvent public void b(SubscribeEventHolder e) { sink += 1; }
        @SubscribeEvent public void c(SubscribeEventHolder e) { sink += 1; }
        @SubscribeEvent public void d(SubscribeEventHolder e) { sink += 1; }
        @SubscribeEvent public void e(SubscribeEventHolder e) { sink += 1; }
        @SubscribeEvent public void f(SubscribeEventHolder e) { sink += 1; }
        @SubscribeEvent public void g(SubscribeEventHolder e) { sink += 1; }
        @SubscribeEvent public void h(SubscribeEventHolder e) { sink += 1; }
    }
}
