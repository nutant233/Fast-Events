package test;

import net.minecraftforge.eventbus.api.GenericEvent;

/** 用来验证"带泛型过滤的监听器"这条路（eventbus 6.0.x / 6.2.x 的 getGenericType 签名不同）。 */
public class GenericTestEvent<T> extends GenericEvent<T> {

    public GenericTestEvent() {
        super();
    }

    public GenericTestEvent(final Class<T> type) {
        super(type);
    }
}
