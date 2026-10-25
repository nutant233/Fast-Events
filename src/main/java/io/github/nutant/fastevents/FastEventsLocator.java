package io.github.nutant.fastevents;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;

public final class FastEventsLocator implements IModFileCandidateLocator {

    private static final Logger LOGGER = LogManager.getLogger("FastEvents");

    public FastEventsLocator() {
        inject();
    }

    @Override
    public void findCandidates(final ILaunchContext context, final IDiscoveryPipeline pipeline) {
        inject();
    }

    static void inject() {
        try {
            final Object launcher = Launcher.INSTANCE;
            if (launcher == null) {
                fail("modlauncher is not running yet", null);
                return;
            }
            final Object handler = UnsafeAccess.read(launcher, Launcher.class, "launchPlugins");
            if (!(handler instanceof LaunchPluginHandler pluginHandler)) {
                fail("unexpected launch plugin handler", null);
                return;
            }
            final Object plugins = UnsafeAccess.read(pluginHandler, LaunchPluginHandler.class, "plugins");
            if (!(plugins instanceof Map map)) {
                fail("unexpected launch plugin map", null);
                return;
            }
            final ILaunchPluginService existing = (ILaunchPluginService) map.get("fastevents");
            if (existing != null) {
                return;
            }
            map.put("fastevents", new FastEventsPlugin());
            LOGGER.info("[FastEvents] launch plugin injected, every EVENT_BUS call site will be rewritten");
        } catch (final Throwable throwable) {
            fail("cannot inject the launch plugin", throwable);
        }
    }

    private static void fail(final String reason, final Throwable throwable) {
        LOGGER.error("[FastEvents] {}, this install is inactive; put the jar on the launch classpath instead",
                reason, throwable);
    }

    static final class UnsafeAccess {
        private static final sun.misc.Unsafe UNSAFE;
        private static final boolean OK;

        static {
            sun.misc.Unsafe unsafe = null;
            try {
                final Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                unsafe = (sun.misc.Unsafe) field.get(null);
            } catch (final Throwable ignored) {
            }
            UNSAFE = unsafe;
            OK = unsafe != null;
        }

        static Object read(final Object target, final Class<?> owner, final String name) throws Exception {
            if (!OK) {
                throw new IllegalStateException("Unsafe is unavailable");
            }
            final long offset = UNSAFE.objectFieldOffset(owner.getDeclaredField(name));
            return UNSAFE.getObject(target, offset);
        }
    }
}
