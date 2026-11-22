package io.github.nutant.fastevents;

import net.neoforged.bus.ConsumerEventHandler;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds one dispatcher class per (bus, event class). The listener calls are inlined into a single method, and
 * the wrappers the bus stored are unpacked: a consumer becomes a {@code Consumer.accept} call, and a
 * {@code @SubscribeEvent} handler is replaced by a direct call to the listener method whenever the method is
 * reachable from the generated class. Otherwise the bus's handler {@code invoke} is called as a fallback.
 */
final class DispatchGen implements Opcodes {

    private static final String DISPATCHER = "io/github/nutant/fastevents/Dispatcher";
    private static final String FAILURE = "io/github/nutant/fastevents/Dispatcher$Failure";
    private static final String LISTENER = "net/neoforged/bus/api/EventListener";
    private static final String LISTENER_DESC = "L" + LISTENER + ";";
    private static final String EVENT = "net/neoforged/bus/api/Event";
    private static final String EVENT_DESC = "L" + EVENT + ";";
    private static final String CANCELLABLE = "net/neoforged/bus/api/ICancellableEvent";
    private static final String CONSUMER = "java/util/function/Consumer";
    private static final String CONSUMER_DESC = "L" + CONSUMER + ";";
    private static final String PREDICATE = "java/util/function/Predicate";
    private static final String PREDICATE_DESC = "L" + PREDICATE + ";";
    private static final String OBJECT_DESC = "Ljava/lang/Object;";

    private static final AtomicInteger ID = new AtomicInteger();

    private static final int STEPS_PER_METHOD = Integer.getInteger("fastevents.steps", 64);

    private static final boolean DUMP = Boolean.getBoolean("fastevents.dump");

    private static final boolean DIRECT = !"false".equals(System.getProperty("fastevents.direct"));

    private static final Field CONSUMER_FIELD = field(ConsumerEventHandler.class, "consumer");
    private static final Field PREDICATE_FIELD = field(ConsumerEventHandler.WithPredicate.class, "predicate");
    private static final Field SUB_INFO_FIELD = field(SubscribeEventListener.class, "subInfo");

    private DispatchGen() {
    }

    static Dispatcher create(final Class<?> eventType, final EventListener[] listeners) {
        if (listeners.length == 0) {
            return Dispatcher.EMPTY;
        }
        try {
            return build(eventType, listeners, DIRECT);
        } catch (final Throwable direct) {
            // a direct call references the listener class from this module, which a modded class loader may not
            // be able to resolve; fall back to the bus's handler instead of failing the post
            try {
                return build(eventType, listeners, false);
            } catch (final Throwable plain) {
                plain.addSuppressed(direct);
                throw new IllegalStateException("[FastEvents] dispatcher generation failed", plain);
            }
        }
    }

    private static Dispatcher build(final Class<?> eventType, final EventListener[] listeners, final boolean direct) {
        final List<Step> steps = new ArrayList<>(listeners.length);
        for (final EventListener listener : listeners) {
            steps.add(classify(listener, eventType, direct));
        }

        final String name = "io/github/nutant/fastevents/Gen" + ID.getAndIncrement();
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", new String[]{DISPATCHER});

        int slots = 0;
        for (int i = 0; i < steps.size(); i++) {
            final Step step = steps.get(i);
            if (step.filter != null) {
                cw.visitField(ACC_PRIVATE | ACC_FINAL, "g" + i, PREDICATE_DESC, null, null).visitEnd();
                slots++;
            }
            if (step.needsField()) {
                cw.visitField(ACC_PRIVATE | ACC_FINAL, "f" + i, step.targetDesc(), null, null).visitEnd();
                slots++;
            }
        }

        final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int slot = 0;
        for (int i = 0; i < steps.size(); i++) {
            final Step step = steps.get(i);
            if (step.filter != null) {
                ctor.visitVarInsn(ALOAD, 0);
                ctor.visitVarInsn(ALOAD, 1);
                pushInt(ctor, slot++);
                ctor.visitInsn(AALOAD);
                ctor.visitTypeInsn(CHECKCAST, PREDICATE);
                ctor.visitFieldInsn(PUTFIELD, name, "g" + i, PREDICATE_DESC);
            }
            if (step.needsField()) {
                ctor.visitVarInsn(ALOAD, 0);
                ctor.visitVarInsn(ALOAD, 1);
                pushInt(ctor, slot++);
                ctor.visitInsn(AALOAD);
                ctor.visitTypeInsn(CHECKCAST, step.targetInternal());
                ctor.visitFieldInsn(PUTFIELD, name, "f" + i, step.targetDesc());
            }
        }
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        emitDispatch(cw, name, steps);
        cw.visitEnd();

        try {
            final Class<?> cls = define(name.replace('/', '.'), cw.toByteArray());
            final Constructor<?> constructor = cls.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            final Object[] arguments = new Object[slots];
            slot = 0;
            for (final Step step : steps) {
                if (step.filter != null) {
                    arguments[slot++] = step.filter;
                }
                if (step.needsField()) {
                    arguments[slot++] = step.value();
                }
            }
            return (Dispatcher) constructor.newInstance(new Object[]{arguments});
        } catch (final Throwable throwable) {
            throw new IllegalStateException("[FastEvents] dispatcher generation failed", throwable);
        }
    }

