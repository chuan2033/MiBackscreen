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

    ArrayList<Object> fullCopy() {
        ArrayList<Object> items = new ArrayList<>();
        for (int i = 0; i < super.size(); i++) {
            items.add(super.get(i));
        }
        return items;
    }

    @Override public int size() { return Math.min(super.size(), cappedSize); }
}
