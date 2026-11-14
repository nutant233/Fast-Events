// End-to-end check for the 1.19.2 build: runs inside a real Forge 1.19.2 server next to this mod and
// Micle's Login Protection (modid loginprotection), and prints what actually happens on the bus.
// Minecraft classes are only touched reflectively and only through descriptor lookups, because their
// members are SRG-named at runtime and official names would not resolve.
package net.nutant.e2e;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

@Mod("fastevents_e2e")
public final class E2ETest {

    private static int keyHits;
    private static boolean cancelKeys;

    private static final UUID PROBE = UUID.fromString("00000000-0000-0000-0000-00000000e2e0");

    public E2ETest() {
        // LOWEST so Login Protection's own listeners (NORMAL) have already run when we check
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, E2ETest::onServerStarted);
    }

    private static void onServerStarted(final ServerStartedEvent event) {
        try {
            run(event);
        } catch (final Throwable throwable) {
            System.out.println("[E2E] FATAL " + throwable);
            throwable.printStackTrace();
        } finally {
            System.out.flush();
            try {
                Thread.sleep(1000L);
            } catch (final InterruptedException ignored) {
            }
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
    }

    private static void run(final ServerStartedEvent event) throws Exception {
        final ClassLoader loader = E2ETest.class.getClassLoader();
        System.out.println("[E2E] ServerStartedEvent reached the test mod");

        try {
            Class.forName("io.github.nutant.fastevents.FastDispatch", false, loader);
            System.out.println("[E2E] FastDispatch visible to game classes: yes");
        } catch (final Throwable throwable) {
            System.out.println("[E2E] FastDispatch visible to game classes: NO (" + throwable + ")");
        }

        // Login Protection sets Proxy.server from its own addListener(Consumer) registration. If that
        // never runs, addPlayer() swallows the resulting NPE and the player stays JOINING forever.
        final Class<?> proxy = Class.forName("dev.micle.loginprotection.proxy.Proxy");
        final Field serverField = proxy.getDeclaredField("server");
        serverField.setAccessible(true);
        System.out.println("[E2E] loginprotection Proxy.server after ServerStartedEvent: "
                + (serverField.get(null) != null ? "set" : "NULL"));

        final Class<?> manager = Class.forName("dev.micle.loginprotection.data.ProtectedPlayerManager");
        manager.getMethod("addPlayer", UUID.class).invoke(null, PROBE);
        final Object entry = manager.getMethod("getPlayer", UUID.class).invoke(null, PROBE);
        System.out.println("[E2E] loginprotection state after addPlayer: "
                + (entry == null ? "NULL" : entry.getClass().getMethod("getState").invoke(entry)));

        damageTest(event);
        keyInputTest();
        System.out.println("[E2E] DONE");
    }

    /** Login Protection cancels LivingDamageEvent unless the player state is ACTIVE. */
    private static void damageTest(final ServerStartedEvent event) throws Exception {
        final Object server = event.getServer();
        final Object level = noArgReturning(server, Class.forName("net.minecraft.server.level.ServerLevel")).invoke(server);
        final Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
        final Object fakePlayer = Class.forName("net.minecraftforge.common.util.FakePlayerFactory")
                .getMethod("getMinecraft", serverLevel).invoke(null, level);
        final UUID fakeId = (UUID) noArgReturning(fakePlayer, UUID.class).invoke(fakePlayer);
        final Class<?> manager = Class.forName("dev.micle.loginprotection.data.ProtectedPlayerManager");
        manager.getMethod("addPlayer", UUID.class).invoke(null, fakeId);

        final Class<?> damageSourceClass = Class.forName("net.minecraft.world.damagesource.DamageSource");
        final Object damageSource = damageSourceClass.getConstructor(String.class).newInstance("generic");
        final Class<?> damageClass = Class.forName("net.minecraftforge.event.entity.living.LivingDamageEvent");
        final Constructor<?> constructor = damageClass.getConstructor(
                Class.forName("net.minecraft.world.entity.LivingEntity"), damageSourceClass, float.class);
        final Method isCanceled = damageClass.getMethod("isCanceled");

        final Object joining = constructor.newInstance(fakePlayer, damageSource, 5.0f);
        MinecraftForge.EVENT_BUS.post((Event) joining);
        System.out.println("[E2E] LivingDamageEvent while JOINING: cancelled=" + isCanceled.invoke(joining));

        final Class<?> stateClass = Class.forName("dev.micle.loginprotection.data.ProtectedPlayer$State");
        final Object entry = manager.getMethod("getPlayer", UUID.class).invoke(null, fakeId);
        entry.getClass().getMethod("setState", (Class<?>) stateClass)
                .invoke(entry, Enum.valueOf((Class<Enum>) stateClass, "ACTIVE"));
        final Object active = constructor.newInstance(fakePlayer, damageSource, 5.0f);
        MinecraftForge.EVENT_BUS.post((Event) active);
        System.out.println("[E2E] LivingDamageEvent while ACTIVE: cancelled=" + isCanceled.invoke(active));
    }

    /**
     * The shape Login Protection depends on for leaving JOINING: a void post of a client input event whose
     * guard shortcut may skip constructing the event when no listener exists.
     */
    private static void keyInputTest() throws Exception {
        MinecraftForge.EVENT_BUS.register(new E2ETest());
        final Class<?> factory = Class.forName("net.minecraftforge.client.ForgeHooksClient");
        final Method onKeyInput = factory.getMethod("onKeyInput", int.class, int.class, int.class, int.class);
        onKeyInput.invoke(null, 87, 0, 1, 0);
        System.out.println("[E2E] ForgeHooksClient.onKeyInput (passive listener) -> handler hits=" + keyHits);
        cancelKeys = true;
        onKeyInput.invoke(null, 87, 1, 0, 0);
        System.out.println("[E2E] ForgeHooksClient.onKeyInput (cancelling listener) -> handler hits=" + keyHits);
    }

    @SubscribeEvent
    public void onKey(final net.minecraftforge.client.event.InputEvent.Key event) {
        keyHits++;
        if (cancelKeys) {
            event.setCanceled(true);
        }
    }

    private static Method noArgReturning(final Object instance, final Class<?> type) throws NoSuchMethodException {
        for (final Method method : instance.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == type) {
                return method;
            }
        }
        throw new NoSuchMethodException("no no-arg method returning " + type.getName()
                + " on " + instance.getClass().getName());
    }
}
