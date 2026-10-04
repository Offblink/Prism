package org.offblink.rgbcam;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 启动页：三滴通道色水滴落下汇成图标（{@link DropLogoView}）→ 图标弹出 →
 * 底部「让世界被看见」渐显 → 平移进拍摄页。整套动画 ≤1.4s（上限 1.5s）。
 */
public class SplashActivity extends AppCompatActivity {

    /** 图标弹出时刻：水滴刚被中心吸收完 */
    private static final long ICON_AT = 620;
    /** 小字渐显时刻 */
    private static final long TAGLINE_AT = 950;
    /** 进拍摄页：动画全部结束后 */
    private static final long HOLD_MS = 1400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable go = () -> {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        startActivity(new Intent(SplashActivity.this, CameraActivity.class));
        finish();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_splash);

        // 底部小字避开手势条
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sp_root), (v, insets) -> {
            int mask = WindowInsetsCompat.Type.systemBars();
            View tagline = findViewById(R.id.sp_tagline);
            tagline.setPadding(tagline.getPaddingLeft(), tagline.getPaddingTop(),
                    tagline.getPaddingRight(),
                    insets.getInsets(mask).bottom + dp(56));
            return insets;
        });

        // 初值：图标缩着藏起来、小字下沉待显
        View icon = findViewById(R.id.sp_icon);
        icon.setScaleX(0.6f);
        icon.setScaleY(0.6f);
        View tagline = findViewById(R.id.sp_tagline);
        tagline.setTranslationY(dp(10));

        // 水滴落完那一下：图标带 overshoot 弹出（视觉 = 水滴汇成图标）
        handler.postDelayed(() -> icon.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(340)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                .start(), ICON_AT);
        // 小字渐显 + 轻微上浮
        handler.postDelayed(() -> tagline.animate()
                .alpha(1f).translationY(0f)
                .setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start(), TAGLINE_AT);

        handler.postDelayed(go, HOLD_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(go);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
