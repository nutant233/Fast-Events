// Development tool: runs this mod's real rewriter over every class of a third-party mod jar and checks that
// the result still passes the JVM data-flow verifier. Used to confirm that a compatibility report is not caused
// by corrupting another mod's bytecode.
//   usage: java harness.TransformCheck <mod.jar>
package harness;

import io.github.nutant.fastevents.PostCallRewriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class TransformCheck {

    private TransformCheck() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("usage: TransformCheck <mod.jar>");
            System.exit(2);
        }
        int classes = 0;
        int touched = 0;
        final List<String> changed = new ArrayList<>();
        final List<String> broken = new ArrayList<>();
        try (JarFile jar = new JarFile(args[0])) {
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                classes++;
                final byte[] original;
                try (InputStream in = jar.getInputStream(entry)) {
                    original = in.readAllBytes();
                }
                final ClassNode node = new ClassNode();
                new ClassReader(original).accept(node, 0);
                if (!PostCallRewriter.referencesBus(node)) {
                    // still verify the input, so a failure below is attributable to the rewrite
                    final String problem = verify(node);
                    if (problem != null) {
                        broken.add(entry.getName() + " (untouched): " + problem);
                    }
                    continue;
                }
                final PostCallRewriter rewriter = new PostCallRewriter();
                final int sites = rewriter.rewrite(node);
                touched++;
                changed.add(entry.getName() + ": " + sites + " site(s), " + rewriter.holders + " holder(s)");
                final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                final ClassNode written = new ClassNode();
                new ClassReader(writer.toByteArray()).accept(written, 0);
                final String problem = verify(written);
                if (problem != null) {
                    broken.add(entry.getName() + ": " + problem);
                }
            }
        }
        System.out.println("classes: " + classes + ", rewritten: " + touched);
        for (final String line : changed) {
            System.out.println("  changed  " + line);
        }
        for (final String line : broken) {
            System.out.println("  BROKEN   " + line);
        }
        System.out.println();
        System.out.println(broken.isEmpty() ? "TRANSFORM CHECK PASSED" : "TRANSFORM CHECK FAILED: " + broken.size());
        System.exit(broken.isEmpty() ? 0 : 1);
    }

    private static String verify(final ClassNode node) {
        for (final MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            try {
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            } catch (final Throwable throwable) {
                final StringWriter writer = new StringWriter();
                throwable.printStackTrace(new PrintWriter(writer));
                return method.name + method.desc + ": " + throwable;
            }
        }
        return null;
    }
}
