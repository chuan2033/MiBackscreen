package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把补充模板里的背屏壁纸条目合并进主题商店的预设分类列表。
 *
 * <p>补充模板是 18 Pro Max 的完整 normal 模板：宿主已有的条目只用来确定位置，
 * 宿主没有、并且资源可用的条目才会加入。新条目插在它在补充模板里前一个条目的后面，
 * 前面没有宿主已有的条目时放到分类最前；宿主缺少的整个分类按同样规则插入分类列表。
 * 这样合并结果的顺序与 18 Pro Max 一致。
 *
 * <p>只做计算，不改动传入的对象；调用方按 {@link Result} 把新的条目列表写回分类对象。
 */
public final class RearPresetMerger {

    /** 访问宿主分类与条目对象的方式。 */
    public interface Access<C, I> {
        @Nullable String categoryType(@NonNull C category);

        @Nullable List<I> items(@NonNull C category);

        /** 判断两个条目是否为同一壁纸的键，取不到时返回 null，该条目不参与合并。 */
        @Nullable String itemKey(@NonNull I item);

        /** 条目的资源在本机可用（例如资源文件存在）。 */
        boolean usable(@NonNull I item);
    }

    public static final class Result<C, I> {
        /** 合并后的分类顺序；没有变化时就是宿主原列表的副本。 */
        @NonNull public final List<C> categories;
        /** 需要写回的条目列表，键是分类对象本身（按引用区分）。 */
        @NonNull public final Map<C, List<I>> updatedItems;
        public final int addedItems;
        public final int addedCategories;

        Result(@NonNull List<C> categories, @NonNull Map<C, List<I>> updatedItems,
               int addedItems, int addedCategories) {
            this.categories = categories;
            this.updatedItems = updatedItems;
            this.addedItems = addedItems;
            this.addedCategories = addedCategories;
        }

        public boolean changed() {
            return addedItems > 0;
        }
    }

    private RearPresetMerger() {
    }

    @NonNull
    public static <C, I> Result<C, I> merge(
            @NonNull List<? extends C> host,
            @NonNull List<? extends C> supplement,
            @NonNull Access<C, I> access
    ) {
        List<C> categories = new ArrayList<>(host);
        Map<C, List<I>> updated = new IdentityHashMap<>();
        int addedItems = 0;
        int addedCategories = 0;

        for (int s = 0; s < supplement.size(); s++) {
            C source = supplement.get(s);
            String type = access.categoryType(source);
            if (type == null) continue;
            List<I> sourceItems = nonNull(access.items(source));
            C target = findCategory(categories, type, access);

            if (target == null) {
                List<I> fresh = new ArrayList<>();
                for (I item : sourceItems) {
                    String key = access.itemKey(item);
                    if (key != null && access.usable(item) && indexOfKey(fresh, key, access) < 0) {
                        fresh.add(item);
                    }
                }
                if (fresh.isEmpty()) continue;
                categories.add(categoryInsertIndex(categories, supplement, s, access), source);
                updated.put(source, fresh);
                addedItems += fresh.size();
                addedCategories++;
                continue;
            }

            List<I> current = updated.containsKey(target)
                    ? updated.get(target)
                    : new ArrayList<>(nonNull(access.items(target)));
            int added = 0;
            for (int i = 0; i < sourceItems.size(); i++) {
                I item = sourceItems.get(i);
                String key = access.itemKey(item);
                if (key == null || indexOfKey(current, key, access) >= 0 || !access.usable(item)) {
                    continue;
                }
                current.add(itemInsertIndex(current, sourceItems, i, access), item);
                added++;
            }
            if (added > 0) {
                updated.put(target, current);
                addedItems += added;
            }
        }
        return new Result<>(categories, updated, addedItems, addedCategories);
    }

    /** 插在补充模板里该条目之前、离它最近的宿主已有条目后面；都没有时放最前。 */
    private static <C, I> int itemInsertIndex(
            @NonNull List<I> current,
            @NonNull List<I> sourceItems,
            int sourceIndex,
            @NonNull Access<C, I> access
    ) {
        for (int j = sourceIndex - 1; j >= 0; j--) {
            String anchor = access.itemKey(sourceItems.get(j));
            if (anchor == null) continue;
            int at = indexOfKey(current, anchor, access);
            if (at >= 0) return at + 1;
        }
        return 0;
    }

    private static <C, I> int categoryInsertIndex(
            @NonNull List<C> categories,
            @NonNull List<? extends C> supplement,
            int sourceIndex,
            @NonNull Access<C, I> access
    ) {
        for (int j = sourceIndex - 1; j >= 0; j--) {
            String anchor = access.categoryType(supplement.get(j));
            if (anchor == null) continue;
            for (int k = 0; k < categories.size(); k++) {
                if (anchor.equals(access.categoryType(categories.get(k)))) return k + 1;
            }
        }
        return 0;
    }

    @Nullable
    private static <C, I> C findCategory(
            @NonNull List<C> categories,
            @NonNull String type,
            @NonNull Access<C, I> access
    ) {
        for (C category : categories) {
            if (type.equals(access.categoryType(category))) return category;
        }
        return null;
    }

    private static <C, I> int indexOfKey(
            @NonNull List<I> items,
            @NonNull String key,
            @NonNull Access<C, I> access
    ) {
        for (int i = 0; i < items.size(); i++) {
            if (key.equals(access.itemKey(items.get(i)))) return i;
        }
        return -1;
    }

    @NonNull
    private static <I> List<I> nonNull(@Nullable List<I> items) {
        return items == null ? Collections.emptyList() : items;
    }
}
