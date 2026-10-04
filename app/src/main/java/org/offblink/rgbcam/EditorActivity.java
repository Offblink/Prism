package org.offblink.rgbcam;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调色模式：一张照片 + 一个统一滑块 + 三个通道按钮，全部画在 WebView 里
 * （{@code assets/edit.html}），原生只负责取图、存图和系统栏。
 *
 * <p>进图路径两条：相机页「调色」的挑图页、应用相册单击。原图先拷进私有缓存
 * （{@code cacheDir/edit_*.jpg}）：WebView 的 file 页面读私有目录最稳，存图也统一从
 * 这份拷贝出。
 *
 * <p>数值回传：网页每动一下滑块就调 {@code Native.onValues(r,g,b)}，存图时原生
 * {@link ChannelFilter} 对原图做同一条乘法——不经过 base64，不掉分辨率。
 */
public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_SRC = "src";
    /** 进页的源矩形（照片格屏幕坐标）：返回时整页缩回这个位置 */
    public static final String EXTRA_ZOOM_FROM = "zoom_from";

    private WebView web;
    private View loading;
    private TextView saveBtn;
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 缩放返回的源矩形，null = 没缩放过（直接 finish） */
    private int[] zoomFrom;
    /** 返回动画进行中，防连点 */
    private boolean closing;
    /** 覆盖保存授权（别人的照片要系统点个头）回来后重写 */
    private byte[] pendingOverwrite;

    private final ActivityResultLauncher<IntentSenderRequest> writeConfirm =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                        byte[] jpeg = pendingOverwrite;
                        pendingOverwrite = null;
                        if (jpeg == null) {
                            return;
                        }
                        if (result.getResultCode() != RESULT_OK) {
                            runOnUiThread(() -> {
                                saving = false;
                                saveBtn.setEnabled(true);
                                saveBtn.setText(R.string.editor_save);
                                Ui.toast(this, R.string.editor_overwrite_denied);
                            });
                            return;
                        }
                        io.execute(() -> {
                            String src = getIntent().getStringExtra(EXTRA_SRC);
                            if (src != null) {
                                writeOverwrite(Uri.parse(src), jpeg);
                            }
                        });
                    });

    /** 网页端回传的三个通道值（100 = 原样），存图时用 */
    private volatile int vr = 100;
    private volatile int vg = 100;
    private volatile int vb = 100;
    /** 归一化后的原图（缓存里的那份拷贝） */
    private volatile String prepared;
    private volatile boolean saving;

    /** @param zoomFrom 照片格的屏幕矩形：非空则从该位置放大进入、返回缩回 */
    public static void start(Context ctx, Uri src, int[] zoomFrom) {
        Intent i = new Intent(ctx, EditorActivity.class)
                .putExtra(EXTRA_SRC, src.toString());
        if (zoomFrom != null) {
            i.putExtra(EXTRA_ZOOM_FROM, zoomFrom);
        }
        ctx.startActivity(i);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_editor);
        applyInsets();

        web = findViewById(R.id.ed_web);
        loading = findViewById(R.id.ed_loading);
        saveBtn = findViewById(R.id.ed_save);
        // 看图 = 从照片格放大进入，返回则缩回原位
        zoomFrom = getIntent().getIntArrayExtra(EXTRA_ZOOM_FROM);
        if (zoomFrom != null) {
            Ui.zoomIn(this, findViewById(R.id.ed_root), zoomFrom);
        }
        findViewById(R.id.ed_back).setOnClickListener(v -> closePage());
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        closePage();
                    }
                });
        saveBtn.setOnClickListener(v -> showSaveMenu());

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // 编辑页是 file:///android_asset/，图片在私有缓存里：开文件访问让 JS 直接读
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setDomStorageEnabled(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg));
        web.addJavascriptInterface(new Bridge(), "Native");
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rgbcam-editor");
            t.setDaemon(true);
            return t;
        });
        io.execute(this::prepare);

        // 看门灯：JS 万一没跑起来（图坏了、脚本报错），5 秒后也别把用户卡在转圈上
        main.postDelayed(() -> {
            if (loading.getVisibility() == View.VISIBLE) {
                showPage();
            }
        }, 5000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (io != null) {
            io.shutdown();
        }
        if (web != null) {
            web.stopLoading();
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) {
                parent.removeView(web);
            }
            web.destroy();
        }
    }

    /** 返回：从照片格放大进来的就缩回照片格，否则走默认平移出屏 */
    private void closePage() {
        if (closing) {
            return;
        }
        if (zoomFrom != null) {
            closing = true;
            Ui.shrinkFinish(this, findViewById(R.id.ed_root), zoomFrom);
        } else {
            finish();
        }
    }

    // ------------------------------------------------------------ 取图

    /** 源（content:// 或 file://）→ 私有缓存唯一副本，成功后把地址交给网页 */
    private void prepare() {
        File[] stale = getCacheDir().listFiles((d, n) -> n.startsWith("edit_"));
        if (stale != null) {
            for (File f : stale) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        String src = getIntent().getStringExtra(EXTRA_SRC);
        if (src == null) {
            runOnUiThread(() -> {
                Ui.toast(this, "没有要调色的照片");
                finish();
            });
            return;
        }

        File out = new File(getCacheDir(), "edit_" + System.currentTimeMillis() + ".jpg");
        AppLog.w("Editor", "取图 src=" + src);
        try (InputStream in = openSource(Uri.parse(src));
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new IOException("打不开原图");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } catch (IOException | SecurityException e) {
            AppLog.e("Editor", "取图失败 src=" + src, e);
            runOnUiThread(() -> {
                Ui.toast(this, "读取照片失败");
                finish();
            });
            return;
        }

        prepared = out.getAbsolutePath();
        // o = EXIF 旋转角，w/h = 原始像素尺寸：网页据此判断 WebView 有没有自己摆正图片
        // （现代 WebView 会自己摆正，见 edit.html 的 orientationApplied）
        android.graphics.BitmapFactory.Options bounds =
                new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(prepared, bounds);
        int deg = ChannelFilter.rotationDegrees(prepared);
        AppLog.w("Editor", "取图完成 w=" + bounds.outWidth + " h=" + bounds.outHeight
                + " deg=" + deg + " mime=" + bounds.outMimeType);
        runOnUiThread(() -> web.loadUrl("file:///android_asset/edit.html#p="
                + Uri.encode(prepared) + "&o=" + deg
                + "&w=" + bounds.outWidth + "&h=" + bounds.outHeight));
    }

    private InputStream openSource(Uri uri) throws IOException {
        if ("content".equals(uri.getScheme())) {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("content 流为空");
            }
            return in;
        }
        String path = uri.getPath();
        if (path == null) {
            throw new IOException("路径为空");
        }
        return new java.io.FileInputStream(path);
    }

    // ------------------------------------------------------------ 存图

    /** 点保存 → 弹两项菜单：另存（新文件）/ 覆盖（写回原图） */
    private void showSaveMenu() {
        if (saving || prepared == null) {
            return;
        }
        Ui.menu(this, getString(R.string.editor_save),
                new CharSequence[]{
                        getString(R.string.editor_save_as),
                        getString(R.string.editor_overwrite)},
                which -> {
                    if (which == 0) {
                        save();
                    } else {
                        saveOverwrite();
                    }
                });
    }

    /** 另存：新文件进应用相册（原行为） */
    private void save() {
        if (saving || prepared == null) {
            return;
        }
        saving = true;
        saveBtn.setEnabled(false);
        saveBtn.setText(R.string.editor_saving);
        int r = vr;
        int g = vg;
        int b = vb;
        String path = prepared;

        io.execute(() -> {
            Uri saved = null;
            String err = null;
            try {
                byte[] jpeg = ChannelFilter.apply(path, r, g, b);
                saved = AlbumStore.insertJpeg(getApplicationContext(), jpeg,
                        AlbumStore.newName());
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            Uri result = saved;
            String error = err;
            runOnUiThread(() -> {
                saving = false;
                saveBtn.setEnabled(true);
                saveBtn.setText(R.string.editor_save);
                if (result != null) {
                    Ui.toast(this, R.string.editor_saved);
                } else {
                    Ui.toast(this, getString(R.string.editor_save_failed,
                            error == null ? "未知错误" : error));
                }
            });
        });
    }

    /** 覆盖：把调色结果写回源图。烘焙同 save，写入目标是 EXTRA_SRC 那个 uri */
    private void saveOverwrite() {
        if (saving || prepared == null) {
            return;
        }
        String src = getIntent().getStringExtra(EXTRA_SRC);
        if (src == null) {
            return;
        }
        saving = true;
        saveBtn.setEnabled(false);
        saveBtn.setText(R.string.editor_saving);
        int r = vr;
        int g = vg;
        int b = vb;
        String path = prepared;
        Uri target = Uri.parse(src);

        io.execute(() -> {
            byte[] jpeg;
            try {
                jpeg = ChannelFilter.apply(path, r, g, b);
            } catch (IOException | RuntimeException e) {
                String err = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName();
                runOnUiThread(() -> saveDone(false, err));
                return;
            }
            writeOverwrite(target, jpeg);
        });
    }

    /** 写回目标 uri；没权限（别人的照片）就走系统授权弹窗，点头后重写 */
    private void writeOverwrite(Uri target, byte[] jpeg) {
        try {
            if ("content".equals(target.getScheme())) {
                try (OutputStream os = getContentResolver().openOutputStream(target, "wt")) {
                    if (os == null) {
                        throw new IOException("openOutputStream 返回 null");
                    }
                    os.write(jpeg);
                }
            } else {
                try (OutputStream os = new FileOutputStream(target.getPath())) {
                    os.write(jpeg);
                }
            }
            AlbumStore.invalidateThumb(target);
            runOnUiThread(() -> saveDone(true, null));
        } catch (SecurityException | IOException e) {
            AppLog.w("Editor", "覆盖写入失败 " + target + " : " + e);
            if (Build.VERSION.SDK_INT >= 29 && "content".equals(target.getScheme())) {
                try {
                    PendingIntent pi = MediaStore.createWriteRequest(
                            getContentResolver(),
                            java.util.Collections.singletonList(target));
                    pendingOverwrite = jpeg;
                    runOnUiThread(() -> {
                        try {
                            writeConfirm.launch(new IntentSenderRequest
                                    .Builder(pi.getIntentSender()).build());
                        } catch (Exception ex) {
                            pendingOverwrite = null;
                            saveDone(false, String.valueOf(ex));
                        }
                    });
                    return;
                } catch (RuntimeException re) {
                    AppLog.w("Editor", "createWriteRequest 失败 " + re);
                }
            }
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            runOnUiThread(() -> saveDone(false, err));
        }
    }

    private void saveDone(boolean ok, String error) {
        saving = false;
        saveBtn.setEnabled(true);
        saveBtn.setText(R.string.editor_save);
        Ui.toast(this, ok ? getString(R.string.editor_overwritten)
                : getString(R.string.editor_save_failed, error == null ? "未知错误" : error));
    }

    private void showPage() {
        loading.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ 网页桥

    public class Bridge {
        /** 网页把原图画出来了，撤掉转圈 */
        @JavascriptInterface
        public void ready() {
            runOnUiThread(EditorActivity.this::showPage);
        }

        /** 滑块/按钮一变就把三个通道值同步给原生 */
        @JavascriptInterface
        public void onValues(int r, int g, int b) {
            vr = r;
            vg = g;
            vb = b;
        }

        /** 给网页兜底：不走 URL 参数也能拿到图的地址 */
        @JavascriptInterface
        public String src() {
            return prepared;
        }

        /** 网页侧报错进日志（图解不出来时的最后取证点） */
        @JavascriptInterface
        public void log(String msg) {
            AppLog.w("Web", msg);
        }

        /**
         * WebView 解不开原图（CMYK JPEG 这类 Chromium 不认、原生认的格式）时的自救：
         * 按 100/100/100 走一遍 {@link ChannelFilter}——EXIF 方向烘焙进像素、重编码
         * 标准 JPEG，返回新缓存文件路径；失败返回空串。
         */
        @JavascriptInterface
        public String convert() {
            String path = prepared;
            if (path == null) {
                return "";
            }
            try {
                byte[] jpeg = ChannelFilter.apply(path, 100, 100, 100);
                File out = new File(getCacheDir(),
                        "edit_cvt_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream os = new FileOutputStream(out)) {
                    os.write(jpeg);
                }
                AppLog.w("Editor", "转码成功 " + out.getName() + " " + jpeg.length + "B");
                return out.getAbsolutePath();
            } catch (IOException | RuntimeException e) {
                AppLog.e("Editor", "转码失败", e);
                return "";
            }
        }
    }

    // ------------------------------------------------------------ 系统栏

    private void applyInsets() {
        int baseTopPad = dp(6);
        int baseBottomPad = dp(0);
        View root = findViewById(R.id.ed_root);
        View topBar = findViewById(R.id.ed_top);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int mask = WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout();
            int top = insets.getInsets(mask).top;
            int bottomInset = insets.getInsets(mask).bottom;
            topBar.setPadding(topBar.getPaddingLeft(), baseTopPad + top,
                    topBar.getPaddingRight(), topBar.getPaddingBottom());
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), baseBottomPad + bottomInset);
            return insets;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