    /**
     * Unpacks what the bus stored for one listener. Consumers live in {@code ConsumerEventHandler} (plus a
     * filter predicate when the registration asked not to receive cancelled events), {@code @SubscribeEvent}
     * methods in {@code SubscribeEventListener} around a generated handler. Anything unknown keeps the plain
     * {@code invoke} call.
     */
    private static Step classify(final EventListener listener, final Class<?> eventType, final boolean direct) {
        if (listener instanceof ConsumerEventHandler) {
            final Object consumer = read(CONSUMER_FIELD, listener);
            if (consumer != null) {
                final Object predicate = listener instanceof ConsumerEventHandler.WithPredicate
                        ? read(PREDICATE_FIELD, listener)
                        : null;
                return Step.consumer(consumer, predicate);
            }
        }
        if (listener instanceof SubscribeEventListener) {
            final SubscribeEventListener wrapper = (SubscribeEventListener) listener;
            final EventListener target = wrapper.getWithoutCheck();
            if (target != null) {
                final boolean receiveCanceled = receiveCanceled(wrapper);
                final Direct call = direct ? Direct.resolve(target, eventType) : null;
                return call != null ? Step.direct(call, receiveCanceled) : Step.listener(target, receiveCanceled);
            }
        }
        if (direct && !(listener instanceof ConsumerEventHandler)) {
            // the bus leaves @SubscribeEvent handlers unwrapped for event classes that can never be cancelled,
            // so the cancellation check was never part of them
            final Direct call = Direct.resolve(listener, eventType);
            if (call != null) {
                return Step.direct(call, true);
            }
        }
        return Step.listener(listener, true);
    }

    private static boolean receiveCanceled(final SubscribeEventListener listener) {
        final Object annotation = read(SUB_INFO_FIELD, listener);
        if (annotation == null) {
            return true;
        }
        try {
            return (boolean) annotation.getClass().getMethod("receiveCanceled").invoke(annotation);
        } catch (final Throwable failed) {
            return true;
        }
    }

    /** A direct call to the listener method instead of the bus's MethodHandle based handler. */
    private static final class Direct {
        final Method method;
        final Object instance;
        final String owner;
        final String descriptor;
        /** Internal name to cast the event to, or null when the parameter is {@code Event} itself. */
        final String parameter;
        final boolean staticCall;
        final boolean pop;

        private Direct(final Method method, final Object instance) {
            this.method = method;
            this.instance = instance;
            this.owner = Type.getInternalName(method.getDeclaringClass());
            this.descriptor = Type.getMethodDescriptor(method);
            final Class<?> parameterType = method.getParameterTypes()[0];
            this.parameter = parameterType == Event.class ? null : Type.getInternalName(parameterType);
            this.staticCall = Modifier.isStatic(method.getModifiers());
            this.pop = method.getReturnType() != void.class;
        }

