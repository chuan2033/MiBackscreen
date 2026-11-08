package hook.HyperBackscreen.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 背屏小部件列表：用户选中的 AppWidget 提供方，按 {@code ComponentName#flattenToString()} 一行一个保存。
 *
 * <p>背屏中心把每一项当作一页「原生小部件」（SubScreenWidget type 1）插进背屏页面列表；
 * 这里只做纯字符串处理，模块 App 与背屏中心两边共用，便于单元测试。
 */
public final class RearWidgetList {
    /** 背屏页面最多追加这么多个小部件页，避免把原有壁纸页挤得太远。 */
    public static final int MAX_WIDGETS = 6;

    /**
     * 模块生成的小部件页 id 区间：{@code BASE_ID + 序号}。背屏中心会把页面列表存回 widget.json，
     * 下次载入时靠这个区间（以及 extra 里的标记）认出旧条目，先删再按当前选择重新追加。
     */
    public static final int BASE_ID = 0x52_57_00_00;
    public static final int ID_SPAN = 0x100;

    /** 写进 SubScreenWidget extra 里的标记键，存回 widget.json 后仍在。 */
    public static final String MARKER_KEY = "mibackscreen_rear_widget";

    private static final Pattern SPLIT = Pattern.compile("[\\s,;]+");
    private static final Pattern COMPONENT =
            Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+/\\.?[A-Za-z0-9_$]+(\\.[A-Za-z0-9_$]+)*");

    private RearWidgetList() {
    }

    @NonNull
    public static List<String> parse(@Nullable String raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return new ArrayList<>();
        for (String part : SPLIT.split(raw)) {
            String value = part == null ? "" : part.trim();
            if (isValid(value)) result.add(value);
            if (result.size() >= MAX_WIDGETS) break;
        }
        return new ArrayList<>(result);
    }

    @NonNull
    public static String encode(@Nullable Collection<String> components) {
        if (components == null || components.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String component : components) {
            String value = component == null ? "" : component.trim();
            if (isValid(value)) unique.add(value);
            if (unique.size() >= MAX_WIDGETS) break;
        }
        for (String value : unique) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(value);
        }
        return builder.toString();
    }

    public static boolean isValid(@Nullable String component) {
        return component != null && COMPONENT.matcher(component).matches();
    }

    public static int idFor(int index) {
        return BASE_ID + index;
    }

    public static boolean isModuleId(int id) {
        return id >= BASE_ID && id < BASE_ID + ID_SPAN;
    }
}
