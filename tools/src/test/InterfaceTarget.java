package test;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;

/** 接口里带默认方法 post 事件（Create 的 IWrenchable 就是这种形态）。 */
public interface InterfaceTarget {

    default boolean postFromDefault() {
        return MinecraftForge.EVENT_BUS.post(new InterfaceEvent());
    }

    class InterfaceEvent extends Event {
        public InterfaceEvent() {}
    }
}
