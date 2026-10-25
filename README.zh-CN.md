# Fast-Events

为 Minecraft 1.20.1 / Forge 47.x 加速 `MinecraftForge.EVENT_BUS`。开箱即用，不需要配置，也不依赖其它模组。

Forge 总线上的每次监听器调用都会走到一个生成出来的、完全内联的分发器上，不再经过多态循环；某个事件类没人监听时，
连事件对象都不会构造。

[English](README.md) | 中文

| 8 个监听器 | 原生 | 本模组 |
|---|---|---|
| `@SubscribeEvent` | 49.6 ns | **9.3 ns** |
| `addListener(this::x)`（8 个不同方法引用） | 46.4 ns | **8.2 ns** |
| `addListener(consumer)` | 15.7 ns | **5.8 ns** |
| 带泛型过滤（`AttachCapabilitiesEvent<T>` 这类） | 52.2 ns | **12.4 ns** |
| 8 个监听器散在 5 个优先级 | — | **5.7 ns** |
| 只换分发循环（8 个原生监听器） | 15.7 ns | 12.0 ns |
| `new Event()`（逃逸） | — | ~3.3 ns |

每次 post，环境为 Forge 47.4.x / eventbus 6.2.33、Java 21。监听器更多的宽事件同样领先原生：128 个 201 ns → **88 ns**，
512 个 1090 ns → **556 ns**。

## 实现原理

原生 `EventBus.post` 遍历 `IEventListener[]`，调用 `listeners[i].invoke(event)`。这一个调用点会看到游戏里所有监听器
实现类，JIT 无法内联其中任何一个。

所以每个 `post` 调用点会缓存一个按该事件类监听器数组生成的 dispatcher：**每个监听器一个字段、一条调用指令**，
调用点于是只见过一种接收者类型，能一路内联到监听器体里。优先级标记内联成 `event.setPhase(...)`，非 `@Cancelable`
的事件连 `isCanceled()` 检查都不生成，同时注册路径也被接管，`addListener(consumer)` 直接跳过 Forge 的
`doCastFilter` / `NamedEventListener` 两层。

8 个 `NORMAL` 优先级的 consumer 监听器，生成的类大致是这样：

```java
public final class Gen0 implements Dispatcher {
    private final Consumer f0;                  // 每个监听器一个字段
    ...
    private final Consumer f7;

    @Override
    public void dispatch(Event event) {
        event.setPhase(EventPriority.NORMAL);   // 优先级标记直接内联
        try { f0.accept(event); } catch (Throwable t) { throw new Failure(t, 1); }
        ...                                     // 每个监听器一段，按顺序
    }

    @Override
    public boolean dispatchAndCancelled(Event event) {
        dispatch(event);
        return false;                           // 非 @Cancelable，不调 isCanceled()
    }
}
```

用 `@SubscribeEvent` 注册的监听器，字段是 `IEventListener` 而不是 `Consumer`：里面装的是 Forge 生成的那个 handler，
`ASMEventHandler` 的包装已经被剥掉，所以这类监听器还省掉一次虚调用和包装里原本要做的取消/过滤判断。

dispatcher 在某个事件类第一次 post 时懒生成，该类的监听器数组变化时重建。同一个调用点若发多个事件类，每个类各缓存一份。

## 安装

二选一：

1. **`mods/`** —— 和其它模组放在一起即可。
2. **启动 classpath** —— 放进启动器的 classpath。

一般用 `mods/`；这个 jar 会把自己注册成 transformer，不会被当成普通模组加载。

## 配置

可选系统属性：

| 属性 | 作用 |
|---|---|
| `-Dfastevents.guard=false` | 关闭"没人监听就跳过 `new Event()`"的短路 |
| `-Dfastevents.audit=true` | 每次 post 核对缓存的监听器数组，并记录晚注册 |
| `-Dfastevents.diag=true` | 记录那些通过本模组未接管路径注册的监听器 |

## 覆盖范围

* `post(Event)` 与 `post(Event, IEventBusInvokeDispatcher)`
* `addListener(...)` 与 `addGenericListener(...)` —— 全部重载
* `unregister(Object)`

`post` 只在总线是 `MinecraftForge.EVENT_BUS` 时接管。注册在任意 `EventBus` 上都成立，所以模组自己的私有总线仍保有
它自己的监听器；遇到意外情况一律回退到原生调用。

未接管：`bus.register(obj)`（保持原样，本来也不贵）；以及总线引用在加载期无法确定的 `post` 调用点——这些仍走原生路径。

## 兼容性

* Minecraft 1.20.1、Forge 47.x —— 已验证 eventbus 6.0.5 与 6.2.33，以及 `-Deventbus.namelisteners=true`。
* 客户端与服务端通用。
* 只改写调用点；不使用 Mixin，不修改 Forge 与 eventbus 的任何类。
