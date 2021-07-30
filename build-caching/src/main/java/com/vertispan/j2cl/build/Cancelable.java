package com.vertispan.j2cl.build;

/**
 *
 */
@FunctionalInterface
public interface Cancelable {
    public static Cancelable of(Cancelable... cancelables) {
        return () -> {
            for (Cancelable cancelable : cancelables) {
                cancelable.cancel();
            }
        };
    }

    /**
     *
     */
    void cancel();
}
