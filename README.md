# Fast-Events

English | [中文](README.zh-CN.md)

Speeds up `NeoForge.EVENT_BUS` for Minecraft 1.21.1 / NeoForge 21.1.x. Drop-in: no config, no other mods required.

Every listener invocation on the NeoForge bus goes through a generated, fully inlined dispatcher instead of a
megamorphic loop, and event objects are not even constructed when nobody listens to that event class.

| 8 listeners | vanilla | this mod |
|---|---|---|
| `@SubscribeEvent` | 31.2 ns | **8.8 ns** |
| `addListener(consumer)` | 19.0 ns | **8.8 ns** |
| `new Event()` (escaped) | — | ~2.4 ns |

Per post, on NeoForge 21.1.234 / `net.neoforged:bus` 8.0.5, Java 21.

## How it works

`EventBus.post` walks an `EventListener[]` and calls `listeners[i].invoke(event)`. That one call site sees every
listener class in the game, so the JIT cannot inline any of them.

Instead, each `post` call site caches a dispatcher generated for the listener array of that event class: every
listener gets its own field and its own call instruction, so each call site has exactly one receiver type and is
inlined all the way down into the listener body.

The 8.x bus needs very little on top of that: the array carries no priority markers, wrapper listeners are already
unwrapped for non-cancellable events, and cancellation is checked inside the listeners themselves. So the whole
generated class is one field and one call per listener:

```java
public final class Gen0 implements Dispatcher {
    private final EventListener f0;              // one field per listener
    ...
    private final EventListener f7;

    @Override
    public void dispatch(Event event) {
        try { f0.invoke(event); } catch (Throwable t) { throw new Failure(t, 0); }
        ...                                      // one block per listener, in order
    }
}
```

Registrations are left to the bus; the rewritten `addListener` / `register` / `unregister` call sites only drop the
cached dispatchers, so a listener added after a class was first dispatched still takes effect.

## Install

Pick one:

1. **`mods/`** — drop the jar next to your other mods.
2. **Launch classpath** — put it on the launcher classpath instead.

## Config

Optional system properties:

| property | effect |
|---|---|
| `-Dfastevents.guard=false` | disable the "skip `new Event()` when nobody listens" shortcut |
| `-Dfastevents.audit=true` | verify the cached listener array on every post and log late registrations |

## What it covers

* `post(Event)` on `NeoForge.EVENT_BUS`
* `addListener(...)` (all overloads), `register(Object)` and `unregister(Object)` — rewritten only for cache
  invalidation, behaviour is unchanged
* a call site that posts several event classes keeps a cache entry per class

`post` is only taken over when the bus is `NeoForge.EVENT_BUS`. Not covered: `post(EventPriority, Event)`, and
`post` call sites where the bus reference cannot be determined at load time — those keep the vanilla path.

## Compatibility

* Minecraft 1.21.1, NeoForge 21.1.x — tested with `net.neoforged:bus` 8.0.5.
* Client and server.
* Only replaces call sites; no mixins, no changes to NeoForge or the event bus.

The Minecraft 1.20.1 / Forge 47 build lives on the `1.20` branch.
