package io.github.nutant.fastevents;

import net.neoforged.bus.api.Event;

public interface Dispatcher {

    void dispatch(Event event);

    Dispatcher EMPTY = event -> {
    };

    final class Failure extends RuntimeException {
        public final int index;

        public Failure(final Throwable cause, final int index) {
            super(null, cause, false, false);
            this.index = index;
        }
    }
}