        /**
         * Finds the listener method behind a generated handler: the handler is named
         * {@code EventListenerFactory$<method>}, holds the target in an {@code instance} field, and the bus's
         * factory produces the very same handler class for the same method, which is used as the check.
         */
        static Direct resolve(final EventListener handler, final Class<?> eventType) {
            final Class<?> handlerClass = handler.getClass();
            final String binary = handlerClass.getName();
            final int dollar = binary.lastIndexOf('$');
            if (dollar < 0) {
                return null;
            }
            final int slash = binary.indexOf('/', dollar);
            final String name = binary.substring(dollar + 1, slash < 0 ? binary.length() : slash);
            final Field instanceField = field(handlerClass, "instance");
            final Object instance = instanceField == null ? null : read(instanceField, handler);
            if (instance == null) {
                return null;
            }
            for (Class<?> owner = instance.getClass(); owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                for (final Method candidate : owner.getDeclaredMethods()) {
                    if (!candidate.getName().equals(name) || candidate.getParameterCount() != 1) {
                        continue;
                    }
                    if (!candidate.getParameterTypes()[0].isAssignableFrom(eventType)) {
                        continue;
                    }
                    if (!Modifier.isPublic(candidate.getModifiers())
                            || !Modifier.isPublic(candidate.getDeclaringClass().getModifiers())) {
                        continue;
                    }
                    if (!linkable(candidate.getDeclaringClass()) || !linkable(candidate.getParameterTypes()[0])) {
                        // the generated class lives in this mod's class loader; naming a class from another
                        // mod's loader would fail to link, so keep the bus's handler for those
                        continue;
                    }
                    if (sameHandler(candidate, instance, handlerClass)) {
                        return new Direct(candidate, instance);
                    }
                }
            }
            return null;
        }

        private static boolean sameHandler(final Method method, final Object instance, final Class<?> expected) {
            try {
                final Class<?> factory = Class.forName("net.neoforged.bus.EventListenerFactory");
                final Method create = factory.getDeclaredMethod("create", Method.class, Object.class);
                create.setAccessible(true);
                return create.invoke(null, method, instance).getClass() == expected;
            } catch (final Throwable failed) {
                return false;
            }
        }

        /** Whether this mod's class loader can resolve {@code owner}, which is what the generated class uses. */
        private static boolean linkable(final Class<?> owner) {
            try {
                return Class.forName(owner.getName(), false, DispatchGen.class.getClassLoader()) == owner;
            } catch (final Throwable failed) {
                return false;
            }
        }
    }

    /** One listener call the generated dispatcher performs. */
    private static final class Step {
        static final int KIND_CONSUMER = 0;
        static final int KIND_INVOKE = 1;
        static final int KIND_DIRECT = 2;

        final int kind;
        final Object target;
        final Object filter;
        final boolean receiveCanceled;
        final Direct direct;

        private Step(final int kind, final Object target, final Object filter, final boolean receiveCanceled,
                     final Direct direct) {
            this.kind = kind;
            this.target = target;
            this.filter = filter;
            this.receiveCanceled = receiveCanceled;
            this.direct = direct;
        }

        static Step consumer(final Object consumer, final Object filter) {
            return new Step(KIND_CONSUMER, consumer, filter, true, null);
        }

        static Step listener(final Object listener, final boolean receiveCanceled) {
            return new Step(KIND_INVOKE, listener, null, receiveCanceled, null);
        }

        static Step direct(final Direct direct, final boolean receiveCanceled) {
            return new Step(KIND_DIRECT, direct.instance, null, receiveCanceled, direct);
        }

        boolean needsField() {
            return filter != null || kind == KIND_CONSUMER || kind == KIND_INVOKE || !direct.staticCall;
        }

        String targetInternal() {
            return kind == KIND_CONSUMER ? CONSUMER : kind == KIND_INVOKE ? LISTENER : direct.owner;
        }

        String targetDesc() {
            return kind == KIND_CONSUMER ? CONSUMER_DESC : kind == KIND_INVOKE ? LISTENER_DESC : "L" + direct.owner + ";";
        }

        Object value() {
            return kind == KIND_CONSUMER ? target : kind == KIND_INVOKE ? target : direct.instance;
        }
    }

