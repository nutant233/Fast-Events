package io.github.nutant.fastevents;

import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.IEventExceptionHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public final class FastDispatch {

    static final EventListener[] EMPTY = new EventListener[0];

    static final boolean AUDIT = Boolean.getBoolean("fastevents.audit");

    private static final Logger LOGGER = LogManager.getLogger("FastEvents");

    private static final ArrayList<Holder> HOLDERS = new ArrayList<>();

    private static final ConcurrentHashMap<Key, Entry> DISPATCHERS = new ConcurrentHashMap<>();

    private static volatile boolean anyCached;

    private static volatile boolean missingHolderReported;

    private FastDispatch() {
    }

    public static Holder holder() {
        final Holder holder = new Holder();
        synchronized (HOLDERS) {
            HOLDERS.add(holder);
        }
        return holder;
    }

    public static boolean hasListeners(final IEventBus bus, final Class<? extends Event> type,
                                       final Holder holder) {
        if (holder == null) {
            missingHolder();
            return true;
        }
        final Binding cached = lookup(holder, type);
        if (cached != null) {
            return cached.dispatcher != Dispatcher.EMPTY;
        }
        final Dispatcher dispatcher = resolve(bus, type, holder);
        return dispatcher == null || dispatcher != Dispatcher.EMPTY;
    }

    public static <T extends Event> T post(final IEventBus bus, final T event, final Holder holder) {
        final Dispatcher dispatcher = peek(bus, event, holder);
        if (dispatcher == null) {
            return bus.post(event);
        }
        try {
            dispatcher.dispatch(event);
        } catch (final Dispatcher.Failure failure) {
            handleException(bus, event, holder, dispatcher, failure);
        }
        return event;
    }

    public static void register(final IEventBus bus, final Object target) {
        bus.register(target);
        invalidate();
    }

    public static void unregister(final IEventBus bus, final Object target) {
        bus.unregister(target);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final Consumer<T> consumer) {
        bus.addListener(consumer);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final Class<T> eventType,
                                                     final Consumer<T> consumer) {
        bus.addListener(eventType, consumer);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final EventPriority priority,
                                                     final Consumer<T> consumer) {
        bus.addListener(priority, consumer);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final EventPriority priority,
                                                     final Class<T> eventType, final Consumer<T> consumer) {
        bus.addListener(priority, eventType, consumer);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final EventPriority priority,
                                                     final boolean receiveCanceled, final Consumer<T> consumer) {
        bus.addListener(priority, receiveCanceled, consumer);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final EventPriority priority,
                                                     final boolean receiveCanceled, final Class<T> eventType,
                                                     final Consumer<T> consumer) {
        bus.addListener(priority, receiveCanceled, eventType, consumer);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final boolean receiveCanceled,
                                                     final Consumer<T> consumer) {
        bus.addListener(receiveCanceled, consumer);
        invalidate();
    }

    public static <T extends Event> void addListener(final IEventBus bus, final boolean receiveCanceled,
                                                     final Class<T> eventType, final Consumer<T> consumer) {
        bus.addListener(receiveCanceled, eventType, consumer);
        invalidate();
    }

    private static Dispatcher peek(final IEventBus bus, final Event event, final Holder holder) {
        if (holder == null) {
            missingHolder();
            return null;
        }
        final Class<?> type = event.getClass();
        final Binding current = holder.binding;
        final Binding cached = current == null ? null : current.find(type);
        if (cached == null) {
            if (AUDIT && current != null) {
                LOGGER.warn("[FastEvents] dispatcher rebound from {} to {}", current.type, type);
            }
            return resolve(bus, type, holder);
        }
        return AUDIT ? audit(bus, type, holder, cached) : cached.dispatcher;
    }

    private static void missingHolder() {
        if (missingHolderReported) {
            return;
        }
        missingHolderReported = true;
        LOGGER.error("[FastEvents] a call site has no holder instance, it falls back to IEventBus.post",
                new Throwable("holder missing"));
    }

    /** The binding this call site has cached for {@code type}, or null. */
    private static Binding lookup(final Holder holder, final Class<?> type) {
        final Binding current = holder.binding;
        return current == null ? null : current.find(type);
    }

    /**
     * Publishes a freshly built binding. The snapshot is immutable and swapped in through a single volatile
     * write, so a post from another thread either sees the previous snapshot, which makes it resolve again,
     * or the new one. It can never observe a half-updated cache, which is what produced
     * ArrayIndexOutOfBoundsException and mis-dispatches before.
     */
    private static void publish(final Holder holder, final Class<?> type, final EventListener[] original,
                                final Dispatcher dispatcher) {
        final Binding current = holder.binding;
        holder.binding = current == null
                ? new Binding(type, original, dispatcher, null)
                : current.push(type, original, dispatcher);
    }

    private static EventListener[] originalOf(final Binding binding, final Dispatcher dispatcher) {
        for (Binding current = binding; current != null; current = current.next) {
            if (current.dispatcher == dispatcher) {
                return current.original == null ? EMPTY : current.original;
            }
        }
        return EMPTY;
    }

    private static void handleException(final IEventBus bus, final Event event, final Holder holder,
                                        final Dispatcher dispatcher, final Dispatcher.Failure failure) {
        final IEventExceptionHandler handler = Access.exceptionHandler(bus);
        if (handler != null) {
            handler.handleException(bus, event, originalOf(holder.binding, dispatcher), failure.index, failure.getCause());
        }
        FastDispatch.sneakyThrow(failure.getCause());
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
        throw (T) throwable;
    }

    public static void invalidate() {
        if (!anyCached) {
            return;
        }
        synchronized (HOLDERS) {
            for (final Holder holder : HOLDERS) {
                clear(holder);
            }
        }
        DISPATCHERS.clear();
    }

    private static void clear(final Holder holder) {
        holder.binding = null;
    }

    private static Dispatcher resolve(final IEventBus bus, final Class<?> type, final Holder holder) {
        if (Access.shutdown(bus)) {
            return null;
        }
        final EventListener[] original = Access.listeners(bus, type);
        if (original == null) {
            return null;
        }
        final Key key = new Key(bus, type);
        Entry entry = DISPATCHERS.get(key);
        if (entry == null || entry.original != original) {
            synchronized (DISPATCHERS) {
                entry = DISPATCHERS.get(key);
                if (entry == null || entry.original != original) {
                    entry = new Entry(original, DispatchGen.create(type, original));
                    DISPATCHERS.put(key, entry);
                }
            }
        }
        publish(holder, type, original, entry.dispatcher);
        anyCached = true;
        return entry.dispatcher;
    }

    private static Dispatcher audit(final IEventBus bus, final Class<?> type, final Holder holder,
                                    final Binding cached) {
        final EventListener[] original = cached.original == null ? EMPTY : cached.original;
        if (original.length == 0) {
            return cached.dispatcher;
        }
        final EventListener[] current = Access.listeners(bus, type);
        if (current != original) {
            System.err.println("[FastEvents] stale listener array for " + type.getName()
                    + " (" + original.length + " -> " + (current == null ? -1 : current.length)
                    + "); late registration detected");
            final Dispatcher fresh = resolve(bus, type, holder);
            return fresh == null ? cached.dispatcher : fresh;
        }
        return cached.dispatcher;
    }

    public static int debugDispatchers() {
        return DISPATCHERS.size();
    }

    /**
     * One call site's cache: a single immutable snapshot, replaced atomically. See {@link #publish} for why
     * it must not be mutated in place.
     */
    public static final class Holder {
        volatile Binding binding;
    }

    /** Immutable: what one call site resolved for one event class, plus the other classes it saw earlier. */
    static final class Binding {
        /** Enough for the handful of event classes a single call site realistically posts. */
        static final int MAX_CHAIN = 8;

        final Class<?> type;
        final EventListener[] original;
        final Dispatcher dispatcher;
        final Binding next;

        Binding(final Class<?> type, final EventListener[] original, final Dispatcher dispatcher, final Binding next) {
            this.type = type;
            this.original = original;
            this.dispatcher = dispatcher;
            this.next = next;
        }

        Binding find(final Class<?> type) {
            for (Binding current = this; current != null; current = current.next) {
                if (current.type == type) {
                    return current;
                }
            }
            return null;
        }

        /**
         * Returns a new snapshot with {@code type} in front. The previous entries are copied so the result is
         * fully immutable; entries beyond {@link #MAX_CHAIN} are dropped and simply re-resolve later.
         */
        Binding push(final Class<?> type, final EventListener[] original, final Dispatcher dispatcher) {
            if (this.type == type && this.original == original && this.dispatcher == dispatcher) {
                return this;
            }
            final int capacity = MAX_CHAIN - 1;
            final Class<?>[] types = new Class<?>[capacity];
            final EventListener[][] originals = new EventListener[capacity][];
            final Dispatcher[] dispatchers = new Dispatcher[capacity];
            int count = 0;
            for (Binding current = this; current != null && count < capacity; current = current.next) {
                if (current.type == type) {
                    continue;
                }
                types[count] = current.type;
                originals[count] = current.original;
                dispatchers[count] = current.dispatcher;
                count++;
            }
            Binding result = new Binding(type, original, dispatcher, null);
            for (int i = count - 1; i >= 0; i--) {
                result = new Binding(types[i], originals[i], dispatchers[i], result);
            }
            return result;
        }
    }

    private static final class Entry {
        final EventListener[] original;
        final Dispatcher dispatcher;

        Entry(final EventListener[] original, final Dispatcher dispatcher) {
            this.original = original;
            this.dispatcher = dispatcher;
        }
    }

    private static final class Key {
        final IEventBus bus;
        final Class<?> type;

        Key(final IEventBus bus, final Class<?> type) {
            this.bus = bus;
            this.type = type;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Key key && key.bus == bus && key.type == type;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(bus) * 31 + type.hashCode();
        }
    }

    static final class Access {
        static final MethodHandle LISTENERS;
        static final MethodHandle SHUTDOWN;
        static final MethodHandle EXCEPTION_HANDLER;

        static {
            MethodHandle listeners = null;
            MethodHandle shutdown = null;
            MethodHandle exceptionHandler = null;
            try {
                final MethodHandles.Lookup lookup =
                        MethodHandles.privateLookupIn(EventBus.class, MethodHandles.lookup());
                listeners = lookup.findVirtual(EventBus.class, "getListenerList",
                        MethodType.methodType(ListenerList.class, Class.class));
                shutdown = lookup.findGetter(EventBus.class, "shutdown", boolean.class);
                exceptionHandler = lookup.findGetter(EventBus.class, "exceptionHandler",
                        IEventExceptionHandler.class);
            } catch (final Throwable failed) {
                LogManager.getLogger("FastEvents").error(
                        "[FastEvents] cannot reach the event bus internals, this install is inactive", failed);
            }
            LISTENERS = listeners;
            SHUTDOWN = shutdown;
            EXCEPTION_HANDLER = exceptionHandler;
        }

        static EventListener[] listeners(final IEventBus bus, final Class<?> type) {
            final MethodHandle handle = LISTENERS;
            if (handle == null || !(bus instanceof EventBus impl)) {
                return null;
            }
            try {
                final ListenerList list = (ListenerList) handle.invokeExact(impl, (Class<?>) type);
                return list.getListeners();
            } catch (final Throwable failed) {
                return null;
            }
        }

        static boolean shutdown(final IEventBus bus) {
            final MethodHandle handle = SHUTDOWN;
            if (handle == null || !(bus instanceof EventBus impl)) {
                return false;
            }
            try {
                return (boolean) handle.invokeExact(impl);
            } catch (final Throwable failed) {
                return false;
            }
        }

        static IEventExceptionHandler exceptionHandler(final IEventBus bus) {
            final MethodHandle handle = EXCEPTION_HANDLER;
            if (handle == null || !(bus instanceof EventBus impl)) {
                return bus instanceof IEventExceptionHandler handler ? handler : null;
            }
            try {
                return (IEventExceptionHandler) handle.invokeExact(impl);
            } catch (final Throwable failed) {
                return null;
            }
        }
    }
}
