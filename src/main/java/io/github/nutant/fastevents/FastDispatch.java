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
        Dispatcher dispatcher = lookup(holder, type);
        if (dispatcher == null) {
            dispatcher = resolve(bus, type, holder);
            if (dispatcher == null) {
                return true;
            }
        }
        return dispatcher != Dispatcher.EMPTY;
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
        Dispatcher dispatcher = lookup(holder, type);
        if (dispatcher == null) {
            if (AUDIT && holder.dispatcher != null) {
                LOGGER.warn("[FastEvents] dispatcher rebound from {} to {}", holder.type, type);
            }
            dispatcher = resolve(bus, type, holder);
        } else if (AUDIT) {
            dispatcher = audit(bus, type, holder, dispatcher);
        }
        return dispatcher;
    }

    private static void missingHolder() {
        if (missingHolderReported) {
            return;
        }
        missingHolderReported = true;
        LOGGER.error("[FastEvents] a call site has no holder instance, it falls back to IEventBus.post",
                new Throwable("holder missing"));
    }

    private static Dispatcher lookup(final Holder holder, final Class<?> type) {
        if (holder.dispatcher != null && holder.type == type) {
            return holder.dispatcher;
        }
        final Class<?>[] restType = holder.restType;
        if (restType == null) {
            return null;
        }
        final int size = holder.size;
        for (int i = 0; i < size; i++) {
            if (restType[i] == type) {
                return holder.restDispatcher[i];
            }
        }
        return null;
    }

    private static void store(final Holder holder, final Class<?> type, final EventListener[] original,
                              final Dispatcher dispatcher) {
        final Class<?> previousType = holder.type;
        final EventListener[] previousOriginal = holder.original;
        final Dispatcher previous = holder.dispatcher;
        holder.type = type;
        holder.original = original;
        holder.dispatcher = dispatcher;
        if (previous == null) {
            return;
        }
        final int size = holder.size;
        Class<?>[] restType = holder.restType;
        if (restType == null) {
            holder.restType = restType = new Class<?>[3];
            holder.restDispatcher = new Dispatcher[3];
            holder.restOriginal = new EventListener[3][];
        } else if (size == restType.length) {
            final int grown = size + 3;
            holder.restType = restType = java.util.Arrays.copyOf(restType, grown);
            holder.restDispatcher = java.util.Arrays.copyOf(holder.restDispatcher, grown);
            holder.restOriginal = java.util.Arrays.copyOf(holder.restOriginal, grown);
        }
        restType[size] = previousType;
        holder.restDispatcher[size] = previous;
        holder.restOriginal[size] = previousOriginal;
        holder.size = size + 1;
    }

    private static EventListener[] originalOf(final Holder holder, final Dispatcher dispatcher) {
        if (dispatcher == holder.dispatcher) {
            return holder.original == null ? EMPTY : holder.original;
        }
        final Dispatcher[] restDispatcher = holder.restDispatcher;
        if (restDispatcher != null) {
            final int size = holder.size;
            for (int i = 0; i < size; i++) {
                if (restDispatcher[i] == dispatcher) {
                    final EventListener[] original = holder.restOriginal[i];
                    return original == null ? EMPTY : original;
                }
            }
        }
        return EMPTY;
    }

    private static void handleException(final IEventBus bus, final Event event, final Holder holder,
                                        final Dispatcher dispatcher, final Dispatcher.Failure failure) {
        final IEventExceptionHandler handler = Access.exceptionHandler(bus);
        if (handler != null) {
            handler.handleException(bus, event, originalOf(holder, dispatcher), failure.index, failure.getCause());
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
        holder.type = null;
        holder.original = null;
        holder.dispatcher = null;
        holder.restType = null;
        holder.restDispatcher = null;
        holder.restOriginal = null;
        holder.size = 0;
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
        store(holder, type, original, entry.dispatcher);
        anyCached = true;
        return entry.dispatcher;
    }

    private static Dispatcher audit(final IEventBus bus, final Class<?> type, final Holder holder,
                                    final Dispatcher cached) {
        final EventListener[] original = originalOf(holder, cached);
        if (original.length == 0) {
            return cached;
        }
        final EventListener[] current = Access.listeners(bus, type);
        if (current != original) {
            System.err.println("[FastEvents] stale listener array for " + type.getName()
                    + " (" + original.length + " -> " + (current == null ? -1 : current.length)
                    + "); late registration detected");
            final Dispatcher fresh = resolve(bus, type, holder);
            return fresh == null ? cached : fresh;
        }
        return cached;
    }

    public static int debugDispatchers() {
        return DISPATCHERS.size();
    }

    public static final class Holder {
        Class<?> type;
        EventListener[] original;
        Dispatcher dispatcher;
        Class<?>[] restType;
        Dispatcher[] restDispatcher;
        EventListener[][] restOriginal;
        int size;
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
