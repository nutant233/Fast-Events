import io.github.nutant.fastevents.PostCallRewriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;

/** 打印改写后的 TestTarget 字节码，用来核对 guard 的形状。 */
public final class Debug {
    public static void main(String[] args) throws Exception {
        final byte[] bytes;
        try (InputStream in = Debug.class.getClassLoader().getResourceAsStream("test/TestTarget.class")) {
            bytes = in.readAllBytes();
        }
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        try {
            final org.objectweb.asm.ClassWriter plain =
                    new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(final String a, final String b) {
                            try {
                                final ClassLoader l = Debug.class.getClassLoader();
                                final Class<?> c1 = Class.forName(a.replace('/', '.'), false, l);
                                final Class<?> c2 = Class.forName(b.replace('/', '.'), false, l);
                                if (c1.isAssignableFrom(c2)) return a;
                                if (c2.isAssignableFrom(c1)) return b;
                                if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                                return "java/lang/Object";
                            } catch (final Throwable t) {
                                return "java/lang/Object";
                            }
                        }
                    };
            node.accept(plain);
            plain.toByteArray();
            System.out.println("baseline COMPUTE_FRAMES ok");
        } catch (final Throwable t) {
            System.out.println("baseline COMPUTE_FRAMES FAILED: " + t);
        }
        final PostCallRewriter rewriter = new PostCallRewriter();
        rewriter.rewrite(node);
        System.out.println("postSites=" + rewriter.postSites + " guardSites=" + rewriter.guardSites
                + " hookSites=" + rewriter.hookSites);
        for (final MethodNode method : node.methods) {
            final ClassNode single = new ClassNode();
            single.name = node.name;
            single.superName = node.superName;
            single.version = node.version;
            single.access = node.access;
            single.fields.addAll(node.fields);
            single.methods.add(method);
            try {
                final org.objectweb.asm.ClassWriter cw =
                        new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
                single.accept(cw);
                cw.toByteArray();
            } catch (final Throwable t) {
                System.out.println("!!! FRAME FAILURE in " + method.name + method.desc + " : " + t);
            }
            if (!method.name.startsWith("post") && !method.name.startsWith("addBar") && !method.name.startsWith("remove")) {
                continue;
            }
            System.out.println("--- " + method.name + method.desc + " maxStack=" + method.maxStack);
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                System.out.println("    " + describe(insn));
            }
        }
    }

    private static String describe(AbstractInsnNode insn) {
        if (insn instanceof LabelNode) return "LABEL";
        switch (insn.getOpcode()) {
            case Opcodes.GOTO: return "GOTO -> " + label(insn);
            case Opcodes.IFEQ: return "IFEQ -> " + label(insn);
            default: break;
        }
        if (insn instanceof MethodInsnNode m) return "CALL " + m.owner + "." + m.name + m.desc;
        if (insn instanceof FieldInsnNode f) return "FIELD " + f.owner + "." + f.name;
        if (insn instanceof LdcInsnNode l) return "LDC " + l.cst;
        if (insn instanceof TypeInsnNode t) return "TYPE " + t.getOpcode() + " " + t.desc;
        if (insn instanceof VarInsnNode v) return "VAR " + v.getOpcode() + " " + v.var;
        return "OP " + insn.getOpcode();
    }

    private static String label(AbstractInsnNode insn) {
        return insn instanceof JumpInsnNode j ? ("@" + System.identityHashCode(j.label)) : "?";
    }
}
