package org.offblink.rgbcam;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志页（长按快门进入）：给「有些老照片读不出来」这类只在用户机器上出现的问题取证。
 *
 * <p>「刷新」会重新扫一遍全部图片：逐张做 打开 → 解码尺寸 → 解缩略图 → 读 EXIF，
 * 失败的把原因写进日志；连同取图/存图的埋点一起，用户复制回来就能定位。
 */
public class LogActivity extends AppCompatActivity {

    private TextView body;
    private TextView countView;
    private ScrollView scroll;
    private ExecutorService io;

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, LogActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_log);
        applyInsets();

        body = findViewById(R.id.lg_text);
        countView = findViewById(R.id.lg_count);
        scroll = findViewById(R.id.lg_scroll);

        findViewById(R.id.lg_back).setOnClickListener(v -> finish());
        findViewById(R.id.lg_clear).setOnClickListener(v -> {
            AppLog.clear();
            render();
            Ui.toast(this, R.string.log_cleared);
        });
        findViewById(R.id.lg_copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Prism log", AppLog.dump()));
            Ui.toast(this, R.string.log_copied);
        });
        findViewById(R.id.lg_refresh).setOnClickListener(v -> refresh());

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rgbcam-log");
            t.setDaemon(true);
            return t;
        });
        render();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
    }

    /** 重扫全部图片的可读性，结果进日志 */
    private void refresh() {
        AppLog.w("Scan", "开始扫描…");
        render();
        io.execute(() -> {
            scan();
            runOnUiThread(this::render);
        });
    }

    private void scan() {
        if (!AlbumStore.hasStoragePermission(this)) {
            AppLog.w("Scan", "没有相册权限，查不到照片");
            return;
        }
        List<AlbumStore.Photo> all = AlbumStore.listAll(this);
        AppLog.w("Scan", "共 " + all.size() + " 张图片");
        int bad = 0;
        for (AlbumStore.Photo p : all) {
            String line = probe(p.uri);
            if (line != null) {
                bad++;
                AppLog.w("Scan", line);
            }
        }
        AppLog.w("Scan", bad == 0 ? "全部可读" : (bad + " 张有问题（见上）"));
    }

    /** 逐张探针：打开/解尺寸/解缩略图/读EXIF，任一失败返回原因，全过返回 null */
    private String probe(Uri uri) {
        try {
            android.graphics.BitmapFactory.Options o =
                    new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return "打开失败(流为null) " + uri;
                }
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                return "尺寸解码失败 " + o.outWidth + "x" + o.outHeight + " mime="
                        + o.outMimeType + " " + uri;
            }
            Bitmap thumb = AlbumStore.thumb(this, uri, 400);
            if (thumb == null) {
                return "缩略图解码失败(" + o.outMimeType + " " + o.outWidth + "x"
                        + o.outHeight + ") " + uri;
            }
            // 不能 recycle：thumb 已进缓存，回收会毒掉缓存（相册再开就崩）
            int deg;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                deg = ChannelFilter.rotationDegrees(in);
            }
            return null;
        } catch (Exception e) {
            return "异常 " + e + " " + uri;
        }
    }

    private void render() {
        String dump = AppLog.dump();
        body.setText(dump.isEmpty() ? getString(R.string.log_empty) : dump);
        int lines = dump.isEmpty() ? 0 : dump.split("\n").length;
        countView.setText(getString(R.string.log_lines, lines));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void applyInsets() {
        int baseTopPad = dp(6);
        View topBar = findViewById(R.id.lg_top);
        View bottomBar = findViewById(R.id.lg_bottom);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.lg_root),
                (v, insets) -> {
                    int mask = WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout();
                    int top = insets.getInsets(mask).top;
                    int bottomInset = insets.getInsets(mask).bottom;
                    topBar.setPadding(topBar.getPaddingLeft(), baseTopPad + top,
                            topBar.getPaddingRight(), topBar.getPaddingBottom());
                    bottomBar.setPadding(bottomBar.getPaddingLeft(),
                            bottomBar.getPaddingTop(),
                            bottomBar.getPaddingRight(), bottomInset);
                    return insets;
                });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
