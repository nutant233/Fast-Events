# CurseForge metadata

Everything needed to create the project and the two files. Copy the values verbatim; the long texts live in
`description.html` (English) and `description.zh-CN.html` (Chinese).

## Project

| Field | Value |
|---|---|
| Project name | `Fast-Events` |
| Slug | `fast-events` |
| Category | Mods → **Optimization** (the category FPS/TPS mods use) |
| License | GNU Lesser General Public License v3.0 (matches `LGPL 3.0` in the mod metadata) |
| Source URL | `https://github.com/nutant233/Fast-Events` |
| Issues URL | `https://github.com/nutant233/Fast-Events/issues` |
| Support URL | `https://github.com/nutant233/Fast-Events/issues` |
| Wiki | leave empty |
| Icon | `icon-400.png` (400×400 PNG, transparent rounded corners) |
| Summary | see below |

Suggested search keywords, for the summary and the first paragraph: *event bus, event dispatch, performance,
optimization, TPS, lag, Forge, NeoForge, tick, listener loop*.

## Summary (max 256 characters)

```
Rewrites every event-bus post into a generated, fully inlined dispatcher: 8 listeners cost ~9 ns per post instead of ~50 ns (Forge 1.20.1) or ~31 ns (NeoForge 1.21.1). Drop-in, no config, no Mixin, and it speeds up every mod's event posts.
```

239 characters — fits. The Chinese page can use:

```
把每次事件发布改写成生成的内联分发器：8 个监听器每次 post 从 ~50 ns（Forge 1.20.1）/~31 ns（NeoForge 1.21.1）降到 ~9 ns。丢进 mods 即用，无需配置，不用 Mixin，并且加速所有模组的事件发布。
```

## Files

One jar per game version. They are the same mod, one branch of the source tree each.

| File to upload | Display name | Game version | Mod loader | Release type |
|---|---|---|---|---|
| `fastevents-1.21-26.10.1.jar` | `Fast-Events 1.21-26.10.1 — NeoForge 1.21.1` | `1.21.1` | `NeoForge` | Release |
| `fastevents-1.20-26.10.1.jar` | `Fast-Events 1.20-26.10.1 — Forge 1.20.1` | `1.20.1` | `Forge` | Release |
| `fastevents-1.19-26.10.1.jar` | `Fast-Events 1.19-26.10.1 — Forge 1.19.2` | `1.19.2` | `Forge` | Release |
| `fastevents-1.18-26.10.1.jar` | `Fast-Events 1.18-26.10.1 — Forge 1.18.2` | `1.18.2` | `Forge` | Release |
| `fastevents-1.16-26.10.1.jar` | `Fast-Events 1.16-26.10.1 — Forge 1.16.5` | `1.16.5` | `Forge` | Release |

Each jar's changelog is below. Select every file on the default / featured list; if CurseForge asks for a "primary"
file, pick the 1.21.1 one (newest game version).

The 1.16.5 jar is compiled to Java 8 bytecode, so it loads on the Java 8 that the vanilla launcher ships.

## Changelog — 1.20-26.10.1 (Forge 1.20.1)

```markdown
First CurseForge release.

* Every `post` call site on `MinecraftForge.EVENT_BUS` now dispatches through a generated class: one field and one call
  instruction per listener, so the JIT inlines straight into the listener body instead of going through the
  megamorphic `listeners[i].invoke(event)` loop.
* Event objects are no longer constructed when the event class has no listeners.
* Priority markers are inlined; the `isCanceled()` check is dropped for non-cancelable events.
* `addListener` / `addGenericListener` / `unregister` call sites are rewritten for cache invalidation only —
  registration semantics are unchanged.
* A call site that posts several event classes keeps one cache entry per class.
* Verified with the bundled harness against eventbus 6.0.5 and 6.2.33: listener order, cancellation, generic filters,
  late registration and 1024-listener arrays.
* Measured (8 listeners, Java 21): `@SubscribeEvent` 49.6 → 9.3 ns, `addListener(this::x)` 46.4 → 8.2 ns,
  consumer listeners 15.7 → 5.8 ns, generic listeners 52.2 → 12.4 ns per post.
```

## Changelog — 1.21-26.10.1 (NeoForge 1.21.1)

```markdown
First CurseForge release, ported to NeoForge 21.1 and `net.neoforged:bus` 8.0.5.

* Every `post` call site on `NeoForge.EVENT_BUS` now dispatches through a generated class: one field and one call
  instruction per listener, so the JIT inlines straight into the listener body instead of going through the
  megamorphic `listeners[i].invoke(event)` loop.
* Event objects are no longer constructed when the event class has no listeners.
* Cancellation and wrapper unwrapping are handled by the 8.x bus already, so the generated class stays minimal; the
  cache is keyed by bus identity and event class, so a call site that posts several classes keeps one entry per class.
* `addListener` / `register` / `unregister` call sites are rewritten for cache invalidation only — registration
  semantics are unchanged.
* Verified with the bundled harness against `net.neoforged:bus` 8.0.5: listener order, cancellation, generic filters,
  late registration and 1024-listener arrays.
* Measured (8 listeners, Java 21): `@SubscribeEvent` 31.2 → 8.8 ns, consumer listeners 19.0 → 8.8 ns per post.
```

## Changelog — 1.19-26.10.1 (Forge 1.19.2)

