// Reproduces the Login Protection (1.19.2) pattern against this mod, to find out which of the two
// registration styles it uses still works:
//   * four @SubscribeEvent handler objects registered with IEventBus.register(Object)
//   * three static-method consumers registered with IEventBus.addListener(Consumer)  (a rewritten call site)
// and then replays its state machine: join -> JOINING, key input -> updateState -> ACTIVE, damage while JOINING
// is cancelled while damage afterwards is not.
package harness;

import io.github.nutant.fastevents.FastDispatch;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LpScenario {

    // Like dev.micle.loginprotection.proxy.Proxy
    static Object server;
    static final Map<UUID, String> states = new HashMap<>();
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static int inputPackets;
    static int damageCancelled;
    static int damageApplied;

    private static int failures;

    private LpScenario() {
    }

    public static void main(final String[] args) {
        final IEventBus bus = MinecraftForge.EVENT_BUS;

        // Proxy() constructor: addListener(Consumer) call sites (rewritten by this mod)
        FastDispatch.addListener(bus, LpScenario::serverStarted);
        FastDispatch.addListener(bus, LpScenario::serverStopping);
        // Proxy() constructor: register(Object) call sites (not rewritten)
        bus.register(new Handlers());

        // the server starts -> ProtectedPlayerManager.init(); server = event.getServer()
        post(new ServerStarted());
        check(server != null, "addListener(Consumer) method reference is delivered (server field set)");

        // player joins -> addPlayer(uuid) -> state JOINING
        post(new PlayerLoggedIn());
        check("JOINING".equals(states.get(PLAYER)), "join puts the player in JOINING, got " + states.get(PLAYER));

        // damage while JOINING must be cancelled (this is Login Protection's intended behaviour)
        final Hurt early = new Hurt();
        post(early);
        check(early.isCanceled(), "damage while JOINING is cancelled, as designed");
        check(damageCancelled == 1, "the cancel path ran once, got " + damageCancelled);

        // the player presses a key -> client sends InputPacket -> updateState -> ACTIVE
        post(new KeyInput());
        check(inputPackets == 1, "key input produced one InputPacket, got " + inputPackets);
        check("ACTIVE".equals(states.get(PLAYER)), "updateState moves the player to ACTIVE, got " + states.get(PLAYER));

        // damage after ACTIVE must not be cancelled
        final Hurt late = new Hurt();
        post(late);
        check(!late.isCanceled(), "damage after ACTIVE is not cancelled");
        check(damageApplied == 1, "the apply path ran once, got " + damageApplied);

        // and a second player that stays in JOINING keeps the protection
        final UUID idle = UUID.fromString("00000000-0000-0000-0000-000000000002");
        states.put(idle, "JOINING");
        final Hurt idleHurt = new Hurt(idle);
        post(idleHurt);
        check(idleHurt.isCanceled(), "a player that never sent input stays protected");

        System.out.println();
        System.out.println(failures == 0 ? "LP SCENARIO PASSED" : ("LP SCENARIO FAILED: " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void post(final Event event) {
        FastDispatch.post(MinecraftForge.EVENT_BUS, event, FastDispatch.holder());
    }

    private static void check(final boolean condition, final String message) {
        if (condition) {
            System.out.println("  [ok]   " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }

    // Proxy::serverStarted / Proxy::serverStopping, as registered by a rewritten addListener call site
    static void serverStarted(final ServerStarted event) {
        states.clear();
        server = event.getServer();
    }

    static void serverStopping(final ServerStopping event) {
        server = null;
    }

    // dev.micle.loginprotection.events.common.* and events.client.OnClientInputEventHandler,
    // all registered with IEventBus.register(Object)
    public static final class Handlers {
        @SubscribeEvent
        public void LivingHurtEvent(final Hurt event) {
            final String state = states.get(event.player);
            if (state == null || "ACTIVE".equals(state)) {
                damageApplied++;
                return;
            }
            event.setCanceled(true);
            damageCancelled++;
        }

        @SubscribeEvent
        public void EntityJoinWorldEvent(final PlayerLoggedIn event) {
            if (!states.containsKey(event.player)) {
                states.put(event.player, "JOINING");
            }
        }

        @SubscribeEvent
        public void KeyInputEvent(final KeyInput event) {
            // OnClientInputEventHandler.updateAndNotify -> InputPacket -> ProtectedPlayerManager.updateState
            inputPackets++;
            if ("JOINING".equals(states.get(event.player))) {
                states.put(event.player, "ACTIVE");
            }
        }
    }

    public static class ServerStarted extends Event {
        Object getServer() {
            return this;
        }
    }

    public static class ServerStopping extends Event {
    }

    public static class PlayerLoggedIn extends Event {
        final UUID player = PLAYER;
    }

    @Cancelable
    public static class Hurt extends Event {
        final UUID player;

        public Hurt() {
            this(PLAYER);
        }

        public Hurt(final UUID player) {
            this.player = player;
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    public static class KeyInput extends Event {
        final UUID player = PLAYER;
    }
}
