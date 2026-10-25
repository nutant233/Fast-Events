package io.github.nutant.fastevents;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.function.Consumer;

public final class FastEventsLocator implements IModLocator {

    private static final Logger LOGGER = LogManager.getLogger("FastEvents");

    private static volatile boolean injected;

    static {
        inject();
    }

    public static void inject() {
        if (injected) {
            return;
        }
        try {
            final Field launchPlugins = Launcher.class.getDeclaredField("launchPlugins");
            launchPlugins.setAccessible(true);
            final Object handler = launchPlugins.get(Launcher.INSTANCE);
            if (handler == null) {
                return;
            }
            final Field pluginsField = LaunchPluginHandler.class.getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            @SuppressWarnings("unchecked") final Map<String, ILaunchPluginService> plugins = (Map<String, ILaunchPluginService>) pluginsField.get(handler);
            plugins.put("fastevents", new FastEventsPlugin());
            injected = true;
            LOGGER.info("[FastEvents] launch plugin injected, every EVENT_BUS call site will be rewritten");
        } catch (final Throwable throwable) {
            LOGGER.error("[FastEvents] failed to inject the modlauncher launch plugin, this install is inactive; "
                    + "put the jar on the launch classpath instead", throwable);
        }
    }

    @Override
    public String name() {
        return "fastevents";
    }

    @Override
    public List<IModFile> scanMods() {
        return Collections.emptyList();
    }

    @Override
    public Path findPath(final IModFile modFile, final String... path) {
        return null;
    }

    @Override
    public void scanFile(final IModFile modFile, final Consumer<Path> pathConsumer) {
    }

    @Override
    public Optional<Manifest> findManifest(final Path file) {
        return Optional.empty();
    }

    @Override
    public void initArguments(final Map<String, ?> arguments) {
        inject();
    }

    @Override
    public boolean isValid(final IModFile modFile) {
        return false;
    }
}
