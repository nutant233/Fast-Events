// Regression test for the concurrency bug reported in issue #1: the per-call-site cache was mutated
// without synchronisation, so a listener registration (which clears the cache) racing with a post from
// another thread produced ArrayIndexOutOfBoundsException / ClassCastException / lost dispatches.
// Embeddium posts platform events from chunk build threads, which is how this shows up in game.
package harness;

import io.github.nutant.fastevents.FastDispatch;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class RaceCheck {

    private static final int EVENT_CLASSES = 6;
    private static final long RUN_NANOS = 4_000_000_000L;

    private static final ConcurrentLinkedQueue<Throwable> FAILURES = new ConcurrentLinkedQueue<>();

    private RaceCheck() {
    }

    public static void main(final String[] args) throws Exception {
        final IEventBus bus = MinecraftForge.EVENT_BUS;
        final List<Class<? extends Event>> types = new ArrayList<>();
        types.add(RaceEvent0.class);
        types.add(RaceEvent1.class);
        types.add(RaceEvent2.class);
        types.add(RaceEvent3.class);
        types.add(RaceEvent4.class);
        types.add(RaceEvent5.class);

        // one listener per class, asserting it only ever sees its own event class
        for (final Class<? extends Event> type : types) {
            FastDispatch.addListener(bus, EventPriority.NORMAL, false, type, event -> {
                if (event.getClass() != type) {
                    FAILURES.add(new IllegalStateException("listener for " + type.getSimpleName()
                            + " received " + event.getClass().getSimpleName()));
                }
            });
        }

        final FastDispatch.Holder holder = FastDispatch.holder();
        final int threads = 4;
        final Thread[] posters = new Thread[threads];
        final long deadline = System.nanoTime() + RUN_NANOS;
        for (int i = 0; i < threads; i++) {
            final int offset = i;
            posters[i] = new Thread(() -> {
                final long[] count = {0};
                while (System.nanoTime() < deadline && FAILURES.isEmpty()) {
                    final Class<? extends Event> type = types.get((int) ((count[0] + offset) % EVENT_CLASSES));
                    try {
                        FastDispatch.post(bus, newEvent(type), holder);
                    } catch (final Throwable throwable) {
                        FAILURES.add(throwable);
                        return;
                    }
                    count[0]++;
                }
            }, "poster-" + i);
        }

        // a listener registration from another thread clears every cached binding, which is what races
        final Thread registrar = new Thread(() -> {
            while (System.nanoTime() < deadline && FAILURES.isEmpty()) {
                try {
                    FastDispatch.invalidate();
                } catch (final Throwable throwable) {
                    FAILURES.add(throwable);
                    return;
                }
                try {
                    Thread.sleep(1L);
                } catch (final InterruptedException ignored) {
                    return;
                }
            }
        }, "registrar");

        for (final Thread poster : posters) {
            poster.start();
        }
        registrar.start();
        for (final Thread poster : posters) {
            poster.join();
        }
        registrar.join();

        int count = 0;
        for (final Throwable throwable : FAILURES) {
            if (count++ < 3) {
                System.out.println("  [FAIL] " + throwable);
                throwable.printStackTrace(System.out);
            }
        }
        System.out.println();
        System.out.println(FAILURES.isEmpty() ? "RACE CHECK PASSED" : "RACE CHECK FAILED: " + FAILURES.size());
        System.exit(FAILURES.isEmpty() ? 0 : 1);
    }

    private static Event newEvent(final Class<? extends Event> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (final ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static class RaceEvent0 extends Event {
    }

    public static class RaceEvent1 extends Event {
    }

    public static class RaceEvent2 extends Event {
    }

    public static class RaceEvent3 extends Event {
    }

    public static class RaceEvent4 extends Event {
    }

    public static class RaceEvent5 extends Event {
    }
}
