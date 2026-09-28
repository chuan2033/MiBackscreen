package hook.HyperBackscreen.hook;

import java.util.ArrayList;
import java.util.Collection;

/** Scoped to insertAppWidget's capacity check; unwrap before passing to the host save path. */
final class SizeCappedList extends ArrayList<Object> {
    private final int cappedSize;

    SizeCappedList(Collection<?> source, int cappedSize) {
        super(source);
        this.cappedSize = cappedSize;
    }

    @Override public int size() { return Math.min(super.size(), cappedSize); }
}
