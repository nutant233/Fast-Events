// Java 8 smoke test and micro benchmark for the 1.16.5 build.
// Compiled to Java 8 bytecode and run on the Java 8 runtime that 1.16.5 actually uses, so the
// ClassLoader-based dispatcher definition (the only Java-8-specific code path) is exercised for real.
// The numbers printed here are the ones quoted in this branch's README.
package smoke;

import io.github.nutant.fastevents.FastDispatch;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Smoke {

    private static int failures;
    static long sink;

    private Smoke() {
    }

    public static void main(final String[] args) throws Exception {
        final IEventBus bus = net.minecraftforge.common.MinecraftForge.EVENT_BUS;

        final int[] hits = {0};
        for (int i = 0; i < 8; i++) {
            FastDispatch.addListener(bus, EventPriority.NORMAL, false, Plain.class, e -> hits[0]++);
        }
        FastDispatch.post(bus, new Plain(), FastDispatch.holder());
        check(hits[0] == 8, "8 consumer listeners dispatch, got " + hits[0]);

        final List<String> order = new ArrayList<String>();
        FastDispatch.addListener(bus, EventPriority.HIGH, false, Ordered.class, e -> order.add("high"));
        FastDispatch.addListener(bus, EventPriority.LOW, false, Ordered.class, e -> order.add("low"));
        FastDispatch.addListener(bus, EventPriority.NORMAL, false, Ordered.class, e -> order.add("normal"));
        FastDispatch.post(bus, new Ordered(), FastDispatch.holder());
        check(order.equals(Arrays.asList("high", "normal", "low")), "priority order, got " + order);

        final int[] cancelledHits = {0};
        FastDispatch.addListener(bus, EventPriority.NORMAL, false, Cancellable.class, e -> cancelledHits[0]++);
        final Cancellable cancelled = new Cancellable();
        cancelled.setCanceled(true);
        check(FastDispatch.post(bus, cancelled, FastDispatch.holder()), "cancelled post returns true");
        check(cancelledHits[0] == 0, "cancelled event skips normal listeners, got " + cancelledHits[0]);

        final int[] lateHits = {0};
        FastDispatch.post(bus, new LateJoin(), FastDispatch.holder());
        FastDispatch.addListener(bus, EventPriority.NORMAL, false, LateJoin.class, e -> lateHits[0]++);
        FastDispatch.post(bus, new LateJoin(), FastDispatch.holder());
        check(lateHits[0] == 1, "listener registered after the first dispatch fires, got " + lateHits[0]);

        check(FastDispatch.hasListeners(bus, Plain.class, null), "null holder guard stays conservative");
        final int before = hits[0];
        FastDispatch.post(bus, new Plain(), null);
        check(hits[0] == before + 8, "null holder falls back to the vanilla post");

        final IEventBus privateBus = new net.minecraftforge.eventbus.EventBus(
                net.minecraftforge.eventbus.api.BusBuilder.builder());
        final int[] privateHits = {0};
        privateBus.addListener(EventPriority.NORMAL, false, Plain.class, e -> privateHits[0]++);
        FastDispatch.post(privateBus, new Plain(), FastDispatch.holder());
        check(privateHits[0] == 1, "a private bus keeps its own listeners, got " + privateHits[0]);

        System.out.println();
        System.out.println("Java " + System.getProperty("java.version")
                + ", one thread, ns per post, 8 listeners unless stated");
        consumers(bus);
        subscribers(bus);
        wide(bus, 128, 60_000);
        wide(bus, 512, 20_000);

        System.out.println();
        System.out.println(failures == 0 ? "SMOKE PASSED" : ("SMOKE FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void consumers(final IEventBus bus) {
        final int[] vanillaHits = {0};
        for (int i = 0; i < 8; i++) {
            bus.addListener(EventPriority.NORMAL, false, ConsumerV.class, e -> vanillaHits[0]++);
        }
        final int[] fastHits = {0};
        for (int i = 0; i < 8; i++) {
            FastDispatch.addListener(bus, EventPriority.NORMAL, false, ConsumerF.class, e -> fastHits[0]++);
        }
        final FastDispatch.Holder holder = FastDispatch.holder();
        report("addListener(consumer)", 200_000, 500_000,
                run(500_000, 200_000, new Runnable() {
                    @Override
                    public void run() {
                        bus.post(new ConsumerV());
                    }
                }),
                run(500_000, 200_000, new Runnable() {
                    @Override
                    public void run() {
                        FastDispatch.post(bus, new ConsumerF(), holder);
                    }
                }));
        check(vanillaHits[0] == fastHits[0], "both paths dispatched the same number of events");
    }

    private static void subscribers(final IEventBus bus) {
        bus.register(new SubscriberV());
        bus.register(new SubscriberF());
        final FastDispatch.Holder holder = FastDispatch.holder();
        report("@SubscribeEvent", 200_000, 500_000,
                run(500_000, 200_000, new Runnable() {
                    @Override
                    public void run() {
                        bus.post(new SubV());
                    }
                }),
                run(500_000, 200_000, new Runnable() {
                    @Override
                    public void run() {
                        FastDispatch.post(bus, new SubF(), holder);
                    }
                }));
    }

    private static void wide(final IEventBus bus, final int count, final int iterations) {
        for (int i = 0; i < count; i++) {
            bus.addListener(EventPriority.NORMAL, false, WideV.class, e -> sink += 1);
        }
        for (int i = 0; i < count; i++) {
            FastDispatch.addListener(bus, EventPriority.NORMAL, false, WideF.class, e -> sink += 1);
        }
        final FastDispatch.Holder holder = FastDispatch.holder();
        final int warmup = 20_000;
        report(count + " listeners", warmup, iterations,
                run(iterations, warmup, new Runnable() {
                    @Override
                    public void run() {
                        bus.post(new WideV());
                    }
                }),
                run(iterations, warmup, new Runnable() {
                    @Override
                    public void run() {
                        FastDispatch.post(bus, new WideF(), holder);
                    }
                }));
    }

    private static void report(final String label, final int warmup, final int iterations,
                               final long vanilla, final long fast) {
        System.out.println(String.format("%-22s vanilla %9.1f ns   Fast-Events %8.1f ns   (%d warmup, %d iterations)",
                label, vanilla / (double) iterations, fast / (double) iterations, warmup, iterations));
    }

    private static long run(final int iterations, final int warmup, final Runnable body) {
        for (int i = 0; i < warmup; i++) {
            body.run();
        }
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            body.run();
        }
        return System.nanoTime() - start;
    }

    private static void check(final boolean condition, final String message) {
        if (condition) {
            System.out.println("  [ok]   " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }

    public static class Plain extends Event {
    }

    public static class Ordered extends Event {
    }

    public static class LateJoin extends Event {
    }

    public static class ConsumerV extends Event {
    }

    public static class ConsumerF extends Event {
    }

    public static class SubV extends Event {
    }

    public static class SubF extends Event {
    }

    public static class WideV extends Event {
    }

    public static class WideF extends Event {
    }

    @Cancelable
    public static class Cancellable extends Event {
        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    public static final class SubscriberV {
        @SubscribeEvent public void a(final SubV event) { sink += 1; }
        @SubscribeEvent public void b(final SubV event) { sink += 1; }
        @SubscribeEvent public void c(final SubV event) { sink += 1; }
        @SubscribeEvent public void d(final SubV event) { sink += 1; }
        @SubscribeEvent public void e(final SubV event) { sink += 1; }
        @SubscribeEvent public void f(final SubV event) { sink += 1; }
        @SubscribeEvent public void g(final SubV event) { sink += 1; }
        @SubscribeEvent public void h(final SubV event) { sink += 1; }
    }

    public static final class SubscriberF {
        @SubscribeEvent public void a(final SubF event) { sink += 1; }
        @SubscribeEvent public void b(final SubF event) { sink += 1; }
        @SubscribeEvent public void c(final SubF event) { sink += 1; }
        @SubscribeEvent public void d(final SubF event) { sink += 1; }
        @SubscribeEvent public void e(final SubF event) { sink += 1; }
        @SubscribeEvent public void f(final SubF event) { sink += 1; }
        @SubscribeEvent public void g(final SubF event) { sink += 1; }
        @SubscribeEvent public void h(final SubF event) { sink += 1; }
    }
}
