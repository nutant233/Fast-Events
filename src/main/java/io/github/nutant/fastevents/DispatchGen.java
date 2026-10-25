package io.github.nutant.fastevents;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicInteger;

final class DispatchGen implements Opcodes {

    private static final String DISPATCHER = "io/github/nutant/fastevents/Dispatcher";
    private static final String FAILURE = "io/github/nutant/fastevents/Dispatcher$Failure";
    private static final String LISTENER = "net/neoforged/bus/api/EventListener";
    private static final String LISTENER_DESC = "L" + LISTENER + ";";
    private static final String EVENT = "net/neoforged/bus/api/Event";
    private static final String EVENT_DESC = "L" + EVENT + ";";
    private static final String OBJECT_DESC = "Ljava/lang/Object;";

    private static final AtomicInteger ID = new AtomicInteger();

    private static final int STEPS_PER_METHOD = Integer.getInteger("fastevents.steps", 64);

    private static final boolean DUMP = Boolean.getBoolean("fastevents.dump");

    private DispatchGen() {
    }

    static Dispatcher create(final Class<?> eventType, final EventListener[] listeners) {
        if (listeners.length == 0) {
            return Dispatcher.EMPTY;
        }

        final String name = "io/github/nutant/fastevents/Gen" + ID.getAndIncrement();
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", new String[]{DISPATCHER});

        for (int i = 0; i < listeners.length; i++) {
            cw.visitField(ACC_PRIVATE | ACC_FINAL, "f" + i, LISTENER_DESC, null, null).visitEnd();
        }

        final MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "([Ljava/lang/Object;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int i = 0; i < listeners.length; i++) {
            ctor.visitVarInsn(ALOAD, 0);
            ctor.visitVarInsn(ALOAD, 1);
            pushInt(ctor, i);
            ctor.visitInsn(AALOAD);
            ctor.visitTypeInsn(CHECKCAST, LISTENER);
            ctor.visitFieldInsn(PUTFIELD, name, "f" + i, LISTENER_DESC);
        }
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        emitDispatch(cw, name, listeners.length);
        cw.visitEnd();

        try {
            final Class<?> cls = define(name.replace('/', '.'), cw.toByteArray());
            final Constructor<?> constructor = cls.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return (Dispatcher) constructor.newInstance(new Object[]{listeners});
        } catch (final Throwable throwable) {
            throw new IllegalStateException("[FastEvents] dispatcher generation failed", throwable);
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

    private static void emitDispatch(final ClassWriter cw, final String name, final int count) {
        if (count <= STEPS_PER_METHOD) {
            final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "dispatch", "(" + EVENT_DESC + ")V", null, null);
            mv.visitCode();
            emitSteps(mv, name, 0, count);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }

        final int chunks = (count + STEPS_PER_METHOD - 1) / STEPS_PER_METHOD;
        for (int chunk = 0; chunk < chunks; chunk++) {
            final int from = chunk * STEPS_PER_METHOD;
            final int to = Math.min(count, from + STEPS_PER_METHOD);
            final MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "run" + chunk, "(" + EVENT_DESC + ")V", null, null);
            mv.visitCode();
            emitSteps(mv, name, from, to);
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

    private static void emitSteps(final MethodVisitor mv, final String name, final int from, final int to) {
        for (int index = from; index < to; index++) {
            final org.objectweb.asm.Label start = new org.objectweb.asm.Label();
            final org.objectweb.asm.Label end = new org.objectweb.asm.Label();
            final org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
            final org.objectweb.asm.Label next = new org.objectweb.asm.Label();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");

            mv.visitLabel(start);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, name, "f" + index, LISTENER_DESC);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, LISTENER, "invoke", "(" + EVENT_DESC + ")V", false);
            mv.visitLabel(end);
            mv.visitJumpInsn(GOTO, next);

            mv.visitLabel(handler);
            mv.visitVarInsn(ASTORE, 2);
            mv.visitTypeInsn(NEW, FAILURE);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitLdcInsn(index);
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
