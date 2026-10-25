# Fast-Events

English | [中文](README.zh-CN.md)

Speeds up `MinecraftForge.EVENT_BUS` for Minecraft 1.16.5 / Forge 36.x. Drop-in: no config, no other mods required.

Every listener invocation on the Forge bus goes through a generated, fully inlined dispatcher instead of a
megamorphic loop, and event objects are not even constructed when nobody listens to that event class.

| 8 listeners | vanilla | this mod |
|---|---|---|
| `@SubscribeEvent` | 92.5 ns | **15.4 ns** |
| `addListener(consumer)` | 34.7 ns | **12.5 ns** |
| 128 listeners on one class | 794 ns | **201 ns** |
| 512 listeners on one class | 3465 ns | **493 ns** |

Per post, on Forge 36.2.x / eventbus 4.0.0, **Java 8** (the runtime 1.16.5 actually uses). Everything is compiled to
Java 8 bytecode, so the mod loads on the Java 8 that ships with the vanilla launcher.

## Versions

One branch per Minecraft version; the code is the same, only the bus and loader it targets differ.

| Minecraft | Loader | Branch |
|---|---|---|
| 1.21.1 | NeoForge 21.1.x | `1.21` |
| 1.20.1 | Forge 47.x | `1.20` |
| 1.19.2 | Forge 43.x | `1.19` |
| 1.18.2 | Forge 40.x | `1.18` |
| 1.16.5 | Forge 36.x | `1.16` |

## How it works

`EventBus.post` walks an `IEventListener[]` and calls `listeners[i].invoke(event)`. That one call site sees every
listener class in the game, so the JIT cannot inline any of them.

Instead, each `post` call site caches a dispatcher generated for the listener array of that event class: every
listener gets its own field and its own call instruction, so each call site has exactly one receiver type and is
inlined all the way down into the listener body. Priority markers become `event.setPhase(...)` calls, the
`isCanceled()` check disappears for non-cancelable events, and registration is taken over as well, so
`addListener(consumer)` skips Forge's cast-filter and `NamedEventListener` layers.

For 8 consumer listeners at `NORMAL` priority the generated class is essentially this:

```java
public final class Gen0 implements Dispatcher {
    private final Consumer f0;                  // one field per listener
    ...
    private final Consumer f7;

    @Override
    public void dispatch(Event event) {
        event.setPhase(EventPriority.NORMAL);   // priority marker, inlined
        try { f0.accept(event); } catch (Throwable t) { throw new Failure(t, 1); }
        ...                                     // one block per listener, in order
    }

    @Override
    public boolean dispatchAndCancelled(Event event) {
        dispatch(event);
        return false;                           // not @Cancelable, so no isCanceled() call
    }
}
```

The dispatcher is built lazily on the first post of an event class and rebuilt when that class's listener array
changes. A call site that posts more than one event class keeps a cache entry per class.

## Install

Pick one:

1. **`mods/`** — drop the jar next to your other mods.
2. **Launch classpath** — put it on the launcher classpath instead.

`mods/` is the normal way; the jar registers itself as a transformer and is not loaded as a regular mod. On Java 8 it
also exposes its own classes to the game class loader, which is what makes the rewritten call sites work — see
Compatibility below.

## Config

Optional system properties:

| property | effect |
|---|---|
| `-Dfastevents.guard=false` | disable the "skip `new Event()` when nobody listens" shortcut |
| `-Dfastevents.audit=true` | verify the cached listener array on every post and log late registrations |
| `-Dfastevents.diag=true` | log listeners that were registered through a path this mod does not rewrite |

## What it covers

* `post(Event)` and `post(Event, IEventBusInvokeDispatcher)`
* `addListener(...)` and `addGenericListener(...)` — all overloads
* `unregister(Object)`

`post` is only taken over when the bus is `MinecraftForge.EVENT_BUS`. Registrations work on any `EventBus`, so a
mod's private bus keeps its own listeners; anything unexpected falls back to the vanilla call.

Not covered: `bus.register(obj)` (unchanged, and already cheap) and `post` call sites where the bus reference cannot
be determined at load time — those keep the vanilla path.

## Compatibility

* Minecraft 1.16.5, Forge 36.x — tested with eventbus 4.0.0.
* Client and server.
* **Java 8** is the supported runtime. 1.16.5's modlauncher keeps transformer jars in a class loader that is a
  sibling of the game's own, so a rewritten `post` site can only call back into this mod if its classes are reachable
  from the game class loader. On Java 8 the mod makes that happen itself; on Java 9+ that is not possible, so there
  the jar has to be on the launch classpath instead. The log says which of the two happened:
  `[FastEvents] exposed ... to the game class loader` (active) or
  `[FastEvents] could not expose this mod to the game class loader` (inactive, and no call site is rewritten).
* Only replaces call sites; no mixins, no changes to Forge or eventbus classes.
