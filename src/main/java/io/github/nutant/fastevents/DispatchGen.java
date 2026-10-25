package io.github.nutant.fastevents;

import net.minecraftforge.eventbus.api.*;
import org.objectweb.asm.*;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class DispatchGen implements Opcodes {

    private static final String DISPATCHER = "io/github/nutant/fastevents/Dispatcher";
    private static final String FAILURE = "io/github/nutant/fastevents/Dispatcher$Failure";
    private static final String CONSUMER = "java/util/function/Consumer";
    private static final String CONSUMER_DESC = "Ljava/util/function/Consumer;";
    private static final String LISTENER = "net/minecraftforge/eventbus/api/IEventListener";
    private static final String LISTENER_DESC = "Lnet/minecraftforge/eventbus/api/IEventListener;";
    private static final String EVENT = "net/minecraftforge/eventbus/api/Event";
    private static final String EVENT_DESC = "Lnet/minecraftforge/eventbus/api/Event;";
    private static final String PRIORITY = "net/minecraftforge/eventbus/api/EventPriority";
    private static final String PRIORITY_DESC = "L" + PRIORITY + ";";
    private static final String OBJECT_DESC = "Ljava/lang/Object;";
    private static final String GENERIC = "net/minecraftforge/eventbus/api/IGenericEvent";
    private static final String GENERIC_METHOD = "getGenericType";
    private static final String INVOKER = "net/minecraftforge/eventbus/api/IEventBusInvokeDispatcher";
    private static final String INVOKER_DESC = "L" + INVOKER + ";";
    private static final String INVOKER_ARGS = "(" + LISTENER_DESC + EVENT_DESC + ")V";

    private static final AtomicInteger ID = new AtomicInteger();
    private static final Loader LOADER = new Loader();

    static final AtomicInteger GENERIC_STEPS = new AtomicInteger();

    private DispatchGen() {
    }

    static Dispatcher create(final Class<?> eventType, final IEventListener[] listeners) {
        final List<Step> steps = new ArrayList<>(listeners.length);
        for (int i = 0; i < listeners.length; i++) {
            final Step step = classify(listeners[i], i);
            steps.add(step);
        }
        if (steps.isEmpty()) {
            return Dispatcher.EMPTY;
        }

        final String name = "io/github/nutant/fastevents/Gen" + ID.getAndIncrement();
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                try {
                    final ClassLoader loader = DispatchGen.class.getClassLoader();
                    Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                    final Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                    if (c1.isAssignableFrom(c2)) {
                        return type1;
                    }
                    if (c2.isAssignableFrom(c1)) {
                        return type2;
                    }
                    if (c1.isInterface() || c2.isInterface()) {
                        return "java/lang/Object";
                    }
                    do {
                        c1 = c1.getSuperclass();
                    } while (!c1.isAssignableFrom(c2));
                    return c1.getName().replace('.', '/');
                } catch (final Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        // 52.0: 1.16.5 runs on Java 8, so a generated class may not claim a newer bytecode level
        cw.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", new String[]{DISPATCHER});

        assignSlots(steps);
        final boolean cancelable = isCancelable(eventType);
        emitFieldsAndCtor(cw, name, steps);
        emitDispatch(cw, name, steps, cancelable);
        emitCancelled(cw, name, cancelable);
        emitWrapped(cw, name, steps, cancelable);
        cw.visitEnd();

        try {
            final Class<?> cls = define(name.replace('/', '.'), cw.toByteArray());
            final Constructor<?> ctor = cls.getDeclaredConstructors()[0];
            final Object[] args = new Object[countFields(steps)];
            int slot = 0;
            for (final Step step : steps) {
                for (final Object arg : step.args) {
                    args[slot++] = arg;
                }
            }
            return (Dispatcher) ctor.newInstance(new Object[]{args});
        } catch (final Throwable t) {
            throw new IllegalStateException("[FastEvents] dispatcher generation failed", t);
        }
    }

    private static Step classify(final IEventListener listener, final int index) {
        if (listener instanceof EventPriority) {
            return new Step(Kind.MARKER, NO_TYPES, NO_ARGS, true, (EventPriority) listener, index);
        }
        if (listener instanceof FastDispatch.ConsumerListener<?>) {
            final FastDispatch.ConsumerListener<?> consumer = (FastDispatch.ConsumerListener<?>) listener;
            if (consumer.filter != null && Generic.DESC != null) {
                GENERIC_STEPS.incrementAndGet();
                return new Step(Kind.CONSUMER_FILTERED, new String[]{CONSUMER_DESC, OBJECT_DESC}, new Object[]{consumer.consumer, consumer.filter}, consumer.receiveCancelled, null, index);
            }
            return new Step(Kind.CONSUMER, new String[]{CONSUMER_DESC}, new Object[]{consumer.consumer},
                    consumer.receiveCancelled, null, index);
        }
        if (listener instanceof FastDispatch.DirectListener) {
            final FastDispatch.DirectListener direct = (FastDispatch.DirectListener) listener;
            if (direct.filter == null) {
                return new Step(Kind.ASM, new String[]{LISTENER_DESC}, new Object[]{direct.target}, direct.receiveCancelled, null, index);
            }
            if (Generic.DESC != null) {
                GENERIC_STEPS.incrementAndGet();
                return new Step(Kind.GENERIC, new String[]{LISTENER_DESC, OBJECT_DESC},
                        new Object[]{direct.target, direct.filter}, direct.receiveCancelled, null, index);
            }
            return new Step(Kind.PLAIN, new String[]{LISTENER_DESC}, new Object[]{direct}, true, null, index);
        }
        return new Step(Kind.PLAIN, new String[]{LISTENER_DESC}, new Object[]{listener}, true, null, index);
    }

    private static int countFields(final List<Step> steps) {
        int n = 0;
        for (final Step step : steps) {
            n += step.args.length;
        }
        return n;
    }

    private static void assignSlots(final List<Step> steps) {
        int slot = 0;
        for (final Step step : steps) {
            step.fieldBase = slot;
            slot += step.args.length;
        }
    }

    private static void emitFieldsAndCtor(final ClassWriter cw, final String name, final List<Step> steps) {
        for (final Step step : steps) {
            for (int i = 0; i < step.args.length; i++) {
                cw.visitField(ACC_PRIVATE | ACC_FINAL, field(step, i), step.types[i], null, null).visitEnd();
            }
        }

        final StringBuilder desc = new StringBuilder("(");
        for (final Step step : steps) {
            for (final String type : step.types) {
                desc.append(type);
            }
        }
        desc.append(")V");

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int slot = 0;
        for (final Step step : steps) {
            for (int i = 0; i < step.args.length; i++) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                pushInt(mv, slot++);
                mv.visitInsn(AALOAD);
                if (!OBJECT_DESC.equals(step.types[i])) {
                    mv.visitTypeInsn(CHECKCAST, internalName(step.types[i]));
                }
                mv.visitFieldInsn(PUTFIELD, name, field(step, i), step.types[i]);
            }
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static String internalName(final String desc) {
        return desc.substring(1, desc.length() - 1);
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

    private static boolean isCancelable(final Class<?> eventType) {
        for (Class<?> c = eventType; c != null && c != Event.class; c = c.getSuperclass()) {
            if (c.isAnnotationPresent(Cancelable.class)) {
                return true;
            }
        }
        return false;
    }

    private static final int STEPS_PER_METHOD = Integer.getInteger("fastevents.steps", 64);

    private static final boolean DUMP = Boolean.getBoolean("fastevents.dump");

    private static void emitDispatch(final ClassWriter cw, final String name, final List<Step> steps,
                                     final boolean cancelable) {
        if (steps.size() <= STEPS_PER_METHOD) {
            final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "dispatch", "(" + EVENT_DESC + ")V", null, null);
            mv.visitCode();
            emitSteps(mv, name, steps, cancelable);
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
            emitSteps(mv, name, steps.subList(from, to), cancelable);
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
                                  final boolean cancelable) {
        for (final Step step : steps) {
            if (step.kind == Kind.MARKER) {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitFieldInsn(GETSTATIC, PRIORITY, step.priority.name(), PRIORITY_DESC);
                mv.visitMethodInsn(INVOKEVIRTUAL, EVENT, "setPhase", "(" + PRIORITY_DESC + ")V", false);
                continue;
            }

            final Label body = new Label();
            final Label skip = new Label();

            if (cancelable && !step.receiveCancelled) {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, EVENT, "isCanceled", "()Z", false);
                mv.visitJumpInsn(IFNE, skip);
            }
            if (step.kind == Kind.GENERIC || step.kind == Kind.CONSUMER_FILTERED) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, field(step, 1), OBJECT_DESC);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitTypeInsn(CHECKCAST, GENERIC);
                mv.visitMethodInsn(INVOKEINTERFACE, GENERIC, GENERIC_METHOD, Generic.DESC, true);
                mv.visitJumpInsn(IF_ACMPNE, skip);
            }
            mv.visitLabel(body);

            final Label start = new Label();
            final Label end = new Label();
            final Label handler = new Label();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");

            mv.visitLabel(start);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, name, field(step, 0), step.types[0]);
            mv.visitVarInsn(ALOAD, 1);
            if (step.kind == Kind.CONSUMER || step.kind == Kind.CONSUMER_FILTERED) {
                mv.visitMethodInsn(INVOKEINTERFACE, CONSUMER, "accept", "(Ljava/lang/Object;)V", true);
            } else {
                mv.visitMethodInsn(INVOKEINTERFACE, LISTENER, "invoke", "(" + EVENT_DESC + ")V", true);
            }
            mv.visitLabel(end);
            mv.visitJumpInsn(GOTO, skip);

            mv.visitLabel(handler);
            mv.visitVarInsn(ASTORE, 2);
            mv.visitTypeInsn(NEW, FAILURE);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn(step.index);
            mv.visitMethodInsn(INVOKESPECIAL, FAILURE, "<init>", "(Ljava/lang/Throwable;I)V", false);
            mv.visitInsn(ATHROW);

            mv.visitLabel(skip);
        }
    }

    private static void emitCancelled(final ClassWriter cw, final String name, final boolean cancelable) {
        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "dispatchAndCancelled",
                "(" + EVENT_DESC + ")Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, name, "dispatch", "(" + EVENT_DESC + ")V", false);
        if (cancelable) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, EVENT, "isCanceled", "()Z", false);
            mv.visitInsn(IRETURN);
        } else {
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitWrapped(final ClassWriter cw, final String name, final List<Step> steps,
                                    final boolean cancelable) {
        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "dispatchAndCancelled",
                "(" + EVENT_DESC + INVOKER_DESC + ")Z", null, null);
        mv.visitCode();

        for (final Step step : steps) {
            final Label start = new Label();
            final Label end = new Label();
            final Label handler = new Label();
            final Label after = new Label();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");

            mv.visitLabel(start);
            mv.visitVarInsn(ALOAD, 2);
            if (step.kind == Kind.MARKER) {
                mv.visitFieldInsn(GETSTATIC, PRIORITY, step.priority.name(), PRIORITY_DESC);
            } else {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, name, field(step, 0), step.types[0]);
            }
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, INVOKER, "invoke", INVOKER_ARGS, true);
            mv.visitLabel(end);
            mv.visitJumpInsn(GOTO, after);

            mv.visitLabel(handler);
            mv.visitVarInsn(ASTORE, 3);
            mv.visitTypeInsn(NEW, FAILURE);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitLdcInsn(step.index);
            mv.visitMethodInsn(INVOKESPECIAL, FAILURE, "<init>", "(Ljava/lang/Throwable;I)V", false);
            mv.visitInsn(ATHROW);

            mv.visitLabel(after);
        }

        if (cancelable) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, EVENT, "isCanceled", "()Z", false);
            mv.visitInsn(IRETURN);
        } else {
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }


    private static String field(final Step step, final int index) {
        return "f" + (step.fieldBase + index);
    }

    private static final class Generic {
        static final String DESC;

        static {
            String desc;
            try {
                desc = Type.getMethodDescriptor(IGenericEvent.class.getMethod(GENERIC_METHOD));
            } catch (final Throwable ignored) {
                desc = null;
            }
            DESC = desc;
        }
    }

    private static final String[] NO_TYPES = new String[0];
    private static final Object[] NO_ARGS = new Object[0];

    private enum Kind {MARKER, CONSUMER, CONSUMER_FILTERED, ASM, GENERIC, PLAIN}

    private static final class Step {
        final Kind kind;
        final String[] types;
        final Object[] args;
        final boolean receiveCancelled;
        final EventPriority priority;
        final int index;
        int fieldBase = -1;

        Step(final Kind kind, final String[] types, final Object[] args, final boolean receiveCancelled,
             final EventPriority priority, final int index) {
            this.kind = kind;
            this.types = types;
            this.args = args;
            this.receiveCancelled = receiveCancelled;
            this.priority = priority;
            this.index = index;
        }
    }

    private static Class<?> define(final String name, final byte[] bytes) {
        if (DUMP) {
            try (java.io.OutputStream out = new java.io.FileOutputStream(name.replace('/', '.') + ".class")) {
                out.write(bytes);
            } catch (final Throwable ignored) {
            }
        }
        // Java 8 has no Lookup#defineHiddenClass; a child loader per generated class is equivalent in lifetime
        return LOADER.define(name, bytes);
    }

    private static final class Loader extends ClassLoader {
        Loader() {
            super(DispatchGen.class.getClassLoader());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
