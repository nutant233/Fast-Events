package io.github.nutant.fastevents;

import net.jodah.typetools.TypeResolver;
import net.minecraftforge.eventbus.ASMEventHandler;
import net.minecraftforge.eventbus.EventBus;
import net.minecraftforge.eventbus.ListenerList;
import net.minecraftforge.eventbus.api.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@SuppressWarnings("unused")
public final class FastDispatch {

    static final IEventListener[] EMPTY = new IEventListener[0];

    static final boolean AUDIT = Boolean.getBoolean("fastevents.audit");

    static final boolean DIAG = Boolean.getBoolean("fastevents.diag");

    private static final Logger LOGGER = LogManager.getLogger("FastEvents");

    private static final ArrayList<Holder> HOLDERS = new ArrayList<>();

    private static final ConcurrentHashMap<Key, Entry> DISPATCHERS = new ConcurrentHashMap<>();

    private static volatile boolean anyCached;

    private static final ConcurrentHashMap<Object, List<Registration>> REGISTERED = new ConcurrentHashMap<>();

    private FastDispatch() {
    }

    public static Holder holder() {
        final Holder holder = new Holder();
        synchronized (HOLDERS) {
            HOLDERS.add(holder);
        }
        return holder;
    }

    public static boolean hasListeners(final IEventBus bus, final Class<? extends Event> type, final Holder holder) {
        if (holder == null) {
            return true;
        }
        Dispatcher dispatcher = lookup(holder, type);
        if (dispatcher == null) {
            dispatcher = resolveForType(bus, type, holder);
            if (dispatcher == null) {
                return true;
            }
        }
        return dispatcher != Dispatcher.EMPTY;
    }

    public static boolean post(final IEventBus bus, final Event event, final Holder holder) {
        final Dispatcher dispatcher = peek(bus, event, holder);
        if (dispatcher == null) {
            return bus.post(event);
        }
        try {
            return dispatcher.dispatchAndCancelled(event);
        } catch (final Dispatcher.Failure failure) {
            handleException(bus, event, holder, dispatcher, failure);
            return false;
        }
    }

    public static void postVoid(final IEventBus bus, final Event event, final Holder holder) {
        final Dispatcher dispatcher = peek(bus, event, holder);
        if (dispatcher == null) {
            bus.post(event);
            return;
        }
        try {
            dispatcher.dispatch(event);
        } catch (final Dispatcher.Failure failure) {
            handleException(bus, event, holder, dispatcher, failure);
        }
    }

    public static boolean postWrapped(final IEventBus bus, final Event event,
                                      final IEventBusInvokeDispatcher invoker, final Holder holder) {
        final Dispatcher dispatcher = peek(bus, event, holder);
        if (dispatcher == null) {
            return bus.post(event, invoker);
        }
        try {
            return dispatcher.dispatchAndCancelled(event, invoker);
        } catch (final Dispatcher.Failure failure) {
            handleException(bus, event, holder, dispatcher, failure);
            return false;
        }
    }

    private static Dispatcher peek(final IEventBus bus, final Event event, final Holder holder) {
        if (holder == null) {
            return null;
        }
        final Class<?> type = event.getClass();
        Dispatcher dispatcher = lookup(holder, type);
        if (dispatcher == null) {
            if (AUDIT && holder.dispatcher != null) {
                LOGGER.warn("[FastEvents] dispatcher rebound from {} to {}", holder.type, type);
            }
            dispatcher = resolve(bus, event, holder);
        } else if (AUDIT) {
            dispatcher = audit(bus, event, holder, dispatcher);
        }
        return dispatcher;
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

    private static void store(final Holder holder, final Class<?> type, final IEventListener[] original,
                              final Dispatcher dispatcher) {
        final Class<?> previousType = holder.type;
        final IEventListener[] previousOriginal = holder.original;
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
            holder.restOriginal = new IEventListener[3][];
        } else if (size == restType.length) {
            final int grown = size + 3;
            holder.restType = restType = Arrays.copyOf(restType, grown);
            holder.restDispatcher = Arrays.copyOf(holder.restDispatcher, grown);
            holder.restOriginal = Arrays.copyOf(holder.restOriginal, grown);
        }
        restType[size] = previousType;
        holder.restDispatcher[size] = previous;
        holder.restOriginal[size] = previousOriginal;
        holder.size = size + 1;
    }

