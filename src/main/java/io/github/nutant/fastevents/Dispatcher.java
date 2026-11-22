package io.github.nutant.fastevents;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher;

public interface Dispatcher {

    /**
     * Dispatches the event and returns the flag {@code IEventBus#post} would return: whether the event is
     * cancelable and was cancelled. Returning it from the same call the caller already makes keeps the post
     * path to a single virtual call instead of a wrapper that calls back into {@code dispatch}.
     */
    boolean dispatch(Event event);

    boolean dispatchAndCancelled(Event event, IEventBusInvokeDispatcher invoker);

    Dispatcher EMPTY = new Dispatcher() {
        @Override
        public boolean dispatch(final Event event) {
            return false;
        }

        @Override
        public boolean dispatchAndCancelled(final Event event, final IEventBusInvokeDispatcher invoker) {
            return false;
        }
    };

    final class Failure extends RuntimeException {
        public final int index;

        public Failure(final Throwable cause, final int index) {
            super(null, cause, false, false);
            this.index = index;
        }
    }
}
