package org.offblink.rgbcam;

import android.app.PendingIntent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拍摄模式的相册入口：单击进调色；长按进选择模式（涂抹连选 / 删除确认）。
 *
 * <p>两个入口，交互完全一样，只有加载的数据不同：底下的相册按钮列本应用的
 * {@code Pictures/Prism}；相机页顶上点「调色」带 {@link #EXTRA_PICK} 进来——
 * 自己的包装，替代系统相册的挑图页（那步 Done 太烦），列**全部图片**。
 */
public class GalleryActivity extends AppCompatActivity {

    /** 挑图态（相机页「调色」进来）：和相册一模一样的交互，只是列全部图片 */
    public static final String EXTRA_PICK = "pick";
    /** 相册入口的源矩形（屏幕坐标）：非空 = 缩放切页进来，返回时缩回该位置 */
    public static final String EXTRA_ZOOM_FROM = "zoom_from";
    private PhotoAdapter adapter;
    private TextView countView;
    private TextView deleteBtn;
    private TextView emptyView;
    private TextView albumBtn;
    private View scrim;
    private ScrollView dropdown;
    private LinearLayout bucketList;
    private ExecutorService io;
    /** 预热专用线程：绝不能和 io 共用——真机 6453 张全量预热会把 io 堵几分钟，
     删除/切相册全排在它后面（实测「点击无响应、日志无输出」就是这个） */
    private ExecutorService warm;
    /** 预热只做前 N 张（首屏 3×4 留裕量）：缓存 16MB 也只装得下几十张 */
    private static final int WARM_FIRST = 24;
    /** 选择模式：长按进入，进来后单击=勾选、拖动=涂抹多选，返回键先退模式再退页面 */
    private boolean selecting;
    private boolean pickMode;
    /** 当前系统相册（bucket 名），null = 全部 */
    private String album;
    /** 相册入口的源矩形：非空 = 本页是缩放进来的，返回要缩回去；调色态为 null 走平移 */
    private int[] zoomFrom;
    /** 切相册平移进行中（旧内容左移出屏 → 新内容从右滑入），防连点 */
    private boolean switching;

    private final ActivityResultLauncher<String[]> mediaLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> reload());

    /** 系统「允许删除 N 张」确认回来后的重试名单（挑图态里别人的照片要用户点头） */
    private List<AlbumStore.Photo> pendingRetry;
    private int pendingDeleted;

    private final ActivityResultLauncher<IntentSenderRequest> deleteConfirm =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                        List<AlbumStore.Photo> retry = pendingRetry;
                        int direct = pendingDeleted;
                        pendingRetry = null;
                        if (retry == null) {
                            return;
                        }
                        if (result.getResultCode() != RESULT_OK) {
                            // 用户在系统弹窗里拒绝了：直接删掉的算数，其余报失败
                            finishDelete(direct);
                            return;
                        }
                        io.execute(() -> {
                            int extra = 0;
                            for (AlbumStore.Photo photo : retry) {
                                if (AlbumStore.delete(this, photo)) {
                                    extra++;
                                }
                            }
                            finishDelete(direct + extra);
                        });
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_gallery);
        pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
        applyInsets();
        // 相册入口进来 = 从相册按钮位置放大（调色态没有该 extra，走默认平移）
        zoomFrom = getIntent().getIntArrayExtra(EXTRA_ZOOM_FROM);
        if (zoomFrom != null) {
            Ui.zoomIn(this, findViewById(R.id.gl_root), zoomFrom);
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dropdownOpen()) {
                    setDropdown(false);
                } else if (selecting) {
                    exitSelection();
                } else {
                    closePage();
                }
            }
        });

        findViewById(R.id.gl_back).setOnClickListener(v -> {
            if (dropdownOpen()) {
                setDropdown(false);
            } else if (selecting) {
                exitSelection();
            } else {
                closePage();
            }
        });
        countView = findViewById(R.id.gl_count);
        deleteBtn = findViewById(R.id.gl_delete);
        emptyView = findViewById(R.id.gl_empty);
        albumBtn = findViewById(R.id.gl_album);
        scrim = findViewById(R.id.gl_scrim);
        dropdown = findViewById(R.id.gl_dropdown);
        bucketList = findViewById(R.id.gl_buckets);
        // 相册标题 = 下拉开关；遮罩/再点标题 = 收起
        albumBtn.setOnClickListener(v -> toggleDropdown());
        scrim.setOnClickListener(v -> setDropdown(false));
        album = pickMode ? null : AlbumStore.ALBUM;   // 相册入口默认本应用相册，挑图默认全部
        emptyView.setOnClickListener(v -> {
            // 空态只在「没给相册权限」时可点，点了重新走授权
            if (!AlbumStore.hasStoragePermission(this)) {
                mediaLauncher.launch(AlbumStore.storagePermissions());
            }
        });

        RecyclerView grid = findViewById(R.id.gl_grid);
        adapter = new PhotoAdapter();
        adapter.setListener(new PhotoAdapter.Listener() {
            @Override
            public void onOpen(AlbumStore.Photo photo, int[] rect) {
                if (selecting) {
                    // 选择模式里单击 = 勾选，不进调色
                    adapter.toggle(photo.id);
                    refreshControls();
                } else {
                    // 看图 = 从照片位置放大：先截本页真帧当新页背景，再跳转
                    Ui.snapshot(GalleryActivity.this, () -> {
                        EditorActivity.start(GalleryActivity.this, photo.uri, rect);
                        overridePendingTransition(0, 0);
                    });
                }
            }

            @Override
            public void onPick(AlbumStore.Photo photo) {
                // 长按 = 进选择模式并勾住这张（挑图态同样：交互与相册完全一致）
                if (!selecting) {
                    selecting = true;
                    adapter.setPaintEnabled(true);
                }
                adapter.toggle(photo.id);
                refreshControls();
            }

            @Override
            public void onSelectionChanged() {
                refreshControls();
            }
        });
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setAdapter(adapter);

        deleteBtn.setOnClickListener(v -> {
            if (dropdownOpen()) {
                setDropdown(false);
                return;
            }
            confirmDelete();
        });

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rgbcam-gallery");
            t.setDaemon(true);
            return t;
        });
        warm = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rgbcam-warm");
            t.setDaemon(true);
            return t;
        });
        if (AlbumStore.hasStoragePermission(this)) {
            reload();
        } else {
            mediaLauncher.launch(AlbumStore.storagePermissions());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从调色模式回来可能多了一张存图，回来就刷一遍
        if (io != null) {
            reload();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
        if (warm != null) {
            warm.shutdownNow();
        }
        if (adapter != null) {
            adapter.shutdown();
        }
    }

    // ------------------------------------------------------------ 数据

    private void reload() {
        AppLog.w("Gallery", "reload 开始 album=" + album);
        io.execute(() -> {
            List<AlbumStore.Photo> data = album == null
                    ? AlbumStore.listAll(this)
                    : AlbumStore.listBucket(this, album);
            List<AlbumStore.Bucket> buckets = AlbumStore.buckets(this);
            // 「全部」的总数 = 各 bucket 之和（每张图必属一个 bucket，含 NULL 那批）
            int allCount = 0;
            for (AlbumStore.Bucket b : buckets) {
                allCount += b.count;
            }
            final int totalAll = allCount;
            AppLog.w("Gallery", "reload 查到 " + data.size() + " 张，"
                    + buckets.size() + " 个相册");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // 新一批数据 = 状态归零：选择模式一起退掉，计数回到「N 张」
                selecting = false;
                adapter.setPaintEnabled(false);
                adapter.submit(data);
                albumBtn.setText(album == null
                        ? getString(R.string.gallery_all) : album);
                buildBuckets(buckets, totalAll);
                refreshControls();
                if (switching) {
                    // 平移切页下半场：新内容从右滑入归位
                    View grid = findViewById(R.id.gl_grid);
                    grid.setTranslationX(grid.getWidth());
                    grid.animate().translationX(0).setDuration(200)
                            .setInterpolator(android.view.animation.AnimationUtils
                                    .loadInterpolator(GalleryActivity.this,
                                            android.R.interpolator.fast_out_slow_in))
                            .withEndAction(() -> switching = false)
                            .start();
                }
            });
            // 预热走独立线程且只做首屏：全量预热在真机（数千张）会堵死任务队列，
            // 缓存也只有 16MB，往后的滚动由 adapter 解码时顺手入缓存
            final List<AlbumStore.Photo> warmList = data.subList(0,
                    Math.min(data.size(), WARM_FIRST));
            warm.execute(() -> {
                for (AlbumStore.Photo p : warmList) {
                    AlbumStore.thumb(getApplicationContext(), p.uri, 400);
                }
                if (!warmList.isEmpty()) {
                    AppLog.w("Gallery", "预热首屏 " + warmList.size() + " 张完成");
                }
            });
        });
    }

    // ------------------------------------------------------------ 相册下拉

    /**
     * 关闭本页：相册入口缩回相册按钮位置（缩放的反动作），
     * 调色态没缩放过 → 走主题默认平移（当前页向右滑出）。
     */
    private void closePage() {
        if (zoomFrom != null) {
            Ui.shrinkFinish(this, findViewById(R.id.gl_root), zoomFrom);
        } else {
            finish();
        }
    }

    private void toggleDropdown() {
        setDropdown(dropdown.getVisibility() != View.VISIBLE);
    }

    private boolean dropdownOpen() {
        return dropdown != null && dropdown.getVisibility() == View.VISIBLE;
    }

    private void setDropdown(boolean open) {
        dropdown.setVisibility(open ? View.VISIBLE : View.GONE);
        scrim.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    /** 下拉列表：全部 + 系统 bucket（数量降序），点一项切相册并收起 */
    private void buildBuckets(List<AlbumStore.Bucket> buckets, int allCount) {
        bucketList.removeAllViews();
        addBucketRow(getString(R.string.gallery_all), allCount, album == null);
        for (AlbumStore.Bucket b : buckets) {
            addBucketRow(b.name, b.count, b.name.equals(album));
        }
    }

    private void addBucketRow(String name, int count, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(13), dp(18), dp(13));
        row.setOnClickListener(v -> {
            AppLog.w("Gallery", "点子相册 → " + name);
            if (switching) {
                return;
            }
            album = getString(R.string.gallery_all).equals(name) ? null : name;
            setDropdown(false);
            // 平移切页：旧内容先向左滑出屏，查好数据后再从右滑入
            switching = true;
            View grid = findViewById(R.id.gl_grid);
            grid.animate().translationX(-grid.getWidth()).setDuration(160)
                    .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                            this, android.R.interpolator.fast_out_linear_in))
                    .withEndAction(this::reload).start();
        });

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(14);
        label.setTextColor(ContextCompat.getColor(this,
                current ? R.color.fg : R.color.muted));
        label.setTypeface(null, current ? Typeface.BOLD : Typeface.NORMAL);

        TextView num = new TextView(this);
        num.setText(getString(R.string.gallery_count, count));
        num.setTextSize(12);
        num.setTextColor(ContextCompat.getColor(this, R.color.muted));
        num.setTypeface(Typeface.MONOSPACE);

        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(num, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        bucketList.addView(row);

        View line = new View(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        line.setBackgroundColor(ContextCompat.getColor(this, R.color.line));
        bucketList.addView(line);
    }

    /** 退选择模式：清空勾选、单击恢复成进调色 */
    private void exitSelection() {
        selecting = false;
        adapter.setPaintEnabled(false);
        adapter.clearSelection();
        refreshControls();
    }

    private void refreshControls() {
        int total = adapter.totalCount();
        int picked = adapter.selectedCount();
        boolean canRead = AlbumStore.hasStoragePermission(this);
        // 有选中就把计数换成「已选 N」：多选只服务于删除，进调色靠单击
        countView.setText(picked > 0
                ? getString(R.string.gallery_selected, picked)
                : getString(R.string.gallery_count, total));
        emptyView.setText(canRead ? R.string.gallery_empty : R.string.gallery_need_permission);
        emptyView.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        emptyView.setClickable(!canRead);

        // 删除常亮（没选中时是灰的）：点了给长按提示，不然用户发现不了多选
        deleteBtn.setEnabled(true);
        deleteBtn.setTextColor(ContextCompat.getColor(this,
                picked > 0 ? R.color.ch_r : R.color.muted));
    }

    // ------------------------------------------------------------ 删除

    private void confirmDelete() {
        int n = adapter.selectedCount();
        if (n == 0) {
            Ui.toast(this, R.string.gallery_pick_hint);
            return;
        }
        Ui.confirm(this,
                getString(R.string.gallery_delete_title),
                getString(R.string.gallery_delete_message, n),
                getString(R.string.gallery_delete_ok),
                ContextCompat.getColor(this, R.color.ch_r),
                this::doDelete);
    }

    private void doDelete() {
        List<AlbumStore.Photo> targets = adapter.selectedPhotos();
        if (targets.isEmpty()) {
            return;
        }
        AppLog.w("Gallery", "删除开始 " + targets.size() + " 张");
        deleteBtn.setEnabled(false);
        io.execute(() -> {
            int gone = 0;
            // 自己的（相册里那批）直接删；挑图态里别人的照片会撞 RecoverableSecurityException
            List<AlbumStore.Photo> blocked = new ArrayList<>();
            for (AlbumStore.Photo photo : targets) {
                if (AlbumStore.delete(this, photo)) {
                    gone++;
                } else {
                    blocked.add(photo);
                }
            }
            final int direct = gone;
            AppLog.w("Gallery", "直删成功 " + direct + "，待授权 " + blocked.size());
            if (blocked.isEmpty()) {
                finishDelete(direct);
                return;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                // 系统级「允许 Prism 删除 N 张」弹窗：一次批量一个，点头回来重试
                List<Uri> uris = new ArrayList<>();
                for (AlbumStore.Photo photo : blocked) {
                    uris.add(photo.uri);
                }
                try {
                    PendingIntent pi = MediaStore.createDeleteRequest(
                            getContentResolver(), uris);
                    pendingRetry = blocked;
                    pendingDeleted = direct;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        try {
                            deleteConfirm.launch(new IntentSenderRequest
                                    .Builder(pi.getIntentSender()).build());
                        } catch (Exception e) {
                            AppLog.w("Gallery", "起删除确认失败 " + e);
                            pendingRetry = null;
                            finishDelete(pendingDeleted);
                        }
                    });
                    return;
                } catch (RuntimeException e) {
                    AppLog.w("Gallery", "createDeleteRequest 失败 " + e);
                }
            }
            finishDelete(direct);
        });
    }

    /** 统一收尾：提示 + 回列表（reload 会把选择态归零、按钮恢复） */
    private void finishDelete(int count) {
        AppLog.w("Gallery", "删除收尾 成功 " + count + " 张");
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            Ui.toast(this, count > 0
                    ? R.string.gallery_deleted : R.string.gallery_delete_failed);
            reload();
        });
    }

    // ------------------------------------------------------------ 系统栏

    private void applyInsets() {
        int baseTopPad = dp(6);
        View root = findViewById(R.id.gl_root);
        View topBar = findViewById(R.id.gl_top);
        View bottomBar = findViewById(R.id.gl_bottom);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int mask = WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout();
            int top = insets.getInsets(mask).top;
            int bottomInset = insets.getInsets(mask).bottom;
            topBar.setPadding(topBar.getPaddingLeft(), baseTopPad + top,
                    topBar.getPaddingRight(), topBar.getPaddingBottom());
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                    bottomBar.getPaddingRight(), bottomInset);
            return insets;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