    private static IEventListener[] originalOf(final Holder holder, final Dispatcher dispatcher) {
        if (dispatcher == holder.dispatcher) {
            return holder.original == null ? EMPTY : holder.original;
        }
        final Dispatcher[] restDispatcher = holder.restDispatcher;
        if (restDispatcher != null) {
            final int size = holder.size;
            for (int i = 0; i < size; i++) {
                if (restDispatcher[i] == dispatcher) {
                    final IEventListener[] original = holder.restOriginal[i];
                    return original == null ? EMPTY : original;
                }
            }
        }
        return EMPTY;
    }

    private static void handleException(final IEventBus bus, final Event event, final Holder holder,
                                        final Dispatcher dispatcher, final Dispatcher.Failure failure) {
        if (bus instanceof EventBus impl) {
            impl.handleException(impl, event, originalOf(holder, dispatcher), failure.index, failure.getCause());
        }
        FastDispatch.sneakyThrow(failure.getCause());
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
        throw (T) throwable;
    }

    public static <T extends Event> void addListener(final IEventBus bus, final Consumer<T> consumer) {
        addListener(bus, EventPriority.NORMAL, false, consumer);
    }

    public static <T extends Event> void addListener(final IEventBus bus, final EventPriority priority,
                                                     final Consumer<T> consumer) {
        addListener(bus, priority, false, consumer);
    }

    public static <T extends Event> void addListener(final IEventBus bus, final EventPriority priority,
                                                     final boolean receiveCancelled, final Consumer<T> consumer) {
        final Class<T> eventType = resolveType(consumer);
        if (eventType == null) {
            bus.addListener(priority, receiveCancelled, consumer);
            return;
        }
        addListener(bus, priority, receiveCancelled, eventType, consumer);
    }

    public static <T extends Event> void addListener(final IEventBus bus, final EventPriority priority,
                                                     final boolean receiveCancelled, final Class<T> eventType,
                                                     final Consumer<T> consumer) {
        if (optimizable(bus, eventType)) {
            final int id = busId(bus);
            if (id >= 0) {
                final ListenerList list = EventListenerHelper.getListenerList(eventType);
                final ConsumerListener<T> listener = new ConsumerListener<>(consumer, receiveCancelled);
                list.register(id, priority, listener);
                REGISTERED.computeIfAbsent(consumer, key -> Collections.synchronizedList(new ArrayList<>(2)))
                        .add(new Registration(list, eventType, listener));
                invalidateClass(eventType, id);
                return;
            }
        }
        bus.addListener(priority, receiveCancelled, eventType, consumer);
    }

    public static <T extends GenericEvent<? extends F>, F> void addGenericListener(final IEventBus bus,
                                                                                   final Class<F> filter,
                                                                                   final Consumer<T> consumer) {
        addGenericListener(bus, filter, EventPriority.NORMAL, false, consumer);
    }

    public static <T extends GenericEvent<? extends F>, F> void addGenericListener(final IEventBus bus,
                                                                                   final Class<F> filter,
                                                                                   final EventPriority priority,
                                                                                   final Consumer<T> consumer) {
        addGenericListener(bus, filter, priority, false, consumer);
    }

    public static <T extends GenericEvent<? extends F>, F> void addGenericListener(final IEventBus bus,
                                                                                   final Class<F> filter,
                                                                                   final EventPriority priority,
                                                                                   final boolean receiveCancelled,
                                                                                   final Consumer<T> consumer) {
        final Class<T> eventType = resolveType(consumer);
        if (eventType == null) {
            bus.addGenericListener(filter, priority, receiveCancelled, consumer);
            return;
        }
        addGenericListener(bus, filter, priority, receiveCancelled, eventType, consumer);
    }

