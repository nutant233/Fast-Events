package harness;

import io.github.nutant.fastevents.FastDispatch;
import io.github.nutant.fastevents.PostCallRewriter;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.NamedEventListener;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventListener;
import net.minecraftforge.eventbus.api.IEventBus;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** 离线验证：改写 -> 反汇编核对 -> 真实分发执行 -> 前后开销对比。 */
public final class Harness {

    private static int failures = 0;

    public static void main(final String[] args) throws Exception {
        final byte[] original = readClass("test/TestTarget.class");
        final ClassNode before = parse(original);

        System.out.println("=== 改写前: test.TestTarget#postNew ===");
        dump(before, "postNew");

        final int originalMaxStack = maxStack(before, "postNew");
        final PostCallRewriter rewriter = new PostCallRewriter();
        check(rewriter.rewrite(before) == 10, "应改写 6 个 post + 2 个 addListener + 1 个 addGenericListener + 1 个 unregister，实际 "
                + (rewriter.postSites + rewriter.hookSites));

        check(rewriter.guardSites == 3, "应有 2 个调用点插 guard，实际 " + rewriter.guardSites);
        final byte[] transformed = write(before);
        final ClassNode after = parse(transformed);

        System.out.println();
        System.out.println("=== 改写后: test.TestTarget#postNew ===");
        dump(after, "postNew");

                System.out.println("  [dbg] post=" + count(after, "post", "io/github/nutant/fastevents/FastDispatch")
                + " postVoid=" + count(after, "postVoid", "io/github/nutant/fastevents/FastDispatch")
                + " postWrapped=" + count(after, "postWrapped", "io/github/nutant/fastevents/FastDispatch"));
        check(count(after, "postDynamic", "io/github/nutant/fastevents/FastDispatch") == 0, "不应再使用 postDynamic");
        check(count(after, "post", "io/github/nutant/fastevents/FastDispatch")
                        + count(after, "postVoid", "io/github/nutant/fastevents/FastDispatch") == 5,
                "Forge 总线上的 5 个 post 应改写为 post/postVoid");
        check(count(after, "post", "net/minecraftforge/eventbus/api/IEventBus") == 2, "接收者未验证的 post 应保持原生 2 次");
        check(count(after, "postWrapped", "io/github/nutant/fastevents/FastDispatch") == 1,
                "post(Event, IEventBusInvokeDispatcher) 应被改写 1 次");
        check(count(after, "bump", "io/github/nutant/fastevents/FastDispatch") == 0, "不应再插入 bump");
        check(count(after, "addListener", "io/github/nutant/fastevents/FastDispatch") == 2, "addListener 应被改写 2 次");
        check(count(after, "addGenericListener", "io/github/nutant/fastevents/FastDispatch") == 1,
                "addGenericListener 应被改写 1 次");
        check(count(after, "unregister", "io/github/nutant/fastevents/FastDispatch") == 1, "unregister 应被改写 1 次");
        check(count(after, "addListener", "net/minecraftforge/eventbus/api/IEventBus") == 0, "IEventBus.addListener 应清零");
        check(count(after, "addGenericListener", "net/minecraftforge/eventbus/api/IEventBus") == 0,
                "IEventBus.addGenericListener 应清零");
        check(maxStack(after, "postNew") >= originalMaxStack, "postNew 的 maxStack 不应变小");
        check(holderFields(after) == 6, "应注入 6 个 Holder 字段，实际 " + holderFields(after));
        check(count(after, "holder", "io/github/nutant/fastevents/FastDispatch") == 6, "clinit 应初始化 6 个 Holder");

        final ClassNode second = parse(transformed);
        final int secondChanged = new PostCallRewriter().rewrite(second);
        check(secondChanged == 0, "第二次改写不应再改动字节码，实际 " + secondChanged);
        check(count(second, "holder", "io/github/nutant/fastevents/FastDispatch") == 12,
                "第二次改写也应重放 clinit 初始化（mixin 可能丢掉第一趟的 clinit），实际 "
                        + count(second, "holder", "io/github/nutant/fastevents/FastDispatch"));
        boolean twiceOk = true;
        String twiceError = "";
        try {
            final Class<?> twice = define("test.TestTarget", write(second));
            Class.forName("test.TestTarget", true, twice.getClassLoader());
        } catch (final Throwable throwable) {
            twiceOk = false;
            twiceError = throwable.toString();
        }
        check(twiceOk, "被改写两次的类应能通过校验并初始化，实际 " + twiceError);

        final Class<?> target = define("test.TestTarget", transformed);
        final Class<?> fooEvent = Class.forName("test.TestTarget$FooEvent", true, target.getClassLoader());
        final IEventBus bus = MinecraftForge.EVENT_BUS;

        final int[] hits = {0};
        register(bus, fooEvent, hops(hits));
        final Method postNew = target.getMethod("postNew");
        final Method postFromLocal = target.getMethod("postFromLocal", Event.class);
        final Method postOtherBus = target.getMethod("postOtherBus", IEventBus.class);

        check(!(Boolean) postNew.invoke(null), "未取消的事件应返回 false");
        check(hits[0] == 1, "第一次 post 应命中 1 次监听器，实际 " + hits[0]);

        register(bus, fooEvent, hops(hits));
        postNew.invoke(null);
        check(hits[0] == 2, "数组已缓存，晚注册的监听器不会生效，实际 " + hits[0]);

        final Event cancelled = (Event) fooEvent.getConstructor(int.class).newInstance(7);
        cancelled.setCanceled(true);
        check((Boolean) postFromLocal.invoke(null, cancelled), "已取消事件应返回 true");
        check(hits[0] == 2, "取消后默认监听器不应收到事件，实际 " + hits[0]);

        check(!(Boolean) postOtherBus.invoke(null, bus), "其它总线 post 返回 false");
        check(hits[0] == 4, "其它总线仍应走 EventBus.post，实际 " + hits[0]);

        final IEventBus other = BusBuilder.builder().build();
        final int[] otherHits = {0};
        register(other, fooEvent, hops(otherHits));
        final Event otherEvent = (Event) fooEvent.getConstructor(int.class).newInstance(3);
        postOtherBus.invoke(null, other);
        check(otherHits[0] == 1, "外来总线自己的监听器应被调用，实际 " + otherHits[0]);
        check(hits[0] == 4, "外来总线不应触发 Forge 总线的监听器，实际 " + hits[0]);

        final Method postWrapped = target.getMethod("postWrappedDirect", Event.class,
                net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher.class);
        final int[] wrapCount = {0};
        final int[] nativeWrapCount = {0};
        final Event wrappedNative = (Event) fooEvent.getConstructor(int.class).newInstance(40);
        bus.post(wrappedNative, (listener, event) -> nativeWrapCount[0]++);
        final Event wrappedEvent = (Event) fooEvent.getConstructor(int.class).newInstance(4);
        check(!(Boolean) postWrapped.invoke(null, wrappedEvent,
                        (net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher) (listener, event) -> wrapCount[0]++),
                "wrapper 路径应返回 false");
        check(wrapCount[0] == nativeWrapCount[0],
                "wrapper 调用次数应与原生一致（含优先级标记），原生 " + nativeWrapCount[0] + " 实际 " + wrapCount[0]);
        check(hits[0] == 4, "wrapper 自己不调 listener 时监听器不应被调用，实际 " + hits[0]);
        final Event wrappedEvent2 = (Event) fooEvent.getConstructor(int.class).newInstance(5);
        postWrapped.invoke(null, wrappedEvent2,
                (net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher) (listener, event) -> listener.invoke(event));
        check(hits[0] == 6, "透传 wrapper 应正常调用监听器，实际 " + hits[0]);

        final int[] foreignReg = {0};
        final Method addOnBus = target.getMethod("addOnBus", IEventBus.class, java.util.function.Consumer.class);
        final java.util.function.Consumer<Event> foreignConsumer = e -> foreignReg[0]++;
        addOnBus.invoke(null, other, foreignConsumer);
        final int beforeForeign = hits[0];
        postFromLocal.invoke(null, (Event) fooEvent.getConstructor(int.class).newInstance(6));
        check(hits[0] == beforeForeign + 2, "外来总线的注册不应影响 Forge 总线，实际 " + (hits[0] - beforeForeign));

        final IEventBus late = BusBuilder.builder().startShutdown().build();
        final FastDispatch.Holder lateHolder = FastDispatch.holder();
        final int beforeLate = hits[0];
        final Event lateProbe = (Event) fooEvent.getConstructor(int.class).newInstance(9);
        check(!FastDispatch.post(late, lateProbe, lateHolder), "shutdown 总线应返回 false");
        late.start();
        register(late, fooEvent, hops(hits));
        final Event lateEvent = (Event) fooEvent.getConstructor(int.class).newInstance(10);
        FastDispatch.post(late, lateEvent, lateHolder);
        check(hits[0] == beforeLate + 1, "start() 之后应能正常解析并分发，实际 " + (hits[0] - beforeLate));

        final int[] dynHits = {0};
        register(bus, WrapEvent.class, hops(dynHits));
        register(bus, BarEvent2.class, hops(dynHits));
        final FastDispatch.Holder dynHolder = FastDispatch.holder();
        FastDispatch.post(bus, new WrapEvent(), dynHolder);
        FastDispatch.post(bus, new BarEvent2(), dynHolder);
        check(dynHits[0] == 2, "同一个 Holder 上先后分发两个事件类都应各命中一次，实际 " + dynHits[0]);

        final ScreenTickSubscriber screenTick = new ScreenTickSubscriber();
        bus.register(screenTick);
        final FastDispatch.Holder sharedHolder = FastDispatch.holder();
        check(FastDispatch.hasListeners(bus, ScreenTickEvent.class, sharedHolder),
                "guard 应报告 ScreenTickEvent 有监听器");
        boolean crossTyped = true;
        String crossError = "";
        try {
            FastDispatch.postVoid(bus, new EngineUpdateEvent(), sharedHolder);
        } catch (final Throwable throwable) {
            crossTyped = false;
            final Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
            crossError = cause.toString();
        }
        check(crossTyped, "Holder 绑定的是另一个事件类时不应抛错，实际 " + crossError);
        check(screenTick.hits == 0,
                "Holder 绑定的是另一个事件类时不应调用原监听器，实际 " + screenTick.hits);
        FastDispatch.postVoid(bus, new ScreenTickEvent(), sharedHolder);
        check(screenTick.hits == 1, "同一个 Holder 重新绑回原事件类后应正常分发，实际 " + screenTick.hits);
        check(FastDispatch.hasListeners(bus, WrapEvent.class, null),
                "Holder 为 null 时 guard 应保守返回 true");
        boolean nullSafe = true;
        String nullError = "";
        try {
            FastDispatch.postVoid(bus, new WrapEvent(), null);
        } catch (final Throwable throwable) {
            nullSafe = false;
            nullError = throwable.toString();
        }
        check(nullSafe, "Holder 为 null 时应退回原生 post 而不是抛错，实际 " + nullError);
        bus.unregister(screenTick);
        FastDispatch.invalidate();

        final Method postUnlistened = target.getMethod("postUnlistened");
        postUnlistened.invoke(null);
        test.Counters.constructions = 0;
        postUnlistened.invoke(null);
        postUnlistened.invoke(null);
        check(test.Counters.constructions == 0,
                "没有监听器时不应构造事件，实际构造 " + test.Counters.constructions);
        @SuppressWarnings("unchecked")
        final Class<? extends Event> unlistened =
                (Class<? extends Event>) Class.forName("test.TestTarget$UnlistenedEvent", true,
                        target.getClassLoader());
        register(bus, unlistened, e -> { });
        FastDispatch.invalidate();
        test.Counters.constructions = 0;
        postUnlistened.invoke(null);
        check(test.Counters.constructions == 1, "有监听器时应正常构造，实际 " + test.Counters.constructions);

        final int[] manyHits = {0};
        for (int i = 0; i < 1_000; i++) {
            FastDispatch.addListener(bus, EventPriority.NORMAL, false, ManyEvent.class,
                    (ManyEvent e) -> manyHits[0]++);
        }
        FastDispatch.postVoid(bus, new ManyEvent(), FastDispatch.holder());
        check(manyHits[0] == 1_000, "1000 个监听器的分发器应能生成并全部调用，实际 " + manyHits[0]);

        final Method addBar = target.getMethod("addBarListener", java.util.function.Consumer.class);
        final Method removeListener = target.getMethod("removeListener", Object.class);
        final Method postBar = target.getMethod("postBar");
        final int[] barHits = {0};
        final java.util.function.Consumer<Object> barConsumer = e -> barHits[0]++;
        addBar.invoke(null, barConsumer);
        postBar.invoke(null);
        check(barHits[0] == 1, "改写后的 addListener 注册的监听器应被调用，实际 " + barHits[0]);
        final int cachedBefore = FastDispatch.debugDispatchers();
        removeListener.invoke(null, barConsumer);
        postBar.invoke(null);
        check(barHits[0] == 1, "unregister 之后不应再收到，实际 " + barHits[0]);
        check(FastDispatch.debugDispatchers() >= cachedBefore - 1,
                "注销一个事件类不应清空全部 dispatcher，注销前 " + cachedBefore + " 注销后 "
                        + FastDispatch.debugDispatchers());

        System.out.println();
        final io.github.nutant.fastevents.FastEventsLocator locator =
                new io.github.nutant.fastevents.FastEventsLocator();
        check("fastevents".equals(locator.name()),
                "locator 名字应为 modid，实际 " + locator.name());
        check(locator.scanMods().isEmpty(), "locator 不应提供任何 mod 文件");
        boolean injectThrew = false;
        try {
            io.github.nutant.fastevents.FastEventsLocator.inject();
        } catch (final Throwable throwable) {
            injectThrew = true;
        }
        check(!injectThrew, "没有 launcher 时注入应优雅失败而不是抛异常");

        bus.register(new test.GenericSubscriber());
        test.GenericSubscriber.stringHits = 0;
        test.GenericSubscriber.integerHits = 0;
        final Method postGeneric = target.getMethod("postGeneric", Event.class);
        final Class<?> genericEvent = Class.forName("test.GenericTestEvent", true, target.getClassLoader());
        postGeneric.invoke(null, genericEvent.getConstructor(Class.class).newInstance(String.class));
        postGeneric.invoke(null, genericEvent.getConstructor(Class.class).newInstance(Integer.class));
        check(test.GenericSubscriber.stringHits == 1,
                "String 泛型监听器应命中 1 次，实际 " + test.GenericSubscriber.stringHits);
        check(test.GenericSubscriber.integerHits == 1,
                "Integer 泛型监听器应命中 1 次，实际 " + test.GenericSubscriber.integerHits);
        check(FastDispatch.debugGenericSteps() >= 2,
                "应把 2 个泛型过滤监听器内联进 dispatcher，实际 " + FastDispatch.debugGenericSteps());
        postGeneric.invoke(null, genericEvent.getConstructor(Class.class).newInstance(String.class));
        check(test.GenericSubscriber.stringHits == 2 && test.GenericSubscriber.integerHits == 1,
                "复用缓存的分发器时过滤仍应正确，实际 " + test.GenericSubscriber.stringHits + "/"
                        + test.GenericSubscriber.integerHits);

        test.GenericStringConsumer.hits = 0;
        final Method addGeneric = target.getMethod("addGenericListenerC", java.util.function.Consumer.class);
        addGeneric.invoke(null, new test.GenericStringConsumer());
        postGeneric.invoke(null, genericEvent.getConstructor(Class.class).newInstance(String.class));
        check(test.GenericStringConsumer.hits == 1,
                "addGenericListener(String) 应命中 String 事件，实际 " + test.GenericStringConsumer.hits);
        postGeneric.invoke(null, genericEvent.getConstructor(Class.class).newInstance(Integer.class));
        check(test.GenericStringConsumer.hits == 1,
                "addGenericListener(String) 不应命中 Integer 事件，实际 " + test.GenericStringConsumer.hits);

        final ClassNode ifaceNode = parse(readClass("test/InterfaceTarget.class"));
        new io.github.nutant.fastevents.PostCallRewriter().rewrite(ifaceNode);
        int ifaceFields = 0;
        boolean ifacePublic = true;
        for (final org.objectweb.asm.tree.FieldNode field : ifaceNode.fields) {
            if (field.name.startsWith("fastevents$h")) {
                ifaceFields++;
                ifacePublic &= (field.access & (org.objectweb.asm.Opcodes.ACC_PUBLIC
                        | org.objectweb.asm.Opcodes.ACC_PRIVATE)) == org.objectweb.asm.Opcodes.ACC_PUBLIC;
            }
        }
        check(ifaceFields == 1, "接口应注入 1 个缓存字段，实际 " + ifaceFields);
        check(ifacePublic, "接口里的注入字段必须是 public static final");
        boolean ifaceDefined = true;
        String ifaceError = "";
        try {
            define("test.InterfaceTarget", write(ifaceNode));
        } catch (final Throwable throwable) {
            ifaceDefined = false;
            final Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
            ifaceError = cause.toString();
        }
        check(ifaceDefined, "接口目标改写后应能通过 JVM 校验，实际 " + ifaceError);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    public static class EmptyEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class BarEvent2 extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class ManyEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class FastEightEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class WrapEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class EightEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public abstract static class LevelLikeEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return false; }
    }

    public static class EngineUpdateEvent extends LevelLikeEvent {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(LevelLikeEvent.LL);

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
    }

    public static class ScreenTickEvent extends Event {
        public static final net.minecraftforge.eventbus.ListenerList LL =
                new net.minecraftforge.eventbus.ListenerList(EventListenerHelper.getListenerList(Event.class));

        @Override public net.minecraftforge.eventbus.ListenerList getListenerList() { return LL; }
        @Override public boolean isCancelable() { return true; }
    }

    public static final class ScreenTickSubscriber {
        public int hits;

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void onScreenTick(final ScreenTickEvent event) {
            hits++;
        }
    }

    private static Consumer<Event> hops(final int[] hits) {
        return e -> hits[0]++;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(final IEventBus bus, final Class<?> eventClass, final Consumer<Event> consumer) {
        bus.addListener(EventPriority.NORMAL, false, (Class) eventClass, (Consumer) consumer);
    }

    private static byte[] readClass(final String path) throws Exception {
        try (InputStream in = Harness.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing " + path);
            }
            return in.readAllBytes();
        }
    }

    private static ClassNode parse(final byte[] bytes) {
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(final ClassNode node) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                try {
                    final ClassLoader loader = Harness.class.getClassLoader();
                    Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                    final Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                    if (c1.isAssignableFrom(c2)) return type1;
                    if (c2.isAssignableFrom(c1)) return type2;
                    if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                    do { c1 = c1.getSuperclass(); } while (!c1.isAssignableFrom(c2));
                    return c1.getName().replace('.', '/');
                } catch (final Throwable t) {
                    return "java/lang/Object";
                }
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Class<?> define(final String name, final byte[] bytes) throws Exception {
        return new ClassLoader(Harness.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(final String className, final boolean resolve) throws ClassNotFoundException {
                if (className.equals(name)) {
                    synchronized (this) {
                        Class<?> loaded = findLoadedClass(className);
                        if (loaded == null) {
                            loaded = defineClass(className, bytes, 0, bytes.length);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(className, resolve);
            }
        }.loadClass(name);
    }

    private static int count(final ClassNode node, final String methodName, final String owner) {
        int total = 0;
        for (final MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && methodName.equals(call.name) && owner.equals(call.owner)) {
                    total++;
                }
            }
        }
        return total;
    }

    private static int holderFields(final ClassNode node) {
        int total = 0;
        for (final org.objectweb.asm.tree.FieldNode field : node.fields) {
            if (field.name.startsWith("fastevents$h")) {
                total++;
            }
        }
        return total;
    }

    private static int maxStack(final ClassNode node, final String methodName) {
        for (final MethodNode method : node.methods) {
            if (methodName.equals(method.name)) {
                return method.maxStack;
            }
        }
        return -1;
    }

    private static void dump(final ClassNode node, final String methodName) {
        for (final MethodNode method : node.methods) {
            if (!methodName.equals(method.name)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                final int op = insn.getOpcode();
                if (op < 0) {
                    continue;
                }
                final StringBuilder line = new StringBuilder("  ").append(OPCODES[op]);
                if (insn instanceof MethodInsnNode call) {
                    line.append(' ').append(call.owner).append('.').append(call.name).append(call.desc);
                } else if (insn instanceof FieldInsnNode field) {
                    line.append(' ').append(field.owner).append('.').append(field.name);
                } else if (insn instanceof VarInsnNode var) {
                    line.append(' ').append(var.var);
                }
                System.out.println(line);
            }
        }
    }

    private static final String[] OPCODES = new String[200];

    static {
        for (int i = 0; i < OPCODES.length; i++) {
            OPCODES[i] = "op" + i;
        }
        OPCODES[Opcodes.GETSTATIC] = "GETSTATIC";
        OPCODES[Opcodes.INVOKESTATIC] = "INVOKESTATIC";
        OPCODES[Opcodes.INVOKEINTERFACE] = "INVOKEINTERFACE";
        OPCODES[Opcodes.INVOKEVIRTUAL] = "INVOKEVIRTUAL";
        OPCODES[Opcodes.INVOKESPECIAL] = "INVOKESPECIAL";
        OPCODES[Opcodes.NEW] = "NEW";
        OPCODES[Opcodes.DUP] = "DUP";
        OPCODES[Opcodes.ALOAD] = "ALOAD";
        OPCODES[Opcodes.IRETURN] = "IRETURN";
        OPCODES[Opcodes.RETURN] = "RETURN";
        OPCODES[Opcodes.IFEQ] = "IFEQ";
        OPCODES[Opcodes.POP] = "POP";
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            failures++;
            System.out.println("  [FAIL] " + message);
        } else {
            System.out.println("  [ok]   " + message);
        }
    }
}
