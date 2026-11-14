package io.github.nutant.fastevents;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

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
        if (!PostCallRewriter.referencesBus(classNode)) return ComputeFlags.NO_REWRITE;
        final PostCallRewriter rewriter = new PostCallRewriter();
        final int changed = rewriter.rewrite(classNode);
        if (changed <= 0) return ComputeFlags.NO_REWRITE;
        return rewriter.guardSites > 0 ? ComputeFlags.COMPUTE_FRAMES : ComputeFlags.COMPUTE_MAXS;
    }
}
