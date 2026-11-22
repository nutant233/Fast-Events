# Fast-Events

English | [中文](README.zh-CN.md)

Speeds up `NeoForge.EVENT_BUS` for Minecraft 1.21.1 / NeoForge 21.1.x. Drop-in: no config, no other mods required.

Every listener invocation on the NeoForge bus goes through a generated, fully inlined dispatcher instead of a
megamorphic loop, and event objects are not even constructed when nobody listens to that event class.

| 8 listeners | vanilla | this mod |
|---|---|---|
| `@SubscribeEvent` | 35.8 ns | **10.8 ns** |
| `addListener(consumer)` | 19.4 ns | **6.9 ns** |
| `new Event()` (escaped) | — | ~2.3 ns |

Per post, on NeoForge 21.1.234 / `net.neoforged:bus` 8.0.5, Java 21. Consumers are called directly; `@SubscribeEvent`
handlers keep the bus's generated handler, because naming another mod's listener class from this mod's generated
class would not link across mod class loaders. Guarded call sites (`if (hasListeners(...))`, which skip building the
event when nobody listens) cost about 0.6 ns extra; `-Dfastevents.guard=false` turns them into plain posts.

## How it works

`EventBus.post` walks an `EventListener[]` and calls `listeners[i].invoke(event)`. That one call site sees every
listener class in the game, so the JIT cannot inline any of them. This mod generates one dispatcher class per
event class, and unpacks the bus's listener wrappers (`ConsumerEventHandler`, `SubscribeEventListener`) so the
generated code calls the consumer or handler directly and applies the cancellation filter inline.

Instead, each `post` call site caches a dispatcher generated for the listener array of that event class: every
listener gets its own field and its own call instruction, so each call site has exactly one receiver type and is
inlined all the way down into the listener body.

The 8.x bus stores `ConsumerEventHandler` for consumer registrations and `SubscribeEventListener` around
`@SubscribeEvent` handlers, so the generator unpacks them: a consumer becomes a `Consumer` field called with
`accept(event)`, its cancellation filter becomes an inline `predicate.test(event)`, and a `@SubscribeEvent` handler is
pulled out of its wrapper and invoked directly with the cancellation check inlined in front of it:

```java
public final class Gen0 implements Dispatcher {
    private final Consumer f0;                   // one field per listener, typed as the unwrapped target
    ...
    @Override
    public void dispatch(Event event) {
        try { f0.accept(event); } catch (Throwable t) { throw new Failure(t, 0); }
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
| `-Dfastevents.exclude=a.b,c.d` | do not touch classes under these packages (diagnostic escape hatch) |

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

## Versions

One branch per Minecraft version; the code is the same, only the bus and loader it targets differ.

| Minecraft | Loader | Branch |
|---|---|---|
| 1.21.1 | NeoForge 21.1.x | `1.21` |
| 1.20.1 | Forge 47.x | `1.20` |
| 1.19.2 | Forge 43.x | `1.19` |
| 1.18.2 | Forge 40.x | `1.18` |
| 1.16.5 | Forge 36.x | `1.16` |
