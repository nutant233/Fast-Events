package test;

import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 带泛型参数的 @SubscribeEvent：会生成带 filter 的 ASMEventHandler。 */
public final class GenericSubscriber {

    public static int stringHits;
    public static int integerHits;

    @SubscribeEvent
    public void onString(final GenericTestEvent<String> event) {
        stringHits++;
    }

    @SubscribeEvent
    public void onInteger(final GenericTestEvent<Integer> event) {
        integerHits++;
    }
}
