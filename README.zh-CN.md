# Fast-Events

为 Minecraft 1.16.5 / Forge 36.x 加速 `MinecraftForge.EVENT_BUS`。开箱即用，不需要配置，也不依赖其它模组。

Forge 总线上的每次监听器调用都会走到一个生成出来的、完全内联的分发器上，不再经过多态循环；某个事件类没人监听时，
连事件对象都不会构造。

[English](README.md) | 中文

| 8 个监听器 | 原生 | 本模组 |
|---|---|---|
| `@SubscribeEvent` | 89.3 ns | **18.0 ns** |
| `addListener(consumer)` | 32.2 ns | **12.2 ns** |
| 同一个类 128 个监听器 | 932.8 ns | **216.3 ns** |
| 同一个类 512 个监听器 | 4372.7 ns | **873.9 ns** |

每次 post，环境为 Forge 36.2.x / eventbus 4.0.0、**Java 8**（也就是 1.16.5 实际使用的运行时）。整个模组编译为
Java 8 字节码，原版启动器自带的 Java 8 就能加载。

## 版本

每个 Minecraft 版本一个分支，代码相同，只是目标总线和加载器不同。

| Minecraft | 加载器 | 分支 |
|---|---|---|
| 1.21.1 | NeoForge 21.1.x | `1.21` |
| 1.20.1 | Forge 47.x | `1.20` |
| 1.19.2 | Forge 43.x | `1.19` |
| 1.18.2 | Forge 40.x | `1.18` |
| 1.16.5 | Forge 36.x | `1.16` |

## 实现原理

原生 `EventBus.post` 遍历 `IEventListener[]`，调用 `listeners[i].invoke(event)`。这一个调用点会看到游戏里所有监听器
实现类，JIT 无法内联其中任何一个。

所以每个 `post` 调用点会缓存一个按该事件类监听器数组生成的 dispatcher：**每个监听器一个字段、一条调用指令**，
调用点于是只见过一种接收者类型，能一路内联到监听器体里。优先级标记变成 `event.setPhase(...)` 调用，非可取消事件的
`isCanceled()` 检查直接消失；注册同样被接管，所以 `addListener(consumer)` 跳过了 Forge 的类型过滤层和
`NamedEventListener` 包装。

8 个 `NORMAL` 优先级的 consumer 监听器，生成的类大致是这样：

```java
public final class Gen0 implements Dispatcher {
    private final Consumer f0;                  // 每个监听器一个字段
    ...
    private final Consumer f7;

    @Override
    public void dispatch(Event event) {
        event.setPhase(EventPriority.NORMAL);   // 优先级标记，内联
        try { f0.accept(event); } catch (Throwable t) { throw new Failure(t, 1); }
        ...                                     // 每个监听器一段，按顺序
    }

    @Override
    public boolean dispatchAndCancelled(Event event) {
        dispatch(event);
        return false;                           // 不是 @Cancelable，不调 isCanceled()
    }
}
```

dispatcher 在某个事件类第一次 post 时惰性生成，该类的监听器数组变化时重建。同一个调用点若发多个事件类，每个类各缓存一份。

## 安装

二选一：

1. **`mods/`** —— 和其它模组放在一起即可。
2. **启动 classpath** —— 放进启动器的 classpath。

`mods/` 是常规用法；jar 会把自己注册成 transformer，不会作为普通模组加载。

## 配置

可选系统属性：

| 属性 | 作用 |
|---|---|
| `-Dfastevents.guard=false` | 关闭"没人监听就跳过 `new Event()`"的短路 |
| `-Dfastevents.audit=true` | 每次 post 核对缓存的监听器数组，并记录晚注册 |
| `-Dfastevents.diag=true` | 记录那些经过「未被改写的路径」注册的监听器 |

## 覆盖范围

* `post(Event)` 与 `post(Event, IEventBusInvokeDispatcher)`
* `addListener(...)`、`addGenericListener(...)` —— 全部重载
* `unregister(Object)`

`post` 只在总线是 `MinecraftForge.EVENT_BUS` 时接管。注册对任意 `EventBus` 都生效，所以模组自己的私有总线仍保留自己的监听器；
任何意外情况都回退到原生调用。

未覆盖：`bus.register(obj)`（不做改写，本身已经很便宜），以及总线引用在加载期无法确定的 `post` 调用点——这些仍走原生路径。

## 兼容性

* Minecraft 1.16.5、Forge 36.x —— 已验证 eventbus 4.0.0。
* 客户端与服务端通用。
* Java 8 及以上均可运行。
* 只改写调用点；不使用 Mixin，不修改 Forge 与 eventbus 的任何类。
