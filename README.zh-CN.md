# Fast-Events

为 Minecraft 1.21.1 / NeoForge 21.1.x 加速 `NeoForge.EVENT_BUS`。开箱即用，不需要配置，也不依赖其它模组。

Forge 总线上的每次监听器调用都会走到一个生成出来的、完全内联的分发器上，不再经过多态循环；某个事件类没人监听时，
连事件对象都不会构造。

[English](README.md) | 中文

| 8 个监听器 | 原生 | 本模组 |
|---|---|---|
| `@SubscribeEvent` | 35.8 ns | **10.5 ns** |
| `addListener(consumer)` | 19.4 ns | **6.7 ns** |
| `new Event()`（逃逸） | — | ~2.4 ns |

每次 post，环境为 NeoForge 21.1.234 / `net.neoforged:bus` 8.0.5、Java 21。consumer 监听器在生成器改为直接调用
注册的 consumer、而不是走总线的包装类之前是 10.7 ns。

## 实现原理

原生 `EventBus.post` 遍历 `EventListener[]`，调用 `listeners[i].invoke(event)`。这一个调用点会看到游戏里所有监听器
实现类，JIT 无法内联其中任何一个。

所以每个 `post` 调用点会缓存一个按该事件类监听器数组生成的 dispatcher：**每个监听器一个字段、一条调用指令**，
调用点于是只见过一种接收者类型，能一路内联到监听器体里。

8.x 的总线在数组里直接存的是 `ConsumerEventHandler`（consumer）和 `SubscribeEventListener`（`@SubscribeEvent`），
所以生成器在编译期把它们拆开：consumer 直接 `accept(event)`，带过滤的再加一条 `predicate.test(event)`，
`@SubscribeEvent` 取出里面的 handler 直接 `invoke`，需要时把取消判断内联在前面。生成出来的类长这样：

```java
public final class Gen0 implements Dispatcher {
    private final Consumer f0;                   // 每个监听器一个字段，类型是拆出来的真实目标
    ...
    @Override
    public void dispatch(Event event) {
        try { f0.accept(event); } catch (Throwable t) { throw new Failure(t, 0); }
        ...                                      // 每个监听器一段，按顺序
    }
}
```

注册仍然交给总线；被改写的 `addListener` / `register` / `unregister` 调用点只做一件事——丢掉缓存的 dispatcher，
所以某个事件类在第一次分发之后才注册的监听器同样会生效。

## 安装

二选一：

1. **`mods/`** —— 和其它模组放在一起即可。
2. **启动 classpath** —— 放进启动器的 classpath。

## 配置

可选系统属性：

| 属性 | 作用 |
|---|---|
| `-Dfastevents.guard=false` | 关闭"没人监听就跳过 `new Event()`"的短路 |
| `-Dfastevents.audit=true` | 每次 post 核对缓存的监听器数组，并记录晚注册 |
| `-Dfastevents.exclude=a.b,c.d` | 这些包下的类完全不做改写（排查用的逃生开关） |

## 覆盖范围

* `NeoForge.EVENT_BUS` 上的 `post(Event)`
* `addListener(...)`（全部重载）、`register(Object)`、`unregister(Object)` —— 只做缓存失效改写，行为不变
* 同一个调用点若发多个事件类，每个类各缓存一份

`post` 只在总线是 `NeoForge.EVENT_BUS` 时接管。未接管：`post(EventPriority, Event)`，以及总线引用在加载期无法确定的
`post` 调用点——这些仍走原生路径。

## 兼容性

* Minecraft 1.21.1、NeoForge 21.1.x —— 已验证 `net.neoforged:bus` 8.0.5。
* 客户端与服务端通用。
* 只改写调用点；不使用 Mixin，不修改 NeoForge 与 event bus 的任何类。

## 版本

每个 Minecraft 版本一个分支，代码相同，只是目标总线和加载器不同。

| Minecraft | 加载器 | 分支 |
|---|---|---|
| 1.21.1 | NeoForge 21.1.x | `1.21` |
| 1.20.1 | Forge 47.x | `1.20` |
| 1.19.2 | Forge 43.x | `1.19` |
| 1.18.2 | Forge 40.x | `1.18` |
| 1.16.5 | Forge 36.x | `1.16` |
