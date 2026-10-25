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

One jar per game version. Both are the same mod, different branch of the source tree.

| | 1.20.1 build | 1.21.1 build |
|---|---|---|
| File to upload | `fastevents-1.20-26.10.1.jar` | `fastevents-1.21-26.10.1.jar` |
| Display name | `Fast-Events 1.20-26.10.1 — Forge 1.20.1` | `Fast-Events 1.21-26.10.1 — NeoForge 1.21.1` |
| Game version | `1.20.1` | `1.21.1` |
| Mod loader | `Forge` | `NeoForge` |
| Release type | Release | Release |
| Additional files | — | — |
| Changelog | below | below |

Both files should be selected on the default / featured list; if CurseForge asks for a "primary" file, pick the 1.21.1
one (newest game version).

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

## Description

Paste `description.html` into the description editor (English — recommended as the project's main description).

`description.zh-CN.html` is a full Chinese translation. CurseForge has one description per project, so pick one, or
append the Chinese sections at the end of the English page under a `中文` heading.

The markup deliberately uses only `p`, `h2`, `h3`, `ul`, `ol`, `li`, `table`, `thead`, `tbody`, `tr`, `th`, `td`,
`pre`, `code`, `strong`, `em` and `&mdash;`/`&times;` entities, no CSS and no `<style>` block, so CurseForge's sanitizer
keeps all of it. If the editor has a source/HTML view, paste the file as is; if it is markdown-only, the same structure
survives as HTML embedded in markdown.

## Optional gallery image

`bench.png` is a two-panel bar chart of the numbers (1.20.1 on the left, 1.21.1 on the right, vanilla vs Fast-Events).
Upload it as an image in the description where the "Numbers" section is, or into the project gallery.

## Icon

`icon-400.png` is the upload target. `icon-128.png` and `icon-64.png` are the same artwork at smaller sizes, for the
gallery and for checking legibility in the mod list. `make-icon.ps1` regenerates all three if you want to change
colours or shapes — the design is a lightning bolt (one event) fanning out to three listeners, on a dark rounded
square.

## Publishing checklist

1. Create the project with the values above, upload `icon-400.png`.
2. Paste `description.html`, set source/issues URLs, choose the LGPL 3.0 license.
3. Upload `fastevents-1.21-26.10.1.jar`: game version 1.21.1, loader NeoForge, release, changelog above.
4. Upload `fastevents-1.20-26.10.1.jar`: game version 1.20.1, loader Forge, release, its changelog.
5. Add a `LICENSE` file (LGPL-3.0 text) to the repository — the mod metadata declares LGPL 3.0 but the source tree does
   not ship the license text yet, and CurseForge reviewers follow that link.
6. Check the rendered page: tables, the two `pre` blocks and the entities should survive the editor round trip.