    private static void pushInt(final MethodVisitor mv, final int value) {
        if (value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else if (value <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static void emitDispatch(final ClassWriter cw, final String name, final List<Step> steps) {
        if (steps.size() <= STEPS_PER_METHOD) {
            final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "dispatch", "(" + EVENT_DESC + ")V", null, null);
            mv.visitCode();
            emitSteps(mv, name, steps, 0, steps.size());
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }

        final int chunks = (steps.size() + STEPS_PER_METHOD - 1) / STEPS_PER_METHOD;
        for (int chunk = 0; chunk < chunks; chunk++) {
            final int from = chunk * STEPS_PER_METHOD;
            final int to = Math.min(steps.size(), from + STEPS_PER_METHOD);
            final MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "run" + chunk, "(" + EVENT_DESC + ")V", null, null);
            mv.visitCode();
            emitSteps(mv, name, steps, from, to);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "dispatch", "(" + EVENT_DESC + ")V", null, null);
        mv.visitCode();
        for (int chunk = 0; chunk < chunks; chunk++) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, name, "run" + chunk, "(" + EVENT_DESC + ")V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSteps(final MethodVisitor mv, final String name, final List<Step> steps,
                                  final int from, final int to) {
        for (int index = from; index < to; index++) {
            final Step step = steps.get(index);
            final Label start = new Label();
            final Label end = new Label();
            final Label handler = new Label();
            final Label next = new Label();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");

            mv.visitLabel(start);
            if (step.filter != null) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "g" + index, PREDICATE_DESC);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEINTERFACE, PREDICATE, "test", "(" + OBJECT_DESC + ")Z", true);
                mv.visitJumpInsn(IFEQ, next);
            }
            if (step.kind != Step.KIND_CONSUMER && !step.receiveCanceled) {
                // the bus would have cast to ICancellableEvent here too, and only cancellable events can be
                // registered with receiveCanceled = false
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, CANCELLABLE);
                mv.visitMethodInsn(INVOKEINTERFACE, CANCELLABLE, "isCanceled", "()Z", true);
                mv.visitJumpInsn(IFNE, next);
            }
            if (step.kind == Step.KIND_DIRECT) {
                final Direct direct = step.direct;
                if (!direct.staticCall) {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, name, "f" + index, step.targetDesc());
                }
                mv.visitVarInsn(ALOAD, 1);
                if (direct.parameter != null) {
                    mv.visitTypeInsn(CHECKCAST, direct.parameter);
                }
                mv.visitMethodInsn(direct.staticCall ? INVOKESTATIC : INVOKEVIRTUAL,
                        direct.owner, direct.method.getName(), direct.descriptor, false);
                if (direct.pop) {
                    mv.visitInsn(direct.method.getReturnType() == long.class
                            || direct.method.getReturnType() == double.class ? POP2 : POP);
                }
            } else {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, "f" + index, step.targetDesc());
                mv.visitVarInsn(ALOAD, 1);
                if (step.kind == Step.KIND_CONSUMER) {
                    mv.visitMethodInsn(INVOKEINTERFACE, CONSUMER, "accept", "(" + OBJECT_DESC + ")V", true);
                } else {
                    mv.visitMethodInsn(INVOKEVIRTUAL, LISTENER, "invoke", "(" + EVENT_DESC + ")V", false);
                }
            }
            mv.visitLabel(end);
            mv.visitJumpInsn(GOTO, next);

            mv.visitLabel(handler);
            mv.visitVarInsn(ASTORE, 2);
            mv.visitTypeInsn(NEW, FAILURE);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 2);
            pushInt(mv, index);
            mv.visitMethodInsn(INVOKESPECIAL, FAILURE, "<init>", "(Ljava/lang/Throwable;I)V", false);
            mv.visitInsn(ATHROW);

            mv.visitLabel(next);
        }
    }

    private static Field field(final Class<?> owner, final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (final Throwable failed) {
            return null;
        }
    }

    private static Object read(final Field field, final Object owner) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(owner);
        } catch (final Throwable failed) {
            return null;
        }
    }

    private static Class<?> define(final String name, final byte[] bytes) {
        if (DUMP) {
            try (java.io.OutputStream out = new java.io.FileOutputStream(name + ".class")) {
                out.write(bytes);
            } catch (final Throwable ignored) {
            }
        }
        try {
            return MethodHandles.lookup().defineHiddenClass(bytes, true).lookupClass();
        } catch (final Throwable hidden) {
            return LOADER.define(name, bytes);
        }
    }

    private static final Loader LOADER = new Loader();

    private static final class Loader extends ClassLoader {
        Loader() {
            super(DispatchGen.class.getClassLoader());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
