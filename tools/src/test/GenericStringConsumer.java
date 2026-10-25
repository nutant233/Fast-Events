package test;

import java.util.function.Consumer;

public final class GenericStringConsumer implements Consumer<GenericTestEvent<String>> {

    public static int hits;

    @Override
    public void accept(final GenericTestEvent<String> event) {
        hits++;
    }
}
