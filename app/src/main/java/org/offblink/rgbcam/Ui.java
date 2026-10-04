package org.offblink.rgbcam;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * 全自绘交互件：提示条 / 确认框 / 菜单，一个原生弹窗都不用（用户拍板）。
 *
 * <p>提示条挂在 decor 上（亮暗场景都可读的深墨胶囊）；确认框/菜单是透明窗
 * {@link Dialog} + 自己的布局，视觉口径和页面一致（白面、14dp 圆角、分隔线）。
 */
public final class Ui {

    private Ui() {
    }

    // ------------------------------------------------------------ 提示条

    public static void toast(Activity a, String msg) {
        if (a.isFinishing() || a.isDestroyed()) {
            return;
        }
        // 复用同一宿主：连发只留最新一条
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        View old = decor.findViewWithTag("prism-toast");
        if (old != null) {
            decor.removeView(old);
        }
        TextView pill = new TextView(a);
        pill.setTag("prism-toast");
        pill.setText(msg);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(13);
        pill.setBackground(ContextCompat.getDrawable(a, R.drawable.toast_pill));
        int h = dp(a, 44);
        // 垂直方向必须显式 BOTTOM：只给 CENTER_HORIZONTAL 时 FrameLayout 缺省贴顶
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, h,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int margin = dp(a, 28);
        int bottom = decor.getPaddingBottom() + dp(a, 96);
        lp.setMargins(margin, 0, margin, bottom);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(margin, 0, margin, 0);
        pill.setAlpha(0f);
        decor.addView(pill, lp);
        pill.animate().alpha(1f).setDuration(140).start();
        pill.postDelayed(() -> pill.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                    ViewGroup parent = (ViewGroup) pill.getParent();
                    if (parent != null) {
                        parent.removeView(pill);
                    }
                }).start(), 1700);
    }

    /** res 版提示条 */
    public static void toast(Activity a, int res) {
        toast(a, a.getString(res));
    }

    // ------------------------------------------------------------ 确认框

    /** 自绘确认框：标题 + 正文 + 取消/确定（确定用 {@code okColor}），点外面关掉 */
    public static Dialog confirm(Activity a, String title, String msg,
                                 String okLabel, int okColor, Runnable onOk) {
        Dialog d = baseDialog(a);
        d.setContentView(R.layout.dialog_confirm);
        ((TextView) d.findViewById(R.id.dc_title)).setText(title);
        ((TextView) d.findViewById(R.id.dc_msg)).setText(msg);
        TextView ok = d.findViewById(R.id.dc_ok);
        ok.setText(okLabel);
        ok.setTextColor(okColor);
        ok.setOnClickListener(v -> {
            d.dismiss();
            if (onOk != null) {
                onOk.run();
            }
        });
        TextView cancel = d.findViewById(R.id.dc_cancel);
        cancel.setOnClickListener(v -> d.dismiss());
        d.show();
        return d;
    }

    // ------------------------------------------------------------ 菜单

    /** 自绘竖排菜单（保存方式这类），点外面关掉；onPick 收 0 基下标 */
    public static Dialog menu(Activity a, String title, CharSequence[] items,
                              OnPick onPick) {
        Dialog d = baseDialog(a);
        d.setContentView(R.layout.dialog_menu);
        TextView tv = d.findViewById(R.id.dm_title);
        tv.setText(title);
        tv.setVisibility(title == null ? View.GONE : View.VISIBLE);
        LinearLayout box = d.findViewById(R.id.dm_items);
        int fg = ContextCompat.getColor(a, R.color.fg);
        int line = ContextCompat.getColor(a, R.color.line);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView row = new TextView(a);
            row.setText(items[i]);
            row.setTextColor(fg);
            row.setTextSize(15);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = dp(a, 22);
            row.setPadding(pad, 0, pad, 0);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 54)));
            row.setOnClickListener(v -> {
                d.dismiss();
                onPick.onPick(idx);
            });
            box.addView(row);
            if (i < items.length - 1) {
                View l = new View(a);
                l.setBackgroundColor(line);
                l.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
                l.setPadding(pad, 0, 0, 0);
                box.addView(l);
            }
        }
        d.show();
        return d;
    }

    public interface OnPick {
        void onPick(int which);
    }

    // ------------------------------------------------------------ 缩放切页

    /** 进缩放切页前拍下的旧页面快照，直接当新页窗口背景（不用透明窗——透明会渲染成黑底） */
    private static android.graphics.Bitmap pageSnap;

    /**
     * 在 startActivity 之前调用：把当前页整帧截下来（PixelCopy 从窗口 surface 拿，
     * 含取景 TextureView 和 WebView 这类 GPU 内容；decor.draw 对它们是瞎的 → 会截成
     * 透明 → 黑底）。截完回调 {@code then} 里再 startActivity。
     */
    public static void snapshot(Activity a, Runnable then) {
        View decor = a.getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            then.run();
            return;
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w,
                h, android.graphics.Bitmap.Config.ARGB_8888);
        // 保底打底色：PixelCopy 万一失败，露出的也是页面底色，绝不黑
        android.graphics.Canvas canvas = new android.graphics.Canvas(b);
        canvas.drawColor(ContextCompat.getColor(a, R.color.bg));
        try {
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            android.view.PixelCopy.request(a.getWindow(), b, copyResult -> {
                if (copyResult != android.view.PixelCopy.SUCCESS) {
                    // 兜底：软件画一遍（取景/面板可能缺，但底色保底不黑）
                    decor.draw(new android.graphics.Canvas(b));
                }
                pageSnap = b;
                main.post(then);
            }, main);
        } catch (RuntimeException e) {
            decor.draw(new android.graphics.Canvas(b));
            pageSnap = b;
            then.run();
        }
    }

    /** view 在屏幕上的矩形 [x, y, w, h]（缩放切页的源/目标位置） */
    public static int[] rectOnScreen(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new int[]{loc[0], loc[1], v.getWidth(), v.getHeight()};
    }

    /**
     * 缩放进页：新页从 {@code rect}（屏幕坐标）放大到全屏。
     * 窗口背景 = 旧页面快照（{@link #snapshot} 先拍），源位置之外看到的就是旧页本身。
     * 调用方要先 {@code overridePendingTransition(0,0)} 关掉默认平移。
     */
    public static void zoomIn(Activity a, View root, int[] rect) {
        if (rect == null) {
            return;
        }
        if (pageSnap != null && !pageSnap.isRecycled()) {
            a.getWindow().setBackgroundDrawable(new android.graphics.drawable.BitmapDrawable(
                    a.getResources(), pageSnap));
        } else {
            // 没截到（极端时序）：退回页面底色，绝不黑底
            a.getWindow().setBackgroundDrawableResource(R.color.bg);
        }
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        root.setPivotX(0f);
        root.setPivotY(0f);
        root.setScaleX(rect[2] / (float) dm.widthPixels);
        root.setScaleY(rect[3] / (float) dm.heightPixels);
        root.setTranslationX(rect[0]);
        root.setTranslationY(rect[1]);
        root.post(() -> root.animate()
                .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(280)
                .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                        a, android.R.interpolator.fast_out_slow_in))
                .start());
    }

    /**
     * 缩放返回：整页缩回 {@code rect} 再 finish（进页的反动作）。
     * 窗口背景在 zoomIn 时已是旧页快照，缩小时露出来的就是旧页；finish 后关系统动画。
     */
    public static void shrinkFinish(Activity a, View root, int[] rect) {
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        root.animate()
                .scaleX(rect[2] / (float) dm.widthPixels)
                .scaleY(rect[3] / (float) dm.heightPixels)
                .translationX(rect[0]).translationY(rect[1])
                .setDuration(240)
                .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                        a, android.R.interpolator.fast_out_slow_in))
                .withEndAction(() -> {
                    a.finish();
                    a.overridePendingTransition(0, 0);
                })
                .start();
    }

    /** 透明窗 + 点外面可关 + 居中，宽幅留边 */
    private static Dialog baseDialog(Activity a) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        d.getWindow().setDimAmount(0.42f);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        Window w = d.getWindow();
        int side = dp(a, 26);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);
        w.getDecorView().setPadding(side, 0, side, 0);
        return d;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
