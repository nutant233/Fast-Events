// Checks that the generated dispatchers keep the bus's cancellation semantics on NeoForge 8.x: a listener
// registered with receiveCanceled = false must not see an event that an earlier listener cancelled, and a
// listener registered with receiveCanceled = true must see it. The counters for a vanilla post and for a
// FastDispatch post have to match.
package harness;

import io.github.nutant.fastevents.FastDispatch;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class CancelCheck {

    private static int canceledConsumer;
    private static int plainConsumer;
    private static int canceledHandler;
    private static int plainHandler;

    public static void main(final String[] args) {
        final IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(EventPriority.HIGHEST, true, CancelEvent.class, (CancelEvent event) -> event.setCanceled(true));
        bus.addListener(EventPriority.NORMAL, true, CancelEvent.class, (CancelEvent event) -> canceledConsumer++);
        bus.addListener(EventPriority.NORMAL, false, CancelEvent.class, (CancelEvent event) -> plainConsumer++);
        bus.register(new Handler());

        final FastDispatch.Holder holder = FastDispatch.holder();
        boolean ok = true;
        for (int round = 0; round < 3; round++) {
            final int[] vanilla = counts(() -> bus.post(new CancelEvent()));
            final int[] fast = counts(() -> FastDispatch.post(bus, new CancelEvent(), holder));
            if (vanilla[0] != fast[0] || vanilla[1] != fast[1] || vanilla[2] != fast[2] || vanilla[3] != fast[3]) {
                System.out.println("  [FAIL] vanilla=" + java.util.Arrays.toString(vanilla)
                        + " fast=" + java.util.Arrays.toString(fast));
                ok = false;
            }
            if (fast[0] != 1 || fast[1] != 0 || fast[2] != 1 || fast[3] != 0) {
                System.out.println("  [FAIL] unexpected counts " + java.util.Arrays.toString(fast));
                ok = false;
            }
        }
        System.out.println();
        System.out.println(ok ? "CANCEL CHECK PASSED" : "CANCEL CHECK FAILED");
        System.exit(ok ? 0 : 1);
    }

    private static int[] counts(final Runnable post) {
        canceledConsumer = 0;
        plainConsumer = 0;
        canceledHandler = 0;
        plainHandler = 0;
        post.run();
        return new int[]{canceledConsumer, plainConsumer, canceledHandler, plainHandler};
    }

    public static class CancelEvent extends Event implements ICancellableEvent {
    }

    public static final class Handler {
        @SubscribeEvent(receiveCanceled = true)
        public void onReceiveCanceled(final CancelEvent event) {
            canceledHandler++;
        }

        @SubscribeEvent
        public void onPlain(final CancelEvent event) {
            plainHandler++;
        }
    }
}
