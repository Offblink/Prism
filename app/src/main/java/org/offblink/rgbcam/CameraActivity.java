package org.offblink.rgbcam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拍摄模式：后摄取景 + 快门 + 应用相册入口，顶上一个「拍摄 / 调色」模式切换。
 *
 * <p>极简口径：无闪光、无变焦、无前摄。按快门 → 照片先落进应用相册 → 留在取景接着拍
 * （边调边拍：通道值不复位，下一张继续按当前调色拍）。顶上点「调色」＝开自己的挑图页
 * （全部图片，点一下直接进调色，没有系统相册的 Done 确认）。
 */
public class CameraActivity extends AppCompatActivity {

    private PreviewView preview;
    private View topBar;
    private LinearLayout modes;
    private TextView modeShoot;
    private TextView modeEdit;
    private View indicator;
    private LinearLayout bottomBar;
    private View shutter;
    private View shutterRing;
    private View shutterDisc;
    private ImageView thumb;
    private View permGate;
    private TextView permBtn;
    private View shutterMask;

    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ExecutorService io;
    private boolean capturing;
    private View focusRing;
    /** 实时调色：网页控件回传的三个通道值（100 = 原样），拍摄时按同一组值烘焙 */
    private WebView controls;
    private int gainR = 100;
    private int gainG = 100;
    private int gainB = 100;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 点按对焦框的收尾任务：连点时先撤掉上一次的，避免两个淡出动画打架 */
    private final Runnable hideFocus = () -> {
        if (focusRing == null) {
            return;
        }
        focusRing.animate().alpha(0f).setDuration(260)
                .withEndAction(() -> focusRing.setVisibility(View.INVISIBLE)).start();
    };

    // ------------------------------------------------------------ 明暗自适应主题

