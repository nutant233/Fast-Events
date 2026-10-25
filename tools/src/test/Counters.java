package test;

/** 独立类，避免被测试用的子 ClassLoader 分裂出两份静态计数器。 */
public final class Counters {
    public static int constructions;

    private Counters() {}
}