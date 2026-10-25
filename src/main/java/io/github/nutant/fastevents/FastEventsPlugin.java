package io.github.nutant.fastevents;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.EnumSet;

public final class FastEventsPlugin implements ILaunchPluginService {

    private static final EnumSet<Phase> HANDLE = EnumSet.of(Phase.AFTER);
    private static final EnumSet<Phase> SKIP = EnumSet.noneOf(Phase.class);

    @Override
    public String name() {
        return "fastevents";
    }

    @Override
    public EnumSet<Phase> handlesClass(final Type classType, final boolean isEmpty) {
        if (isEmpty) return SKIP;
        if (classType.getInternalName().indexOf('/') < 0) return SKIP;
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
