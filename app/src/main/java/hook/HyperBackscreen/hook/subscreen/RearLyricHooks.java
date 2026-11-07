package hook.HyperBackscreen.hook.subscreen;

import static hook.HyperBackscreen.hook.util.HookReflection.findClass;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredConstructor;
import static hook.HyperBackscreen.hook.util.HookReflection.findDeclaredMethod;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.lang.reflect.Constructor;

import hook.HyperBackscreen.common.Constants;
import hook.HyperBackscreen.hook.ModuleMain;
import hook.HyperBackscreen.lyric.RearLyricHost;

/**
 * 背屏实时歌词在背屏中心里的 Hook 点，全部是 MAML 稳定名（{@code com.miui.maml.*} 未混淆）：
 *
 * <ul>
 *   <li>{@code MusicControlScreenElement.<init>(Element, ScreenElementRoot)} 之后：登记卡片。
 *       卡片 XML 里有 {@code name="album_artist_text"} 的 Text 才算背屏自带的音乐卡片，只有它的歌手行会被替换；</li>
 *   <li>{@code ScreenElement.show(boolean)}、{@code MusicControlScreenElement.pause()/resume()}、
 *       {@code onMusicStateChange(boolean)} 之后：重新判断要不要显示歌词；</li>
 *   <li>{@code updateAlbum(String, String, String)} 前后：记下原歌手名；歌词显示期间宿主刷新元数据后写回歌词；</li>
 *   <li>{@code finish()}：注销卡片。</li>
 * </ul>
 * 背屏桌面前后台与息屏状态由 {@link SubScreenCenterHooks} 已有的 Hook 转发给 {@link RearLyricHost}
 * （同一个方法不能挂两次）。开关关着时这些 Hook 只做登记，不写任何变量。
 */
public final class RearLyricHooks {
    private static final String MUSIC_CONTROL = "com.miui.maml.elements.MusicControlScreenElement";
    private static final String SCREEN_ELEMENT = "com.miui.maml.elements.ScreenElement";
    private static final String SCREEN_ELEMENT_ROOT = "com.miui.maml.ScreenElementRoot";
    /** 背屏自带音乐卡片里歌手行 Text 的名字（smart_assistant/music/manifest.xml）。 */
    private static final String STOCK_ARTIST_TEXT = "album_artist_text";

    private final ModuleMain module;

    public RearLyricHooks(@NonNull ModuleMain module) {
        this.module = module;
    }

    public void install(@NonNull ClassLoader classLoader) {
        try {
            installHooks(classLoader);
        } catch (Throwable error) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear lyric hooks failed", error);
        }
    }

    private void installHooks(@NonNull ClassLoader classLoader) {
        Class<?> music = findClass(MUSIC_CONTROL, classLoader);
        Class<?> root = findClass(SCREEN_ELEMENT_ROOT, classLoader);
        Class<?> screenElement = findClass(SCREEN_ELEMENT, classLoader);
        Constructor<?> constructor = music == null || root == null
                ? null
                : findDeclaredConstructor(music, Element.class, root);
        if (music == null || screenElement == null || constructor == null) {
            module.log(Log.WARN, Constants.LOG_TAG, "Rear lyric skipped: MusicControlScreenElement not found");
            return;
        }
        RearLyricHost.install(module);

        boolean created = module.hookConstructorIfPresent(constructor, MUSIC_CONTROL + "#<init>", chain -> {
            Object result = chain.proceed();
            Object dom = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
            RearLyricHost.onCardCreated(chain.getThisObject(), isStockCard(dom));
            return result;
        });
        if (!created) return;

        module.hookMethodIfPresent(findDeclaredMethod(screenElement, "show", boolean.class),
                SCREEN_ELEMENT + "#show", chain -> {
                    Object result = chain.proceed();
                    Object element = chain.getThisObject();
                    if (music.isInstance(element)) RearLyricHost.onCardChanged(element);
                    return result;
                });
        for (String name : new String[]{"pause", "resume"}) {
            module.hookMethodIfPresent(findDeclaredMethod(music, name), MUSIC_CONTROL + "#" + name, chain -> {
                Object result = chain.proceed();
                RearLyricHost.onCardChanged(chain.getThisObject());
                return result;
            });
        }
        module.hookMethodIfPresent(findDeclaredMethod(music, "onMusicStateChange", boolean.class),
                MUSIC_CONTROL + "#onMusicStateChange", chain -> {
                    Object result = chain.proceed();
                    RearLyricHost.onCardChanged(chain.getThisObject());
                    return result;
                });
        module.hookMethodIfPresent(
                findDeclaredMethod(music, "updateAlbum", String.class, String.class, String.class),
                MUSIC_CONTROL + "#updateAlbum", chain -> {
                    Object element = chain.getThisObject();
                    RearLyricHost.beforeUpdateAlbum(element, chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null);
                    Object result = chain.proceed();
                    RearLyricHost.afterUpdateAlbum(element);
                    return result;
                });
        module.hookMethodIfPresent(findDeclaredMethod(music, "finish"), MUSIC_CONTROL + "#finish", chain -> {
            RearLyricHost.onCardFinished(chain.getThisObject());
            return chain.proceed();
        });
        module.log(Log.INFO, Constants.LOG_TAG, "Rear lyric hooks installed");
    }

    /** 卡片 DOM 里能找到 {@code <Text name="album_artist_text">} 才是背屏自带的音乐卡片。 */
    private static boolean isStockCard(@Nullable Object dom) {
        if (!(dom instanceof Element element)) return false;
        try {
            NodeList texts = element.getElementsByTagName("Text");
            for (int i = 0; i < texts.getLength(); i++) {
                if (texts.item(i) instanceof Element text
                        && STOCK_ARTIST_TEXT.equals(text.getAttribute("name"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // DOM 实现异常时按「不是自带卡片」处理：只写歌词变量，不动歌手行。
        }
        return false;
    }
}
