package org.offblink.rgbcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * 启动页落滴动画（风格参考 gasp-design 的 gooey 圆滴）：
 * 三个通道色水滴错峰从顶部落下，边落边向屏幕中心收拢，末端缩小消失（被"吸收"）——
 * 图标在本 View 之上，带 overshoot 弹出，视觉上是"水滴汇成图标"。
 * 全程约 1.0s，加上图标/小字动画总时长 ≤1.5s。
 */
public class DropLogoView extends View {

    /** 三滴的起落错峰 */
    private static final long[] DELAY = {0, 90, 180};
    /** 单滴下落时长 */
    private static final long FALL = 480;
    /** 落定后中心扩散一圈细环（汇合的"涟漪"） */
    private static final long RING_AT = 660;
    private static final long RING_DUR = 300;
    /** 本 View 动画总时长（之后交给图标/小字） */
    private static final long TOTAL = 1000;

    private final Paint dropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final int[] colors = new int[3];
    private long t0 = -1;

    public DropLogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dropPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        colors[0] = ContextCompat.getColor(context, R.color.ch_r);
        colors[1] = ContextCompat.getColor(context, R.color.ch_g);
        colors[2] = ContextCompat.getColor(context, R.color.ch_b);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        t0 = SystemClock.uptimeMillis();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (t0 < 0) {
            t0 = SystemClock.uptimeMillis();
        }
        long t = SystemClock.uptimeMillis() - t0;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = dp(13);
        float startOffsetX = dp(64);
        float startY = -dp(70);

        for (int i = 0; i < 3; i++) {
            float local = t - DELAY[i];
            if (local <= 0 || local >= FALL) {
                continue;
            }
            float p = local / (float) FALL;
            float e = p * p;                       // 重力加速
            float lateral = (i == 1 ? 0f : (i == 0 ? -startOffsetX : startOffsetX));
            float x = cx + lateral * (1f - e);     // 边落边收拢到中心
            float y = startY + (cy - startY) * e;
            // 末端 28% 缩没 + 淡出 = 被中心吸收
            float sc = p < 0.72f ? 1f : 1f - (p - 0.72f) / 0.28f * 0.75f;
            float al = p < 0.70f ? 1f : 1f - (p - 0.70f) / 0.30f;
            if (sc <= 0.05f || al <= 0f) {
                continue;
            }
            dropPaint.setColor(colors[i]);
            dropPaint.setAlpha(Math.round(al * 255));
            drawDrop(c, x, y, r * sc);
        }

        // 汇合涟漪：中心一圈细环扩散淡出
        if (t >= RING_AT && t < RING_AT + RING_DUR) {
            float q = (t - RING_AT) / (float) RING_DUR;
            ringPaint.setColor(ContextCompat.getColor(getContext(), R.color.fg));
            ringPaint.setAlpha(Math.round((1f - q) * 80));
            ringPaint.setStrokeWidth(dp(1.5f) * (1f - q) + dp(0.5f));
            c.drawCircle(cx, cy, dp(44) + q * dp(52), ringPaint);
        }

        if (t < TOTAL) {
            postInvalidateOnAnimation();
        }
    }

    /** 水滴：圆底 + 上尖尾（下落时尾朝上） */
    private void drawDrop(Canvas c, float x, float y, float r) {
        path.reset();
        path.moveTo(x, y - 2.35f * r);
        path.quadTo(x + r * 0.8f, y - r * 0.9f, x + r, y);
        oval.set(x - r, y - r, x + r, y + r);
        path.arcTo(oval, 0, 180, false);
        path.quadTo(x - r * 0.8f, y - r * 0.9f, x, y - 2.35f * r);
        path.close();
        c.drawPath(path, dropPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