    /** 取景暗 → 浅色前景（深色主题），取景亮 → 深色前景（浅色主题）；100/140
     迟滞带防抖。上下纱已去掉，前景反色是相机页唯一的对比度手段 */
    private boolean uiDark;
    /** 当前在「拍摄/调色」哪个态：换主题时按它重涂两个标签的强调色 */
    private boolean editMode;
    /** 网页调色盘加载完没有：没完就存着 uiDark，等 ready 时补推 */
    private boolean controlsReady;
    private final int[] lumaPx = new int[32 * 32];
    /** 每 500ms 采一次取景亮度（32×32 缩图求均值，开销可忽略），按迟滞带切主题 */
    private final Runnable themeSampler = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            Bitmap small = null;
            // PreviewView 没有 (w,h) 缩图重载；COMPATIBLE 模式下它内嵌的就是 TextureView，
            // 直接向 TextureView 要 32×32（每次只 alloc 4KB，比全帧 3.7MB 便宜三个量级）
            for (int i = 0; i < preview.getChildCount(); i++) {
                View child = preview.getChildAt(i);
                if (child instanceof android.view.TextureView) {
                    small = ((android.view.TextureView) child).getBitmap(32, 32);
                    break;
                }
            }
            if (small != null) {
                int w = small.getWidth();
                int h = small.getHeight();
                small.getPixels(lumaPx, 0, w, 0, 0, w, h);
                int n = w * h;
                long sr = 0;
                long sg = 0;
                long sb = 0;
                for (int i = 0; i < n; i++) {
                    int px = lumaPx[i];
                    sr += px >> 16 & 0xFF;
                    sg += px >> 8 & 0xFF;
                    sb += px & 0xFF;
                }
                // getBitmap 给的是相机原帧（硬件层 ColorMatrix 合成时才生效）：
                // 按当前实时调色的增益折算，才是屏幕上实际看到的亮度
                double rr = Math.min(255, sr * gainR / (100.0 * n));
                double gg = Math.min(255, sg * gainG / (100.0 * n));
                double bb = Math.min(255, sb * gainB / (100.0 * n));
                int luma = (int) (rr * 0.2126 + gg * 0.7152 + bb * 0.0722);
                // 纱已去掉，字直接压在取景上：暗取景配浅字、亮取景配深字才保得住对比
                if (!uiDark && luma <= 100) {
                    applyCameraTheme(true);
                } else if (uiDark && luma >= 140) {
                    applyCameraTheme(false);
                }
                small.recycle();
            }
            mainHandler.postDelayed(this, 500);
        }
    };

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // 相机没给就留引导层；相册权限没给只是缩略图先空着（相册页会再要一次）
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    onCameraGranted();
                } else {
                    permGate.setVisibility(View.VISIBLE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_camera);

        preview = findViewById(R.id.cam_preview);
        // 必须是 TextureView（COMPATIBLE）：相机像素画进窗口里，硬件层的
        // ColorMatrix 才够得着它。默认的 SurfaceView 是独立合成层，滤镜永远打不上
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        topBar = findViewById(R.id.cam_top);
        modes = findViewById(R.id.cam_modes);
        modeShoot = findViewById(R.id.cam_mode_shoot);
        modeEdit = findViewById(R.id.cam_mode_edit);
        indicator = findViewById(R.id.cam_mode_indicator);
        bottomBar = findViewById(R.id.cam_bottom);
        shutter = findViewById(R.id.cam_shutter);
        shutterRing = findViewById(R.id.cam_shutter_ring);
        shutterDisc = findViewById(R.id.cam_shutter_disc);
        thumb = findViewById(R.id.cam_thumb);
        permGate = findViewById(R.id.cam_perm);
        permBtn = findViewById(R.id.cam_perm_btn);
        shutterMask = findViewById(R.id.cam_shutter_mask);

        applyInsets();

        modeShoot.setOnClickListener(v -> selectMode(false));
        modeEdit.setOnClickListener(v -> {
            // 自己的挑图页（全部图片）：点一下直接进调色，没有系统相册那步 Done
            selectMode(true);
            startActivity(new android.content.Intent(this, GalleryActivity.class)
                    .putExtra(GalleryActivity.EXTRA_PICK, true));
        });
        shutter.setOnClickListener(v -> takePhoto());
        shutter.setOnLongClickListener(v -> {
            // 长按快门 = 日志页（读图取证入口，不在界面上留按钮）
            LogActivity.start(this);
            return true;
        });
        bindShutterPress();
        findViewById(R.id.cam_gallery).setOnClickListener(v -> {
            // 打开相册 = 从相册按钮位置放大：先截本页真帧当新页背景，再跳转
            int[] rect = Ui.rectOnScreen(v);
            Ui.snapshot(this, () -> {
                android.content.Intent i = new android.content.Intent(
                        CameraActivity.this, GalleryActivity.class);
                i.putExtra(GalleryActivity.EXTRA_ZOOM_FROM, rect);
                startActivity(i);
                overridePendingTransition(0, 0);
            });
        });
        permBtn.setOnClickListener(v -> permLauncher.launch(requestedPermissions()));

        focusRing = findViewById(R.id.cam_focus);
        bindTapToFocus();
        setupControls();

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rgbcam-io");
            t.setDaemon(true);
            return t;
        });

        topBar.post(() -> placeIndicator(modeShoot, false));
        refreshThumb();

        boolean cameraGranted = ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (cameraGranted) {
            onCameraGranted();
        } else {
            permGate.setVisibility(View.VISIBLE);
        }
        if (!cameraGranted || !AlbumStore.hasStoragePermission(this)) {
            permLauncher.launch(requestedPermissions());
        }
    }

    /** 首启一次要齐：取景要相机，相册缩略图要相册读权（API 33+ 没它连自己拍的都查不到） */
    private String[] requestedPermissions() {
        String[] storage = AlbumStore.storagePermissions();
        String[] all = new String[storage.length + 1];
        all[0] = Manifest.permission.CAMERA;
        System.arraycopy(storage, 0, all, 1, storage.length);
        return all;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modeShoot != null) {
            // 从调色/相册回来：指示条回到「拍摄」
            selectMode(false);
        }
        refreshThumb();
        // 取景明暗采样：页面可见才跑（去调色/相册时自动停）
        mainHandler.removeCallbacks(themeSampler);
        mainHandler.post(themeSampler);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(themeSampler);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(hideFocus);
        mainHandler.removeCallbacks(themeSampler);
        if (io != null) {
            io.shutdown();
        }
        if (provider != null) {
            provider.unbindAll();
        }
    }

    // ------------------------------------------------------------ 权限 / 相机

    private void onCameraGranted() {
        permGate.setVisibility(View.GONE);
        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                provider = future.get();
            } catch (Exception e) {
                Ui.toast(this, "相机启动失败：" + e.getClass().getSimpleName());
                return;
            }
            Preview viewfinder = new Preview.Builder().build();
            viewfinder.setSurfaceProvider(preview.getSurfaceProvider());
            // 只有后摄、只有预览 + 拍照：无闪光（默认 OFF）、无变焦、无前摄
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .build();
            provider.unbindAll();
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    viewfinder, imageCapture);
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * 点取景区任意位置 → 那一点对焦 + 测光（自动对焦和曝光一起交给点击处），
     * 同时在指尖位置亮一下对焦框。极简口径不变：不加对焦/曝光滑块。
     */
    private void bindTapToFocus() {
        preview.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return true;
            }
            if (camera == null) {
                return true;
            }
            MeteringPoint point = preview.getMeteringPointFactory()
                    .createPoint(event.getX(), event.getY());
            try {
                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(point,
                                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                                .build());
            } catch (IllegalArgumentException e) {
                // 点落在可测区之外（画面极边缘），忽略即可
            }
            showFocusRing(event.getX(), event.getY());
            return true;
        });
    }

    /** 对焦框：在点击处亮起，约 1 秒后淡出；连点会把上一次的收尾任务取消掉 */
    private void showFocusRing(float x, float y) {
        mainHandler.removeCallbacks(hideFocus);
        focusRing.setTranslationX(x - focusRing.getWidth() / 2f);
        focusRing.setTranslationY(y - focusRing.getHeight() / 2f);
        focusRing.animate().cancel();
        focusRing.setAlpha(0f);
        focusRing.setVisibility(View.VISIBLE);
        focusRing.animate().alpha(1f).setDuration(120).start();
        mainHandler.postDelayed(hideFocus, 1000);
    }

    private void takePhoto() {
        if (imageCapture == null || capturing) {
            return;
        }
        flashShutter();
        capturing = true;
        shutter.setEnabled(false);
        File tmp = new File(getCacheDir(), AlbumStore.newName());
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(tmp).build(),
                io, new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        // 取景什么样，入册就什么样：按当前通道值烘焙；
                        // 三个都是 100 就原样入库，不白重编码一次
                        Uri uri;
                        boolean baked = true;
                        if (gainR == 100 && gainG == 100 && gainB == 100) {
                            uri = AlbumStore.insertFromFile(CameraActivity.this, tmp,
                                    tmp.getName());
                        } else {
                            try {
                                byte[] filtered = ChannelFilter.apply(tmp.getPath(),
                                        gainR, gainG, gainB);
                                uri = AlbumStore.insertJpeg(CameraActivity.this, filtered,
                                        tmp.getName());
                            } catch (IOException e) {
                                Log.w("Prism", "拍后调色失败，退回存原片", e);
                                baked = false;
                                uri = AlbumStore.insertFromFile(CameraActivity.this, tmp,
                                        tmp.getName());
                            }
                        }
                        final Uri saved = uri;
                        final boolean colorApplied = baked;
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        runOnUiThread(() -> {
                            capturing = false;
                            shutter.setEnabled(true);
                            if (saved == null) {
                                Ui.toast(CameraActivity.this, "保存失败");
                                return;
                            }
                            if (!colorApplied) {
                                Ui.toast(CameraActivity.this, R.string.shot_color_failed);
                            }
                            refreshThumb();
                            // 边调边拍：拍完留在取景接着拍，不跳调色模式
                            // （通道值不复位，下一张继续按当前调色烘焙；要改图进相册单击进调色）
                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        runOnUiThread(() -> {
                            capturing = false;
                            shutter.setEnabled(true);
                            Ui.toast(CameraActivity.this,
                                    "拍摄失败：" + exception.getMessage());
                        });
                    }
                });
    }

    /** 拍摄黑闪：黑 mask 迅速压到 85% 再散掉，模拟快门帘一闪而过 */
    private void flashShutter() {
        shutterMask.animate().cancel();
        shutterMask.setAlpha(0f);
        shutterMask.animate().alpha(0.85f).setDuration(70)
                .withEndAction(() -> shutterMask.animate().alpha(0f)
                        .setDuration(160).start()).start();
    }

    /** 按下快门内盘缩一下、松手弹回：纯触感反馈，不改变布局 */
    private void bindShutterPress() {
        shutter.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    shutterDisc.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    shutterDisc.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    break;
                default:
                    break;
            }
            return false; // 放行，让 click 照常触发
        });
    }

    // ------------------------------------------------------------ 实时调色

    /** 拍摄页底部的调色面板：和调色模式共用 controls.css，两边视觉完全一致 */
    private void setupControls() {
        controls = findViewById(R.id.cam_controls);
        WebSettings s = controls.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        controls.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        controls.addJavascriptInterface(new ControlsBridge(), "Native");
        controls.loadUrl("file:///android_asset/controls.html");
    }

    /** 网页 → 原生：滑块/通道按钮/复位一动，就把三个通道值同步过来刷取景 */
    public class ControlsBridge {
        @JavascriptInterface
        public void onValues(int r, int g, int b) {
            gainR = r;
            gainG = g;
            gainB = b;
            runOnUiThread(CameraActivity.this::applyLiveGain);
        }

        @JavascriptInterface
        public void ready() {
            controlsReady = true;
            runOnUiThread(CameraActivity.this::pushWebTheme);
        }
    }

    // ------------------------------------------------------------ 明暗自适应主题

    /**
     * 相机页按取景亮度反色（用户拍板，上下纱已去掉）：取景暗 → 浅色前景
     * （深色主题样式），取景亮 → 深色前景（浅色主题样式）——字直接压在取景上，
     * 对比度全靠前景跟背景反着走。只动相机页——调色页/相册有自己的底色，保持浅色。
     */
    private void applyCameraTheme(boolean dark) {
        uiDark = dark;
        int fg = ContextCompat.getColor(this, dark ? R.color.fg_dark : R.color.fg);
        selectMode(editMode);              // 两个模式标签按当前态重涂前景色
        indicator.setBackgroundColor(fg);
        tint(shutterRing, fg);
        tint(shutterDisc, fg);
        // 相册入口描边：亮取景用黑 25%（原色），暗取景染成白 25%
        tint(findViewById(R.id.cam_gallery), dark ? android.graphics.Color.WHITE : null);
        // 系统栏图标跟着前景反：暗取景（浅前景）用浅图标，亮取景用深图标
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!dark);
        bars.setAppearanceLightNavigationBars(!dark);
        pushWebTheme();
    }

    /** 形状背景整体染色：tint 走 SRC_IN，只换颜色，渐变/描边的 alpha 照旧 */
    private void tint(View v, Integer color) {
        Drawable d = v.getBackground();
        if (d == null) {
            return;
        }
        d = d.mutate();
        d.setTintList(color == null ? null : ColorStateList.valueOf(color));
    }

    /** 当前主题推给网页调色盘；页面没就绪就等 ready 再推（uiDark 是现成的） */
    private void pushWebTheme() {
        if (controls != null && controlsReady) {
            controls.evaluateJavascript(
                    "window.setCameraTheme&&setCameraTheme(" + uiDark + ")", null);
        }
    }

    /**
     * 通道乘法直接打在取景层上：硬件层 + ColorMatrixColorFilter，逐帧由 GPU 做，
     * 和存图时的 {@link ChannelFilter} 是同一条数学（预览什么样，存下来就什么样）。
     * 三个值都是 100 就把层撤掉，省掉一次全屏合成。
     */
    private void applyLiveGain() {
        if (preview == null) {
            return;
        }
        if (gainR == 100 && gainG == 100 && gainB == 100) {
            preview.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                gainR / 100f, 0, 0, 0, 0,
                0, gainG / 100f, 0, 0, 0,
                0, 0, gainB / 100f, 0, 0,
                0, 0, 0, 1, 0}));
        preview.setLayerType(View.LAYER_TYPE_HARDWARE, p);
    }

    // ------------------------------------------------------------ 模式切换

    /** @param edit true = 调色态样式（指示条滑到「调色」，返回时 onResume 落回拍摄） */
    private void selectMode(boolean edit) {
        editMode = edit;
        // 前景色跟着明暗主题走（取景亮 → 深底浅字，暗 → 浅底深字）
        int fg = ContextCompat.getColor(this, uiDark ? R.color.fg_dark : R.color.fg);
        int mut = ContextCompat.getColor(this, uiDark ? R.color.muted_dark : R.color.muted);
        modeShoot.setTextColor(edit ? mut : fg);
        modeShoot.setTypeface(null, edit ? Typeface.NORMAL : Typeface.BOLD);
        modeEdit.setTextColor(edit ? fg : mut);
        modeEdit.setTypeface(null, edit ? Typeface.BOLD : Typeface.NORMAL);
        placeIndicator(edit ? modeEdit : modeShoot, true);
    }

    /** 指示条滑到目标标签正下方（父容器内的绝对坐标 = 模式行偏移 + 标签偏移） */
    private void placeIndicator(View target, boolean animate) {
        topBar.post(() -> {
            float x = modes.getLeft() + target.getLeft() + target.getWidth() / 2f
                    - indicator.getWidth() / 2f;
            if (animate) {
                indicator.animate().translationX(x).setDuration(180).start();
            } else {
                indicator.setTranslationX(x);
            }
        });
    }

    // ------------------------------------------------------------ 相册缩略图

    private void refreshThumb() {
        if (io == null || io.isShutdown()) {
            return;
        }
        io.execute(() -> {
            Uri uri = AlbumStore.latestUri(this);
            Bitmap bmp = uri == null ? null : AlbumStore.thumb(this, uri, 320);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                thumb.setImageBitmap(bmp);
            });
        });
    }

    // ------------------------------------------------------------ 系统栏

    private void applyInsets() {
        int baseTopPad = dp(14);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cam_root),
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