    public static <T extends GenericEvent<? extends F>, F> void addGenericListener(final IEventBus bus,
                                                                                   final Class<F> filter,
                                                                                   final EventPriority priority,
                                                                                   final boolean receiveCancelled,
                                                                                   final Class<T> eventType,
                                                                                   final Consumer<T> consumer) {
        if (optimizable(bus, eventType)) {
            final int id = busId(bus);
            if (id >= 0) {
                final ListenerList list = EventListenerHelper.getListenerList(eventType);
                final ConsumerListener<T> listener = new ConsumerListener<>(consumer, receiveCancelled, filter);
                list.register(id, priority, listener);
                REGISTERED.computeIfAbsent(consumer, key -> Collections.synchronizedList(new ArrayList<>(2)))
                        .add(new Registration(list, eventType, listener));
                invalidateClass(eventType, id);
                return;
            }
        }
        bus.addGenericListener(filter, priority, receiveCancelled, eventType, consumer);
    }

    public static void unregister(final IEventBus bus, final Object object) {
        final List<Registration> ours = REGISTERED.remove(object);
        if (ours != null) {
            final int id = busId(bus);
            if (id >= 0) {
                for (final Registration registration : ours) {
                    registration.list.unregister(id, registration.listener);
                    invalidateClass(registration.type, id);
                }
            }
        }
        bus.unregister(object);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Event> Class<T> resolveType(final Consumer<T> consumer) {
        try {
            final Class<?> type = TypeResolver.resolveRawArgument(Consumer.class, consumer.getClass());
            if (type == null || type == TypeResolver.Unknown.class) {
                return null;
            }
            return (Class<T>) type;
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static void report(final Class<? extends Event> type, final IEventListener[] listeners) {
        for (final IEventListener listener : listeners) {
            if (listener == null || listener instanceof EventPriority || listener instanceof ConsumerListener
                    || listener instanceof DirectListener) {
                continue;
            }
            LOGGER.warn("[FastEvents] listener {} ({}) on {} was registered through a non-rewritten call site",
                    listener.listenerName(), listener.getClass().getName(), type.getName());
        }
    }

    private static boolean optimizable(final IEventBus bus, final Class<?> eventType) {
        return Access.OK
                && eventType != Event.class
                && bus instanceof EventBus;
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

    static void invalidateClass(final Class<?> type, final int busId) {
        if (!anyCached) {
            return;
        }
        synchronized (HOLDERS) {
            for (final Holder holder : HOLDERS) {
                if (matches(holder, type)) {
                    clear(holder);
                }
            }
        }
        DISPATCHERS.keySet().removeIf(key -> key.busId == busId && type.isAssignableFrom(key.type));
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

    private static boolean matches(final Holder holder, final Class<?> type) {
        if (holder.dispatcher != null && holder.type != null && type.isAssignableFrom(holder.type)) {
            return true;
        }
        final Class<?>[] restType = holder.restType;
        if (restType != null) {
            final int size = holder.size;
            for (int i = 0; i < size; i++) {
                if (type.isAssignableFrom(restType[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Dispatcher resolve(final IEventBus bus, final Event event, final Holder holder) {
        if (isShutdown(bus)) {
            return null;
        }
        final int id = busId(bus);
        if (id < 0) {
            return null;
        }
        return resolve(bus, event.getClass(), id, event.getListenerList().getListeners(id), holder);
    }

    private static Dispatcher resolveForType(final IEventBus bus, final Class<? extends Event> type,
                                             final Holder holder) {
        if (isShutdown(bus)) {
            return null;
        }
        final int id = busId(bus);
        if (id < 0) {
            return null;
        }
        return resolve(bus, type, id, EventListenerHelper.getListenerList(type).getListeners(id), holder);
    }

    private static Dispatcher resolve(final IEventBus bus, final Class<? extends Event> type, final int id,
                                      final IEventListener[] original, final Holder holder) {
        final Key key = new Key(type, id);
        Entry entry = DISPATCHERS.get(key);
        if (entry == null || entry.original != original) {
            synchronized (DISPATCHERS) {
                entry = DISPATCHERS.get(key);
                if (entry == null || entry.original != original) {
                    final IEventListener[] flattened = Flatten.apply(original);
                    if (DIAG) {
                        report(type, flattened);
                    }
                    entry = new Entry(original, DispatchGen.create(type, flattened));
                    DISPATCHERS.put(key, entry);
                }
            }
        }
        store(holder, type, original, entry.dispatcher);
        anyCached = true;
        return entry.dispatcher;
    }

    private static Dispatcher audit(final IEventBus bus, final Event event, final Holder holder,
                                    final Dispatcher cached) {
        final int id = busId(bus);
        final IEventListener[] original = originalOf(holder, cached);
        if (original.length == 0) {
            return cached;
        }
        final IEventListener[] current = event.getListenerList().getListeners(id);
        if (current != original) {
            LOGGER.error("[FastEvents] stale listener array for {} ({} -> {}); late registration detected", event.getClass().getName(), original.length, current.length);
            final Dispatcher fresh = resolve(bus, event, holder);
            return fresh == null ? cached : fresh;
        }
        return cached;
    }

    private static int busId(final IEventBus bus) {
        if (Access.OK && bus instanceof EventBus impl) {
            return Access.UNSAFE.getInt(impl, Access.BUSID);
        }
        return Probe.probe(bus);
    }

    static boolean isShutdown(final IEventBus bus) {
        return Access.OK && bus instanceof EventBus impl && Access.UNSAFE.getBooleanVolatile(impl, Access.SHUTDOWN);
    }

    public static int debugGenericSteps() {
        return DispatchGen.GENERIC_STEPS.get();
    }

    public static int debugDispatchers() {
        return DISPATCHERS.size();
    }

    public static final class Holder {
        Class<?> type;
        IEventListener[] original;
        Dispatcher dispatcher;
        Class<?>[] restType;
        Dispatcher[] restDispatcher;
        IEventListener[][] restOriginal;
        int size;
    }

    static final class Entry {
        final IEventListener[] original;
        final Dispatcher dispatcher;

        Entry(final IEventListener[] original, final Dispatcher dispatcher) {
            this.original = original;
            this.dispatcher = dispatcher;
        }
    }

    static final class Registration {
        final ListenerList list;
        final Class<?> type;
        final IEventListener listener;

        Registration(final ListenerList list, final Class<?> type, final IEventListener listener) {
            this.list = list;
            this.type = type;
            this.listener = listener;
        }
    }

    static final class Key {
        final Class<?> type;
        final int busId;

        Key(final Class<?> type, final int busId) {
            this.type = type;
            this.busId = busId;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Key key && key.type == type && key.busId == busId;
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + busId;
        }
    }

    static final class ConsumerListener<T extends Event> implements IEventListener {
        final Consumer<T> consumer;
        final boolean receiveCancelled;
        final Object filter;

        ConsumerListener(final Consumer<T> consumer, final boolean receiveCancelled) {
            this(consumer, receiveCancelled, null);
        }

        ConsumerListener(final Consumer<T> consumer, final boolean receiveCancelled, final Object filter) {
            this.consumer = consumer;
            this.receiveCancelled = receiveCancelled;
            this.filter = filter;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void invoke(final Event event) {
            if (!receiveCancelled && event.isCancelable() && event.isCanceled()) {
                return;
            }
            if (filter != null && filter != ((IGenericEvent<?>) event).getGenericType()) {
                return;
            }
            consumer.accept((T) event);
        }

        @Override
        public String listenerName() {
            return consumer.getClass().getName();
        }
    }

    static final class DirectListener implements IEventListener {
        final IEventListener target;
        final Object filter;
        final String name;
        final boolean receiveCancelled;
        final ASMEventHandler original;

        DirectListener(final IEventListener target, final String name, final boolean receiveCancelled,
                       final Object filter, final ASMEventHandler original) {
            this.target = target;
            this.name = name;
            this.receiveCancelled = receiveCancelled;
            this.filter = filter;
            this.original = original;
        }

        static DirectListener of(final ASMEventHandler handler) {
            try {
                final Object target = Access.UNSAFE.getObject(handler, Access.ASM_HANDLER);
                final Object subInfo = Access.UNSAFE.getObject(handler, Access.ASM_SUBINFO);
                if (!(target instanceof IEventListener listener) || !(subInfo instanceof SubscribeEvent info)) {
                    return null;
                }
                final Object filter = Access.UNSAFE.getObject(handler, Access.ASM_FILTER);
                return new DirectListener(listener, handler.toString(), info.receiveCanceled(), filter, handler);
            } catch (final Throwable ignored) {
                return null;
            }
        }

        @Override
        public void invoke(final Event event) {
            if (filter != null) {
                original.invoke(event);
                return;
            }
            if (!receiveCancelled && event.isCancelable() && event.isCanceled()) {
                return;
            }
            target.invoke(event);
        }

        @Override
        public String listenerName() {
            return name;
        }
    }

    static final class Flatten {
        static IEventListener[] apply(final IEventListener[] src) {
            if (!Access.FLATTEN_OK) {
                return src;
            }
            IEventListener[] dst = null;
            for (int i = 0; i < src.length; i++) {
                if (!(src[i] instanceof ASMEventHandler handler)) {
                    continue;
                }
                final DirectListener direct = DirectListener.of(handler);
                if (direct == null) {
                    continue;
                }
                if (dst == null) {
                    dst = src.clone();
                }
                dst[i] = direct;
            }
            return dst == null ? src : dst;
        }
    }

    static final class Access {
        static final sun.misc.Unsafe UNSAFE;
        static final long BUSID;
        static final long SHUTDOWN;
        static final long ASM_HANDLER;
        static final long ASM_FILTER;
        static final long ASM_SUBINFO;
        static final boolean OK;
        static final boolean FLATTEN_OK;

        static {
            sun.misc.Unsafe unsafe;
            long busId = -1;
            long shutdown = -1;
            long handler = -1;
            long filter = -1;
            long subInfo = -1;
            try {
                final java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                unsafe = (sun.misc.Unsafe) field.get(null);
                busId = unsafe.objectFieldOffset(EventBus.class.getDeclaredField("busID"));
                shutdown = unsafe.objectFieldOffset(EventBus.class.getDeclaredField("shutdown"));
                handler = unsafe.objectFieldOffset(ASMEventHandler.class.getDeclaredField("handler"));
                filter = unsafe.objectFieldOffset(ASMEventHandler.class.getDeclaredField("filter"));
                subInfo = unsafe.objectFieldOffset(ASMEventHandler.class.getDeclaredField("subInfo"));
            } catch (final Throwable ignored) {
                unsafe = null;
            }
            UNSAFE = unsafe;
            BUSID = unsafe == null ? -1 : busId;
            SHUTDOWN = unsafe == null ? -1 : shutdown;
            ASM_HANDLER = unsafe == null ? -1 : handler;
            ASM_FILTER = unsafe == null ? -1 : filter;
            ASM_SUBINFO = unsafe == null ? -1 : subInfo;
            OK = unsafe != null && BUSID >= 0 && SHUTDOWN >= 0;
            FLATTEN_OK = unsafe != null && ASM_HANDLER >= 0 && ASM_FILTER >= 0 && ASM_SUBINFO >= 0;
        }
    }

    private static final class Probe extends Event {
        private static final Object LOCK = new Object();
        private static final Probe INSTANCE = new Probe();
        private static volatile int captured = -1;

        private static final ListenerList CAPTURE = new ListenerList() {
            @Override
            public IEventListener[] getListeners(final int id) {
                captured = id;
                return EMPTY;
            }
        };

        @Override
        public ListenerList getListenerList() {
            return CAPTURE;
        }

        @Override
        public boolean isCancelable() {
            return false;
        }

        static int probe(final IEventBus bus) {
            synchronized (LOCK) {
                captured = -1;
                try {
                    bus.post(INSTANCE);
                } catch (final Throwable ignored) {
                    return -1;
                }
                return captured;
            }
        }
    }
}
