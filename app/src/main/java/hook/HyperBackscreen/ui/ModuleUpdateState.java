package hook.HyperBackscreen.ui;

public final class ModuleUpdateState {
    private final String loadedApkPath;
    private final long loadedVersionCode;

    public ModuleUpdateState(String loadedApkPath, long loadedVersionCode) {
        this.loadedApkPath = loadedApkPath;
        this.loadedVersionCode = loadedVersionCode;
    }

    public boolean requiresRestart(String installedApkPath, long installedVersionCode) {
        if (loadedVersionCode != installedVersionCode) return true;
        // Android gives a replacement APK a new install path, even for the same version code.
        return loadedApkPath != null && !loadedApkPath.isEmpty()
                && installedApkPath != null && !installedApkPath.isEmpty()
                && !loadedApkPath.equals(installedApkPath);
    }
}
