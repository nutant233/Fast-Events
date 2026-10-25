package io.github.nutant.fastevents;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher;

public interface Dispatcher {

    void dispatch(Event event);

    boolean dispatchAndCancelled(Event event);

    boolean dispatchAndCancelled(Event event, IEventBusInvokeDispatcher invoker);

    Dispatcher EMPTY = new Dispatcher() {
        @Override
        public void dispatch(final Event event) {
        }

        @Override
        public boolean dispatchAndCancelled(final Event event) {
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
