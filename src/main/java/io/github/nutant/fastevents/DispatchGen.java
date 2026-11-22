package io.github.nutant.fastevents;

import net.neoforged.bus.ConsumerEventHandler;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds one dispatcher class per (bus, event class). The listener calls are inlined into a single method, and
 * the wrappers the bus stored are unpacked so the generated code calls the registered consumer or handler
 * directly instead of going through {@code invoke}.
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

    private static final Field CONSUMER_FIELD = field(ConsumerEventHandler.class, "consumer");
    private static final Field PREDICATE_FIELD = field(ConsumerEventHandler.WithPredicate.class, "predicate");
    private static final Field SUB_INFO_FIELD = field(SubscribeEventListener.class, "subInfo");

    private DispatchGen() {
    }

    static Dispatcher create(final Class<?> eventType, final EventListener[] listeners) {
        if (listeners.length == 0) {
            return Dispatcher.EMPTY;
        }

        final List<Step> steps = new ArrayList<>(listeners.length);
        for (final EventListener listener : listeners) {
            steps.add(classify(listener));
        }

        final String name = "io/github/nutant/fastevents/Gen" + ID.getAndIncrement();
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", new String[]{DISPATCHER});

        // fields: f<i> = consumer or the unwrapped listener, g<i> = the bus's filter predicate
        for (int i = 0; i < steps.size(); i++) {
            final Step step = steps.get(i);
            if (step.filter != null) {
                cw.visitField(ACC_PRIVATE | ACC_FINAL, "g" + i, PREDICATE_DESC, null, null).visitEnd();
            }
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "f" + i, step.targetDesc, null, null).visitEnd();
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
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            pushInt(ctor, slot++);
            ctor.visitInsn(AALOAD);
            ctor.visitTypeInsn(CHECKCAST, step.targetInternal);
            ctor.visitFieldInsn(PUTFIELD, name, "f" + i, step.targetDesc);
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
            final Object[] arguments = new Object[slot];
            slot = 0;
            for (final Step step : steps) {
                if (step.filter != null) {
                    arguments[slot++] = step.filter;
                }
                arguments[slot++] = step.target;
            }
            return (Dispatcher) constructor.newInstance(new Object[]{arguments});
        } catch (final Throwable throwable) {
            throw new IllegalStateException("[FastEvents] dispatcher generation failed", throwable);
        }
    }

    /**
     * Unpacks what the bus stored for one listener. Consumers live in {@code ConsumerEventHandler} (plus a
     * filter predicate when the registration asked not to receive cancelled events), {@code @SubscribeEvent}
     * methods in {@code SubscribeEventListener} around the generated handler. Anything unknown keeps the plain
     * {@code invoke} call.
     */
    private static Step classify(final EventListener listener) {
        if (listener instanceof ConsumerEventHandler) {
            final Object consumer = read(CONSUMER_FIELD, listener);
            if (consumer != null) {
                final Object predicate = listener instanceof ConsumerEventHandler.WithPredicate
                        ? read(PREDICATE_FIELD, listener)
                        : null;
                return new Step(consumer, predicate, true, true);
            }
        }
        if (listener instanceof SubscribeEventListener) {
            final EventListener target = ((SubscribeEventListener) listener).getWithoutCheck();
            if (target != null) {
                return new Step(target, null, false, receiveCanceled((SubscribeEventListener) listener));
            }
        }
        return new Step(listener, null, false, true);
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

    /** One listener call the generated dispatcher performs. */
    private static final class Step {
        final Object target;
        final Object filter;
        final boolean consumer;
        final boolean receiveCanceled;
        final String targetInternal;
        final String targetDesc;

        Step(final Object target, final Object filter, final boolean consumer, final boolean receiveCanceled) {
            this.target = target;
            this.filter = filter;
            this.consumer = consumer;
            this.receiveCanceled = receiveCanceled;
            this.targetInternal = consumer ? CONSUMER : LISTENER;
            this.targetDesc = consumer ? CONSUMER_DESC : LISTENER_DESC;
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
            if (!step.consumer && !step.receiveCanceled) {
                // the bus would have cast to ICancellableEvent here too, and only cancellable events can be
                // registered with receiveCanceled = false
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, CANCELLABLE);
                mv.visitMethodInsn(INVOKEINTERFACE, CANCELLABLE, "isCanceled", "()Z", true);
                mv.visitJumpInsn(IFNE, next);
            }
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, name, "f" + index, step.targetDesc);
            mv.visitVarInsn(ALOAD, 1);
            if (step.consumer) {
                mv.visitMethodInsn(INVOKEINTERFACE, CONSUMER, "accept", "(" + OBJECT_DESC + ")V", true);
            } else {
                mv.visitMethodInsn(INVOKEVIRTUAL, LISTENER, "invoke", "(" + EVENT_DESC + ")V", false);
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
