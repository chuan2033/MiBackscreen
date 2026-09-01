package hook.HyperBackscreen.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class HomeNavigationPolicyTest {
    @Test
    public void mainTabsOnlyExposeHomeAndAbout() {
        assertArrayEquals(
                new HomeNavigationPolicy.Tab[]{
                        HomeNavigationPolicy.Tab.HOME,
                        HomeNavigationPolicy.Tab.ABOUT
                },
                HomeNavigationPolicy.mainTabs());
    }

    @Test
    public void topActionIsRestartOnHomeAndSettingsOnAbout() {
        assertEquals(
                HomeNavigationPolicy.TopAction.RESTART,
                HomeNavigationPolicy.topActionFor(HomeNavigationPolicy.Tab.HOME));
        assertEquals(
                HomeNavigationPolicy.TopAction.SETTINGS,
                HomeNavigationPolicy.topActionFor(HomeNavigationPolicy.Tab.ABOUT));
    }

    @Test
    public void floatingBottomBarItemsStayWideEnoughForTwoTabs() throws Exception {
        assertTrue(layoutDp("floatingBottomBarItemMinWidthDp") >= 96);
    }

    @Test
    public void mainContentBottomSpacerKeepsLastRowsAboveFloatingBar() throws Exception {
        assertTrue(layoutDp("mainContentBottomSpacerDp") >= 112);
    }

    private static int layoutDp(String methodName) throws NoSuchMethodException,
            InvocationTargetException, IllegalAccessException {
        Method method = HomeNavigationPolicy.class.getMethod(methodName);
        return (Integer) method.invoke(null);
    }
}