```markdown
First CurseForge release for Minecraft 1.19.2 / Forge 43.x (`net.minecraftforge:eventbus` 6.0.3).

* Every `post` call site on `MinecraftForge.EVENT_BUS` now dispatches through a generated class: one field and one call
  instruction per listener, so the JIT inlines straight into the listener body instead of going through the
  megamorphic `listeners[i].invoke(event)` loop.
* Event objects are no longer constructed when the event class has no listeners.
* Priority markers are inlined; the `isCanceled()` check is dropped for non-cancelable events.
* `addListener` / `addGenericListener` / `unregister` call sites are rewritten for cache invalidation only.
* Verified with the bundled harness against eventbus 6.0.3 and 6.0.5: listener order, cancellation, generic filters,
  late registration and wide listener arrays.
* Measured (8 listeners, Java 21): `@SubscribeEvent` 50.5 → 9.5 ns, `addListener(this::x)` 52.4 → 7.9 ns,
  consumer listeners 20.6 → 5.9 ns, generic listeners 49.7 → 13.8 ns per post.
```

## Changelog — 1.18-26.10.1 (Forge 1.18.2)

```markdown
First CurseForge release for Minecraft 1.18.2 / Forge 40.x (`net.minecraftforge:eventbus` 5.0.3).

* Every `post` call site on `MinecraftForge.EVENT_BUS` now dispatches through a generated class: one field and one call
  instruction per listener, so the JIT inlines straight into the listener body instead of going through the
  megamorphic `listeners[i].invoke(event)` loop.
* Event objects are no longer constructed when the event class has no listeners.
* Priority markers are inlined; the `isCanceled()` check is dropped for non-cancelable events.
* `addListener` / `addGenericListener` / `unregister` call sites are rewritten for cache invalidation only.
* Verified with the bundled harness against eventbus 5.0.3: listener order, cancellation, generic filters, late
  registration and wide listener arrays.
* Measured (8 listeners, Java 21): `@SubscribeEvent` 52.2 → 9.3 ns, `addListener(this::x)` 51.2 → 7.8 ns,
  consumer listeners 20.2 → 6.1 ns, generic listeners 49.1 → 12.7 ns per post.
```

## Changelog — 1.16-26.10.1 (Forge 1.16.5)

```markdown
First CurseForge release for Minecraft 1.16.5 / Forge 36.x (`net.minecraftforge:eventbus` 4.0.0).

* Every `post` call site on `MinecraftForge.EVENT_BUS` now dispatches through a generated class: one field and one call
  instruction per listener, so the JIT inlines straight into the listener body instead of going through the
  megamorphic `listeners[i].invoke(event)` loop.
* Event objects are no longer constructed when the event class has no listeners.
* Priority markers are inlined; the `isCanceled()` check is dropped for non-cancelable events.
* `addListener` / `addGenericListener` / `unregister` call sites are rewritten for cache invalidation only.
* Compiled to Java 8 bytecode, because that is the runtime 1.16.5 uses; verified on a real Java 8 JVM as well as with
  the bundled harness against eventbus 4.0.0 (listener order, cancellation, late registration, private buses).
* Measured (8 listeners, Java 8): `@SubscribeEvent` 92.5 → 15.4 ns, consumer listeners 34.7 → 12.5 ns,
  128 listeners 794 → 201 ns, 512 listeners 3465 → 493 ns per post.
```

## Description

Paste `description.html` into the description editor (English — recommended as the project's main description).

`description.zh-CN.html` is a full Chinese translation. CurseForge has one description per project, so pick one, or
append the Chinese sections at the end of the English page under a `中文` heading.

The markup deliberately uses only `p`, `h2`, `h3`, `ul`, `ol`, `li`, `table`, `thead`, `tbody`, `tr`, `th`, `td`,
`pre`, `code`, `strong`, `em` and `&mdash;`/`&times;` entities, no CSS and no `<style>` block, so CurseForge's sanitizer
keeps all of it. If the editor has a source/HTML view, paste the file as is; if it is markdown-only, the same structure
survives as HTML embedded in markdown.

## Optional gallery image

`bench.png` is a two-panel bar chart of the numbers (every version side by side on the left, the 1.20.1 listener-kind breakdown on the right, vanilla vs Fast-Events).
Upload it as an image in the description where the "Numbers" section is, or into the project gallery. The 1.19.2,
1.18.2 and 1.16.5 numbers are in the description's version table; `make-chart.ps1` regenerates the image if you want
the older versions plotted too.

## Icon

`icon-400.png` is the upload target. `icon-128.png` and `icon-64.png` are the same artwork at smaller sizes, for the
gallery and for checking legibility in the mod list. `make-icon.ps1` regenerates all three if you want to change
colours or shapes — the design is a lightning bolt (one event) fanning out to three listeners, on a dark rounded
square.

## Publishing checklist

1. Create the project with the values above, upload `icon-400.png`.
2. Paste `description.html`, set source/issues URLs, choose the LGPL 3.0 license.
3. Upload all five jars with the game version / loader / changelog from the table above.
4. Add a `LICENSE` file (LGPL-3.0 text) to the repository — the mod metadata declares LGPL 3.0 but the source tree does
   not ship the license text yet, and CurseForge reviewers follow that link.
5. Check the rendered page: tables, the two `pre` blocks and the entities should survive the editor round trip.
