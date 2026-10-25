package io.github.nutant.fastevents;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public final class PostCallRewriter {

    public static final String BUS_OWNER = "net/neoforged/neoforge/common/NeoForge";
    public static final String BUS_FIELD = "EVENT_BUS";
    public static final String BUS_DESC = "Lnet/neoforged/bus/api/IEventBus;";
    public static final String EVENTBUS = "net/neoforged/bus/api/IEventBus";
    public static final String EVENT_DESC = "Lnet/neoforged/bus/api/Event;";
    public static final String POST_DESC = "(" + EVENT_DESC + ")" + EVENT_DESC;

    private static final String CONSUMER_DESC = "Ljava/util/function/Consumer;";
    private static final String CLASS_DESC = "Ljava/lang/Class;";
    private static final String PRIORITY_DESC = "Lnet/neoforged/bus/api/EventPriority;";
    private static final String TARGET_DESC = "(Ljava/lang/Object;)V";

    public static final String[] ADD_LISTENER_DESCS = {
            "(" + CONSUMER_DESC + ")V",
            "(" + CLASS_DESC + CONSUMER_DESC + ")V",
            "(" + PRIORITY_DESC + CONSUMER_DESC + ")V",
            "(" + PRIORITY_DESC + CLASS_DESC + CONSUMER_DESC + ")V",
            "(" + PRIORITY_DESC + "Z" + CONSUMER_DESC + ")V",
            "(" + PRIORITY_DESC + "Z" + CLASS_DESC + CONSUMER_DESC + ")V",
            "(Z" + CONSUMER_DESC + ")V",
            "(Z" + CLASS_DESC + CONSUMER_DESC + ")V",
    };

    public static final String HELPER = "io/github/nutant/fastevents/FastDispatch";
    public static final String HOLDER_DESC = "L" + HELPER + "$Holder;";

    public static final String FIELD_PREFIX = "fastevents$h";

    public static final boolean guardEnabled = !"false".equals(System.getProperty("fastevents.guard"));

    public int postSites;
    public int holders;
    public int hookSites;
    public int guardSites;

    public static boolean referencesBus(final ClassNode classNode) {
        for (final MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.GETSTATIC
                        && BUS_OWNER.equals(field.owner) && BUS_FIELD.equals(field.name)) {
                    return true;
                }
                if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKEINTERFACE
                        && EVENTBUS.equals(call.owner) && isRewriteTarget(call)) {
                    return true;
                }
            }
        }
        return false;
    }

    public int rewrite(final ClassNode classNode) {
        this.postSites = 0;
        this.holders = 0;
        this.hookSites = 0;
        this.guardSites = 0;
        for (final MethodNode method : classNode.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            rewriteMethod(classNode, method);
        }
        initHolders(classNode);
        return this.postSites + this.hookSites;
    }

    private void rewriteMethod(final ClassNode classNode, final MethodNode method) {
        if (!mentionsTarget(method)) {
            return;
        }

        final Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(classNode.name, method);
        } catch (final AnalyzerException | RuntimeException ignored) {
            return;
        }

        boolean addedHolder = false;
        int site = 0;
        final AbstractInsnNode[] snapshot = method.instructions.toArray();
        for (int i = 0; i < snapshot.length; i++) {
            final AbstractInsnNode insn = snapshot[i];
            if (!(insn instanceof MethodInsnNode call) || insn.getOpcode() != Opcodes.INVOKEINTERFACE) {
                continue;
            }
            if (!EVENTBUS.equals(call.owner) || !isRewriteTarget(call)) {
                continue;
            }
            final Frame<SourceValue> frame = frames[i];
            if (frame == null) {
                continue;
            }

            if (!"post".equals(call.name)) {
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, call.name,
                        "(" + BUS_DESC + call.desc.substring(1), false));
                this.hookSites++;
                continue;
            }

            if (!isForgeBus(receiver(frame, call.desc))) {
                continue;
            }

            final AbstractInsnNode bus = receiverInsn(call);
            final TypeInsnNode newInsn = fixedNew(bus, call, frame.getStack(frame.getStackSize() - 1));
            final AbstractInsnNode next = call.getNext();
            final AbstractInsnNode pop = next != null && next.getOpcode() == Opcodes.POP ? next : null;

            final String fieldName = holderName(method, site);
            site++;
            if (addHolderField(classNode, fieldName)) {
                this.holders++;
            }

            final boolean guardable = newInsn != null && pop != null && guardEnabled && isGuardable(bus, newInsn, call);

            method.instructions.insertBefore(call, new FieldInsnNode(Opcodes.GETSTATIC, classNode.name, fieldName, HOLDER_DESC));
            final MethodInsnNode replacement = new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "post",
                    "(" + BUS_DESC + EVENT_DESC + HOLDER_DESC + ")" + EVENT_DESC, false);
            method.instructions.set(call, replacement);
            this.postSites++;
            addedHolder = true;

            if (guardable) {
                insertGuard(method, classNode.name, fieldName, bus, replacement, pop, newInsn.desc);
                this.guardSites++;
            }
        }

        if (addedHolder) {
            method.maxStack += 2;
        }
    }

    private static void insertGuard(final MethodNode method, final String owner, final String fieldName,
                                    final AbstractInsnNode bus, final AbstractInsnNode call,
                                    final AbstractInsnNode pop, final String eventInternalName) {
        final LabelNode skip = new LabelNode();
        final InsnList guard = new InsnList();
        guard.add(new FieldInsnNode(Opcodes.GETSTATIC, BUS_OWNER, BUS_FIELD, BUS_DESC));
        guard.add(new LdcInsnNode(Type.getObjectType(eventInternalName)));
        guard.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, fieldName, HOLDER_DESC));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "hasListeners",
                "(" + BUS_DESC + "Ljava/lang/Class;" + HOLDER_DESC + ")Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        guard.add(new FieldInsnNode(Opcodes.GETSTATIC, BUS_OWNER, BUS_FIELD, BUS_DESC));
        method.instructions.insertBefore(bus, guard);
        method.instructions.remove(bus);
        method.instructions.insert(pop, skip);
    }

    private static TypeInsnNode fixedNew(final AbstractInsnNode bus, final AbstractInsnNode call,
                                         final SourceValue value) {
        if (value == null || value.insns.size() != 1) {
            return null;
        }
        final AbstractInsnNode source = value.insns.iterator().next();
        if (source.getOpcode() == Opcodes.NEW && source instanceof TypeInsnNode type) {
            return type;
        }
        if (source.getOpcode() == Opcodes.DUP) {
            final AbstractInsnNode previous = source.getPrevious();
            return previous != null && previous.getOpcode() == Opcodes.NEW && previous instanceof TypeInsnNode type
                    ? type : null;
        }
        if (source instanceof MethodInsnNode init && init.getOpcode() == Opcodes.INVOKESPECIAL
                && "<init>".equals(init.name)) {
            if (bus == null) {
                return null;
            }
            for (AbstractInsnNode insn = bus.getNext(); insn != null && insn != call; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode type
                        && type.desc.equals(init.owner)) {
                    return type;
                }
            }
        }
        return null;
    }

    private static SourceValue receiver(final Frame<SourceValue> frame, final String desc) {
        final int arguments = Type.getArgumentTypes(desc).length;
        final int index = frame.getStackSize() - 1 - arguments;
        return index < 0 ? null : frame.getStack(index);
    }

    private static AbstractInsnNode receiverInsn(final AbstractInsnNode call) {
        for (AbstractInsnNode insn = call.getPrevious(); insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
                    && BUS_OWNER.equals(field.owner) && BUS_FIELD.equals(field.name)) {
                return insn;
            }
        }
        return null;
    }

    private static boolean isGuardable(final AbstractInsnNode bus, final TypeInsnNode newInsn,
                                       final AbstractInsnNode call) {
        if (bus == null || newInsn == null) {
            return false;
        }
        boolean sawNew = false;
        boolean sawInit = false;
        for (AbstractInsnNode insn = bus.getNext(); insn != null && insn != call; insn = insn.getNext()) {
            if (insn instanceof LabelNode) {
                return false;
            }
            final int opcode = insn.getOpcode();
            if (opcode < 0) {
                continue;
            }
            if (insn == newInsn) {
                sawNew = true;
                continue;
            }
            if (insn instanceof MethodInsnNode method) {
                if (method.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(method.name) && !sawInit) {
                    sawInit = true;
                    continue;
                }
                return false;
            }
            if (!isPure(opcode)) {
                return false;
            }
        }
        return sawNew && sawInit;
    }

    private static boolean isPure(final int opcode) {
        return switch (opcode) {
            case Opcodes.NEW, Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP2, Opcodes.SWAP,
                 Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD,
                 Opcodes.ACONST_NULL,
                 Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                 Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
                 Opcodes.LCONST_0, Opcodes.LCONST_1,
                 Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
                 Opcodes.DCONST_0, Opcodes.DCONST_1,
                 Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC,
                 Opcodes.GETSTATIC, Opcodes.GETFIELD,
                 Opcodes.ANEWARRAY, Opcodes.NEWARRAY, Opcodes.ARRAYLENGTH,
                 Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                 Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                 Opcodes.CHECKCAST, Opcodes.INSTANCEOF,
                 Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD,
                 Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB,
                 Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL,
                 Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV,
                 Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM,
                 Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG,
                 Opcodes.ISHL, Opcodes.LSHL, Opcodes.ISHR, Opcodes.LSHR,
                 Opcodes.IUSHR, Opcodes.LUSHR, Opcodes.IAND, Opcodes.LAND,
                 Opcodes.IOR, Opcodes.LOR, Opcodes.IXOR, Opcodes.LXOR,
                 Opcodes.I2L, Opcodes.I2F, Opcodes.I2D, Opcodes.L2I, Opcodes.L2F, Opcodes.L2D,
                 Opcodes.F2I, Opcodes.F2L, Opcodes.F2D, Opcodes.D2I, Opcodes.D2L, Opcodes.D2F,
                 Opcodes.I2B, Opcodes.I2C, Opcodes.I2S,
                 Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> true;
            default -> false;
        };
    }

    private static boolean mentionsTarget(final MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKEINTERFACE
                    && EVENTBUS.equals(call.owner) && isRewriteTarget(call)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRewriteTarget(final MethodInsnNode call) {
        return switch (call.name) {
            case "post" -> POST_DESC.equals(call.desc);
            case "addListener" -> matches(ADD_LISTENER_DESCS, call.desc);
            case "register", "unregister" -> TARGET_DESC.equals(call.desc);
            default -> false;
        };
    }

    private static boolean matches(final String[] descs, final String desc) {
        for (final String candidate : descs) {
            if (candidate.equals(desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForgeBus(final SourceValue value) {
        if (value == null || value.insns.size() != 1) {
            return false;
        }
        final AbstractInsnNode source = value.insns.iterator().next();
        if (source.getOpcode() != Opcodes.GETSTATIC || !(source instanceof FieldInsnNode field)) {
            return false;
        }
        return BUS_OWNER.equals(field.owner) && BUS_FIELD.equals(field.name) && BUS_DESC.equals(field.desc);
    }

    private static String holderName(final MethodNode method, final int site) {
        return FIELD_PREFIX + "$" + sanitize(method.name) + "$" + site;
    }

    private static String sanitize(final String methodName) {
        final StringBuilder builder = new StringBuilder(methodName.length() + 8);
        for (int i = 0; i < methodName.length(); i++) {
            final char c = methodName.charAt(i);
            final boolean plain = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '$';
            if (plain) {
                builder.append(c);
            } else {
                builder.append('$').append(Character.forDigit(c >> 4 & 0xF, 16))
                        .append(Character.forDigit(c & 0xF, 16));
            }
        }
        return builder.toString();
    }

    private static boolean addHolderField(final ClassNode classNode, final String fieldName) {
        for (final FieldNode field : classNode.fields) {
            if (fieldName.equals(field.name)) {
                return false;
            }
        }
        final boolean iface = (classNode.access & Opcodes.ACC_INTERFACE) != 0;
        classNode.fields.add(new FieldNode((iface ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE) | Opcodes.ACC_STATIC
                | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, fieldName, HOLDER_DESC, null, null));
        return true;
    }

    private void initHolders(final ClassNode classNode) {
        final InsnList snippet = new InsnList();
        for (final FieldNode field : classNode.fields) {
            if (!field.name.startsWith(FIELD_PREFIX) || !HOLDER_DESC.equals(field.desc)) {
                continue;
            }
            snippet.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "holder", "()" + HOLDER_DESC, false));
            snippet.add(new FieldInsnNode(Opcodes.PUTSTATIC, classNode.name, field.name, HOLDER_DESC));
        }
        if (snippet.size() == 0) {
            return;
        }

        MethodNode clinit = null;
        for (final MethodNode method : classNode.methods) {
            if ("<clinit>".equals(method.name)) {
                clinit = method;
                break;
            }
        }
        if (clinit == null) {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(snippet);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            clinit.maxStack = 1;
            classNode.methods.add(clinit);
        } else {
            clinit.instructions.insert(snippet);
            if (clinit.maxStack < 1) {
                clinit.maxStack = 1;
            }
        }
    }
}
