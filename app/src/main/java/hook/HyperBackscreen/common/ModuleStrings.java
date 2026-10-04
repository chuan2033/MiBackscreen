package hook.HyperBackscreen.common;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.Toast;
import android.util.Log;
import java.util.Locale;

/** Resolve strings against the installed module, using the host's current language. */
public final class ModuleStrings {
    private ModuleStrings() {}

    public static String fallback(Locale locale, String chinese, String english) {
        return "zh".equals(locale.getLanguage()) ? chinese : english;
    }

    public static String get(Context host, String name, String chinese, String english) {
        Locale locale = Locale.getDefault();
        try {
            if (host != null) {
                Configuration configuration = host.getResources().getConfiguration();
                if (!configuration.getLocales().isEmpty()) locale = configuration.getLocales().get(0);
                Context module = host.createPackageContext(Constants.MODULE_PACKAGE, 0)
                        .createConfigurationContext(configuration);
                int id = module.getResources().getIdentifier(name, "string", Constants.MODULE_PACKAGE);
                if (id != 0) return module.getString(id);
            }
        } catch (Exception ignored) {
            // Host must still show useful text when the module APK cannot be read.
        }
        return fallback(locale, chinese, english);
    }

    public static void showOpenFailure(Context context) {
        try {
            Toast.makeText(context, get(context, "activity_open_failed",
                    "无法打开此页面，请确认相关应用已安装并启用后重试。",
                    "Could not open this page. Check that the required app is installed and enabled, then retry."),
                    Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            Log.w(Constants.LOG_TAG, "Unable to display activity launch failure", error);
        }
    }
}
