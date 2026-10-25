package harness;

import io.github.nutant.fastevents.FastDispatch;
import io.github.nutant.fastevents.PostCallRewriter;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 离线验证：改写 -> 反汇编核对 -> 真实分发执行（eventbus 8.x 真包，不需要 MC）。 */
public final class Harness {

    private static int failures = 0;

    public static void main(final String[] args) throws Exception {
        final byte[] original = read("test/TestTarget.class");
        final ClassNode before = parse(original);
        final PostCallRewriter rewriter = new PostCallRewriter();
        final int changed = rewriter.rewrite(before);
        check(changed == 7, "应改写 4 个 post + 3 个注册钩子，实际 " + changed);
        check(rewriter.postSites == 4, "post 调用点应为 4，实际 " + rewriter.postSites);
        check(rewriter.hookSites == 3, "注册/注销钩子应为 3，实际 " + rewriter.hookSites);
        final int expectedGuards = PostCallRewriter.guardEnabled ? 2 : 0;
        check(rewriter.guardSites == expectedGuards, "guard 调用点数应为 " + expectedGuards + "，实际 " + rewriter.guardSites);
        check(holderFields(before) == 4, "应注入 4 个 Holder 字段，实际 " + holderFields(before));
        check(count(before, "holder", PostCallRewriter.HELPER) == 4, "clinit 应初始化 4 个 Holder");

        final byte[] transformed = write(before);
        final ClassNode after = parse(transformed);
        check(count(after, "post", "net/neoforged/bus/api/IEventBus") == 1,
                "总线未知的 post 应保持原生 1 次，实际 " + count(after, "post", "net/neoforged/bus/api/IEventBus"));
        check(count(after, "post", PostCallRewriter.HELPER) == 4, "NeoForge 总线上的 post 应为 4，实际 "
                + count(after, "post", PostCallRewriter.HELPER));
        check(count(after, "addListener", PostCallRewriter.HELPER) == 1, "addListener 应被改写 1 次");
        check(count(after, "register", PostCallRewriter.HELPER) == 1, "register 应被改写 1 次");
        check(count(after, "unregister", PostCallRewriter.HELPER) == 1, "unregister 应被改写 1 次");

        final ClassNode twice = parse(transformed);
        check(new PostCallRewriter().rewrite(twice) == 0, "第二次改写不应再改动");
        check(count(twice, "holder", PostCallRewriter.HELPER) == 8, "第二次改写应重放 clinit 初始化，实际 "
                + count(twice, "holder", PostCallRewriter.HELPER));

        final Class<?> target = define("test.TestTarget", transformed);
        final Class<?> fooEvent = Class.forName("test.TestTarget$FooEvent", true, target.getClassLoader());
        final IEventBus bus = NeoForge.EVENT_BUS;
        final Method postNew = target.getMethod("postNew");
        final Method postDiscarded = target.getMethod("postDiscarded");
        final Method postFromLocal = target.getMethod("postFromLocal", Event.class);
        final Method postOtherBus = target.getMethod("postOtherBus", IEventBus.class);

        final int[] hits = {0};
        bus.addListener(EventPriority.NORMAL, false, (Class<Event>) fooEvent, e -> hits[0]++);
        final Object returned = postNew.invoke(null);
        check(returned instanceof Event, "post 应返回事件本身");
        check(hits[0] == 1, "第一次 post 应命中 1 次，实际 " + hits[0]);
        postDiscarded.invoke(null);
        check(hits[0] == 2, "结果被丢弃的调用点也应分发，实际 " + hits[0]);

        final Event dynamic = (Event) fooEvent.getConstructor(int.class).newInstance(9);
        check(postFromLocal.invoke(null, dynamic) == dynamic, "动态调用点应返回同一个事件实例");
        check(hits[0] == 3, "动态调用点应正常分发，实际 " + hits[0]);

        final IEventBus other = BusBuilder.builder().build();
        final int[] otherHits = {0};
        other.addListener(EventPriority.NORMAL, false, (Class<Event>) fooEvent, e -> otherHits[0]++);
        postOtherBus.invoke(null, other);
        check(otherHits[0] == 1, "外来总线自己的监听器应被调用，实际 " + otherHits[0]);
        check(hits[0] == 3, "外来总线不应触发 NeoForge 总线的监听器，实际 " + hits[0]);

        final BarListener bar = new BarListener();
        final Method addBar = target.getMethod("addBarListener", Consumer.class);
        addBar.invoke(null, (Consumer<Event>) e -> bar.consumerHits++);
        final Class<?> barEvent = Class.forName("test.TestTarget$BarEvent", true, target.getClassLoader());
        bus.post((Event) barEvent.getConstructor().newInstance());
        check(bar.consumerHits == 1, "改写后的 addListener 注册的监听器应被调用，实际 " + bar.consumerHits);

        final OrderSubscriber order = new OrderSubscriber();
        bus.register(order);
        FastDispatch.invalidate();
        final Class<?> orderEvent = Class.forName("harness.Harness$OrderEvent", true, Harness.class.getClassLoader());
        FastDispatch.post(bus, (Event) orderEvent.getConstructor().newInstance(), FastDispatch.holder());
        check(order.order.equals(List.of("highest", "normal", "lowest")),
                "优先级顺序应为 HIGHEST->NORMAL->LOWEST，实际 " + order.order);

        final CancelSubscriber cancel = new CancelSubscriber();
        bus.register(cancel);
        FastDispatch.invalidate();
        final Class<?> cancelEvent = Class.forName("harness.Harness$CancelEvent", true, Harness.class.getClassLoader());
        FastDispatch.post(bus, (Event) cancelEvent.getConstructor().newInstance(), FastDispatch.holder());
        check(cancel.canceller == 1, "取消者的监听器应被调用，实际 " + cancel.canceller);
        check(cancel.afterCancel == 0, "取消后 NORMAL 监听器不应被调用，实际 " + cancel.afterCancel);
        check(cancel.receiver == 1, "receiveCanceled=true 的监听器应收到取消事件，实际 " + cancel.receiver);

        final int[] sharedHits = {0};
        bus.addListener(EventPriority.NORMAL, false, MixedFirst.class, e -> sharedHits[0]++);
        bus.addListener(EventPriority.NORMAL, false, MixedSecond.class, e -> sharedHits[0]++);
        bus.addListener(EventPriority.NORMAL, false, MixedThird.class, e -> sharedHits[0]++);
        final FastDispatch.Holder shared = FastDispatch.holder();
        FastDispatch.post(bus, new MixedFirst(), shared);
        FastDispatch.post(bus, new MixedSecond(), shared);
        FastDispatch.post(bus, new MixedThird(), shared);
        FastDispatch.post(bus, new MixedFirst(), shared);
        check(sharedHits[0] == 4, "同一个 Holder 上混用多个事件类都应命中，实际 " + sharedHits[0]);

        final CrossSubscriber cross = new CrossSubscriber();
        bus.register(cross);
        FastDispatch.invalidate();
        final FastDispatch.Holder crossHolder = FastDispatch.holder();
        check(FastDispatch.hasListeners(bus, OrderEvent.class, crossHolder), "guard 应报告 OrderEvent 有监听器");
        boolean crossSafe = true;
        String crossError = "";
        try {
            FastDispatch.post(bus, new MixedFirst(), crossHolder);
        } catch (final Throwable throwable) {
            crossSafe = false;
            crossError = throwable.toString();
        }
        check(crossSafe, "Holder 绑定的是另一个事件类时不应抛错，实际 " + crossError);
        check(cross.hits == 0, "Holder 绑定的是另一个事件类时不应调用原监听器，实际 " + cross.hits);

        check(FastDispatch.hasListeners(bus, MixedFirst.class, null), "Holder 为 null 时 guard 应保守返回 true");
        boolean nullSafe = true;
        try {
            FastDispatch.post(bus, new MixedFirst(), null);
        } catch (final Throwable throwable) {
            nullSafe = false;
        }
        check(nullSafe, "Holder 为 null 时应退回原生 post");
        FastDispatch.invalidate();

        final Method postUnlistened = target.getMethod("postUnlistened");
        test.Counters.constructions = 0;
        postUnlistened.invoke(null);
        postUnlistened.invoke(null);
        if (PostCallRewriter.guardEnabled) {
            check(test.Counters.constructions == 0, "没有监听器时不应构造事件，实际 " + test.Counters.constructions);
        } else {
            check(test.Counters.constructions == 2, "关闭 guard 后每次都应构造，实际 " + test.Counters.constructions);
        }
        @SuppressWarnings("unchecked")
        final Class<? extends Event> unlistened =
                (Class<? extends Event>) Class.forName("test.TestTarget$UnlistenedEvent", true,
                        target.getClassLoader());
        bus.addListener(EventPriority.NORMAL, false, unlistened, e -> {
        });
        FastDispatch.invalidate();
        test.Counters.constructions = 0;
        postUnlistened.invoke(null);
        check(test.Counters.constructions == 1, "有监听器时应正常构造，实际 " + test.Counters.constructions);

        final int[] lateHits = {0};
        FastDispatch.post(bus, new MixedSecond(), FastDispatch.holder());
        FastDispatch.addListener(bus, EventPriority.NORMAL, false, MixedSecond.class,
                (MixedSecond e) -> lateHits[0]++);
        FastDispatch.post(bus, new MixedSecond(), FastDispatch.holder());
        check(lateHits[0] == 1, "通过改写钩子注册后应立刻生效，实际 " + lateHits[0]);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    public static class OrderEvent extends Event {
    }

    public static class CancelEvent extends Event implements ICancellableEvent {
    }

    public static class MixedFirst extends Event {
    }

    public static class MixedSecond extends Event {
    }

    public static class MixedThird extends Event {
    }

    public static final class OrderSubscriber {
        public final List<String> order = new ArrayList<>();

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void highest(final OrderEvent event) {
            order.add("highest");
        }

        @SubscribeEvent
        public void normal(final OrderEvent event) {
            order.add("normal");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void lowest(final OrderEvent event) {
            order.add("lowest");
        }
    }

    public static final class CancelSubscriber {
        public int canceller;
        public int afterCancel;
        public int receiver;

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void cancel(final CancelEvent event) {
            canceller++;
            event.setCanceled(true);
        }

        @SubscribeEvent
        public void normal(final CancelEvent event) {
            afterCancel++;
        }

        @SubscribeEvent(receiveCanceled = true)
        public void receive(final CancelEvent event) {
            receiver++;
        }
    }

    public static final class CrossSubscriber {
        public int hits;

        @SubscribeEvent
        public void on(final OrderEvent event) {
            hits++;
        }
    }

    public static final class BarListener {
        public int consumerHits;
    }

    private static byte[] read(final String path) throws Exception {
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
                    Class<?> first = Class.forName(type1.replace('/', '.'), false, loader);
                    final Class<?> second = Class.forName(type2.replace('/', '.'), false, loader);
                    if (first.isAssignableFrom(second)) return type1;
                    if (second.isAssignableFrom(first)) return type2;
                    if (first.isInterface() || second.isInterface()) return "java/lang/Object";
                    do {
                        first = first.getSuperclass();
                    } while (!first.isAssignableFrom(second));
                    return first.getName().replace('.', '/');
                } catch (final Throwable throwable) {
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
            if (field.name.startsWith(PostCallRewriter.FIELD_PREFIX)) {
                total++;
            }
        }
        return total;
    }

    private static void check(final boolean condition, final String message) {
        if (condition) {
            System.out.println("  [ok]   " + message);
        } else {
            failures++;
            System.out.println("  [FAIL] " + message);
        }
    }
}
