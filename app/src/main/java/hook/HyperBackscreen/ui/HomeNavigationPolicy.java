package hook.HyperBackscreen.ui;

public final class HomeNavigationPolicy {
    private static final Tab[] MAIN_TABS = {Tab.HOME, Tab.ABOUT};

    private HomeNavigationPolicy() {
    }

    public enum Tab {
        HOME,
        ABOUT
    }

    public enum TopAction {
        RESTART,
        SETTINGS
    }

    public static Tab[] mainTabs() {
        return MAIN_TABS.clone();
    }

    public static TopAction topActionFor(Tab tab) {
        return tab == Tab.ABOUT ? TopAction.SETTINGS : TopAction.RESTART;
    }

    public static int floatingBottomBarItemMinWidthDp() {
        return 96;
    }

    public static int mainContentBottomSpacerDp() {
        return 120;
    }
}
