package com.ciot.writer;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * 系统 UI 避让工具：开启边到边显示后，让标题栏自动增加状态栏高度、底部面板自动增加导航栏高度的内边距，
 * 避免内容被系统任务栏或手势条遮挡。项目未引入 AndroidX，因此直接使用系统 API 实现。
 */
final class SystemInsets {
    private SystemInsets() { }

    /** 在 setContentView 之前调用：让内容延伸到状态栏与导航栏区域，并把状态栏图标改为浅色以适配深色标题栏。 */
    @SuppressWarnings("deprecation")
    static void enableEdgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        try {
            window.setStatusBarColor(activity.getColor(R.color.colorDark));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                // 必须经由 DecorView 获取控制器：Window#getInsetsController 在 Android 15 上直接读取内部 DecorView，
                // 而此时若尚未调用 setContentView，DecorView 仍为 null 会抛出空指针。getDecorView 会按需创建它。
                WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
                if (controller != null) controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } catch (Exception ignored) {
            // 纯外观设置，失败时退回系统默认样式即可，不应阻断界面启动。
        }
    }

    /** 让顶部标题栏按状态栏高度增加上内边距。 */
    static void avoidStatusBar(View header) {
        final int left = header.getPaddingLeft(), top = header.getPaddingTop();
        final int right = header.getPaddingRight(), bottom = header.getPaddingBottom();
        header.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(left, top + topInset(insets), right, bottom);
            return insets;
        });
        header.requestApplyInsets();
    }

    /**
     * 让底部区域按“导航栏或软键盘”中更高的那个增加下内边距：
     * 无键盘时仅翻开导航栏，弹出键盘时整体抬升到键盘上方，避免输入框被遮挡且无法滚动查看。
     */
    static void avoidBottomInset(View footer) {
        final int left = footer.getPaddingLeft(), top = footer.getPaddingTop();
        final int right = footer.getPaddingRight(), bottom = footer.getPaddingBottom();
        footer.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(left, top, right, bottom + bottomInset(insets));
            return insets;
        });
        footer.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private static int topInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return insets.getInsets(WindowInsets.Type.systemBars()).top;
        return insets.getSystemWindowInsetTop();
    }

    /** 返回导航栏与软键盘中更高的底部内边距（union 取最大），避免两者叠加导致多余留白。 */
    @SuppressWarnings("deprecation")
    private static int bottomInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom;
        return insets.getSystemWindowInsetBottom();
    }
}
