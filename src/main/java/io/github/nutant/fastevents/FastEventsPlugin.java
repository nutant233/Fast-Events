package io.github.nutant.fastevents;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class FastEventsPlugin implements ILaunchPluginService {

    private static final Logger LOGGER = LogManager.getLogger("FastEvents");

    private static final EnumSet<Phase> HANDLE = EnumSet.of(Phase.AFTER);
    private static final EnumSet<Phase> SKIP = EnumSet.noneOf(Phase.class);

    /** Internal-name prefixes that are left alone, from {@code -Dfastevents.exclude=a.b,c.d}. */
    private static final String[] EXCLUDED = excluded();

    private static String[] excluded() {
        final String property = System.getProperty("fastevents.exclude", "").trim();
        if (property.isEmpty()) {
            return new String[0];
        }
        final List<String> prefixes = new ArrayList<>();
        for (final String raw : property.split(",")) {
            final String prefix = raw.trim().replace('.', '/');
            if (!prefix.isEmpty()) {
                prefixes.add(prefix);
            }
        }
        final String[] result = prefixes.toArray(new String[0]);
        LOGGER.info("[FastEvents] not touching classes under {}", String.join(", ", result));
        return result;
    }

    private static boolean isExcluded(final String internalName) {
        for (final String prefix : EXCLUDED) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Set once this mod's classes are reachable from the game class loader. */
    private static volatile boolean exposed;

    static {
        expose();
    }

    /**
     * On 1.16.5 modlauncher loads launch plugins from its own URLClassLoader, a sibling of the game class
     * loader, so a call site rewritten into a game class cannot resolve {@code FastDispatch}. The game class
     * loader's parent is the system class loader, so appending this jar to that one makes the classes visible
     * to both. Only possible on Java 8, the runtime Forge 36 targets, where the system loader is a
     * {@link URLClassLoader}.
     */
    static void expose() {
        if (exposed) {
            return;
        }
        try {
            final ClassLoader system = ClassLoader.getSystemClassLoader();
            if (visible(system)) {
                exposed = true;
                return;
            }
            if (!(system instanceof URLClassLoader)) {
                LOGGER.error("[FastEvents] the system class loader is not a URLClassLoader (Java {}), so this mod cannot "
                        + "make its classes visible to Minecraft; put the jar on the launch classpath instead",
                        System.getProperty("java.version"));
                return;
            }
            final URL jar = FastEventsPlugin.class.getProtectionDomain().getCodeSource().getLocation();
            final Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            addURL.invoke(system, jar);
            if (visible(system)) {
                exposed = true;
                LOGGER.info("[FastEvents] exposed {} to the game class loader (plugin loader: {}, system loader: {})",
                    jar, FastEventsPlugin.class.getClassLoader(), system.getClass().getName());
            } else {
                LOGGER.error("[FastEvents] could not expose {} to the game class loader, this install is inactive; "
                        + "put the jar on the launch classpath instead", jar);
            }
        } catch (final Throwable throwable) {
            LOGGER.error("[FastEvents] could not expose this mod to the game class loader, this install is inactive; "
                    + "put the jar on the launch classpath instead", throwable);
        }
    }

    private static boolean visible(final ClassLoader loader) {
        try {
            Class.forName("io.github.nutant.fastevents.FastDispatch", false, loader);
            return true;
        } catch (final Throwable notVisible) {
            return false;
        }
    }

    @Override
    public String name() {
        return "fastevents";
    }

    @Override
    public EnumSet<Phase> handlesClass(final Type classType, final boolean isEmpty) {
        if (isEmpty) return SKIP;
        final String internalName = classType.getInternalName();
        if (internalName.indexOf('/') < 0) return SKIP;
        if (EXCLUDED.length > 0 && isExcluded(internalName)) return SKIP;
        return HANDLE;
    }

    @Override
    public int processClassWithFlags(final Phase phase, final ClassNode classNode, final Type classType, final String reason) {
        if (!exposed) {
            // rewriting without a reachable dispatch helper would crash the game with NoClassDefFoundError
            return ComputeFlags.NO_REWRITE;
        }
        if (!PostCallRewriter.referencesBus(classNode)) return ComputeFlags.NO_REWRITE;
        final PostCallRewriter rewriter = new PostCallRewriter();
        final int changed = rewriter.rewrite(classNode);
        if (changed <= 0) return ComputeFlags.NO_REWRITE;
        return rewriter.guardSites > 0 ? ComputeFlags.COMPUTE_FRAMES : ComputeFlags.COMPUTE_MAXS;
    }
}
