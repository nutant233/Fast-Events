// Test probe for the 1.16.5 class loader fix: this class is compiled OUTSIDE the runtime classpath,
// so it can only be seen after its jar is appended to the system class loader (the parent of the game
// loader in modlauncher 8). Never referenced by the harness itself.
package probe;

public final class Probe {

    private Probe() {
    }

    public static String hello() {
        return "probe-ok";
    }
}
