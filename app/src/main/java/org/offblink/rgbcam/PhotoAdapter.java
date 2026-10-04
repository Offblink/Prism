package org.offblink.rgbcam;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相册网格：3 列方格，缩略图后台解、按 tag 对号入座（滚快了旧图不串位）。
 *
 * <p>手势分工（用户拍板）：单击＝进调色；长按＝进选择模式；选择模式里单击＝勾选，
 * 按住拖动＝**序列范围选择**——相册是一维照片序列，起笔那张是锚点，锚点到手指
 * 当前位置之间的所有照片按本笔方向翻转（斜着从左上拖到右下 = 起点到终点全选）；
 * 往回拖区间收缩，本笔涂过又甩出区间的自动撤销（回撤反选、折返点都由区间管）。
 * 只翻本笔动过的，之前就选好的不碰。选中态只画圈+压暗，不整格重绑。
 */
class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.VH> {

    interface Listener {
        /** 单击一格：选择模式里是勾选，否则直接进调色；rect = 这格的屏幕矩形（缩放切页用） */
        void onOpen(AlbumStore.Photo photo, int[] rect);

        /** 长按一格：进选择模式并勾住它 */
        void onPick(AlbumStore.Photo photo);

        /** 涂抹勾选有变化：让界面刷新计数 */
        void onSelectionChanged();
    }

    /** 只改选中态的局部刷新标记，避免重解缩略图 */
    private static final Object PAYLOAD_SELECTION = new Object();

    private final List<AlbumStore.Photo> items = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private Listener listener;

    /** 选择模式开关：开着才允许涂抹（单击才不会误开照片） */
    private boolean paintEnabled;
    private boolean painting;
    /** 本次涂抹是加选还是减选：起笔那格决定整笔方向（落在已选图上 = 这笔取消选择） */
    private boolean paintSelecting;
    private boolean paintModeDecided;
    /** 本笔的锚点（按下的那张）：一维序列上 锚点↔手指 的整段都是操作对象 */
    private long anchorId = -1;
    /** 本笔碰过的格子（只增不减）＋ 其中当前处于「本笔方向」状态的：
     区间收进来的翻回来，甩出区间的翻回去——正反反复拖由区间统一裁决 */
    private final Set<Long> strokeTouched = new HashSet<>();
    private final Set<Long> strokeApplied = new HashSet<>();
    private float downX;
    private float downY;
    /** 本次涂抹上一次涂到的格子，防止在同格上来回抖动反复切换 */
    private long lastPaintId = -1;
    private int touchSlop;

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setPaintEnabled(boolean on) {
        paintEnabled = on;
        if (!on) {
            painting = false;
        }
    }

    /** 换一批数据（每次刷新都清空选择：删除后、从调色模式回来后都是新状态） */
    void submit(List<AlbumStore.Photo> data) {
        items.clear();
        items.addAll(data);
        selected.clear();
        notifyDataSetChanged();
    }

    void clearSelection() {
        if (selected.isEmpty()) {
            return;
        }
        selected.clear();
        notifyDataSetChanged();
    }

    void toggle(long id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        int idx = indexOf(id);
        if (idx >= 0) {
            notifyItemChanged(idx, PAYLOAD_SELECTION);
        }
    }

    int selectedCount() {
        return selected.size();
    }

    int totalCount() {
        return items.size();
    }

    /** 选中的照片（按列表顺序），删除前一次性快照 */
    List<AlbumStore.Photo> selectedPhotos() {
        List<AlbumStore.Photo> out = new ArrayList<>();
        for (AlbumStore.Photo photo : items) {
            if (selected.contains(photo.id)) {
                out.add(photo);
            }
        }
        return out;
    }

    void shutdown() {
        thumbs.shutdownNow();
    }

    private int indexOf(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        touchSlop = ViewConfiguration.get(parent.getContext()).getScaledTouchSlop();
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        AlbumStore.Photo photo = items.get(position);
        holder.photoId = photo.id;
        holder.ring.setVisibility(selected.contains(photo.id) ? View.VISIBLE : View.GONE);
        Bitmap cached = AlbumStore.cachedThumb(photo.uri, 400);
        if (cached != null) {
            // 缓存命中：直接上屏，不闪白格（这就是「退出再进重新加载」的解法）
            holder.image.setTag(photo.id);
            holder.image.setImageBitmap(cached);
        } else {
            holder.image.setImageDrawable(null);
            holder.image.setTag(photo.id);
            thumbs.execute(() -> {
                Bitmap bmp = AlbumStore.thumb(holder.image.getContext(), photo.uri, 400);
                final Bitmap out = bmp;
                holder.image.post(() -> {
                    Object tag = holder.image.getTag();
                    if (tag instanceof Long && (Long) tag == photo.id) {
                        holder.image.setImageBitmap(out);
                    }
                });
            });
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpen(photo, Ui.rectOnScreen(v));
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener == null) {
                return false;
            }
            listener.onPick(photo);
            return true;
        });
        bindPaint(holder);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            // 只是选中态变了：画个圈就完事，别把缩略图重新解一遍
            AlbumStore.Photo photo = items.get(position);
            holder.photoId = photo.id;
            holder.ring.setVisibility(selected.contains(photo.id)
                    ? View.VISIBLE : View.GONE);
            return;
        }
        onBindViewHolder(holder, position);
    }

    /**
     * 涂抹：手指按住滑过哪格就勾哪格。
     *
     * <p>事件始终发给按下的那一格（它才是 touch target），所以坐标要从该格的局部系
     * 换算回 RecyclerView 再 {@code findChildViewUnder}，才能找到手指正压着的兄弟格。
     */
    private void bindPaint(VH holder) {
        holder.itemView.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    painting = false;
                    paintModeDecided = false;
                    downX = event.getX();
                    downY = event.getY();
                    lastPaintId = -1;
                    anchorId = holder.photoId;
                    strokeTouched.clear();
                    strokeApplied.clear();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!paintEnabled) {
                        break;
                    }
                    if (!painting) {
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (dx * dx + dy * dy > (float) touchSlop * touchSlop) {
                            painting = true;
                            // 起涂了就掐掉这一格挂起的长按，否则拖到一半它会当成
                            // 「长按进选择模式」把刚涂上的格子又翻回去
                            v.cancelLongPress();
                            // 起涂之后别让 RecyclerView 把手势抢去当滚动
                            if (v.getParent() != null) {
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }
                    }
                    if (painting) {
                        paintAt(v, event.getX(), event.getY());
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (painting) {
                        painting = false;
                        if (listener != null) {
                            listener.onSelectionChanged();
                        }
                        // 吞掉这次抬起：这是一次涂抹，不是单击
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /**
     * 一维范围涂抹：锚点（按下的那张）到当前手指所在照片，整段按本笔方向翻转。
     *
     * <p>二维网格只是排布，语义按序列走——斜着从左上拖到右下 = 锚点到终点之间
     * 全部选中，不管几何路径压没压到中间那些格子。往回拖区间收缩，本笔涂过又
     * 落回区间外的就地撤销（折返点那张自动含在区间裁决里，不用单独处理）。
     */
    private void paintAt(View target, float x, float y) {
        if (!(target.getParent() instanceof RecyclerView)) {
            return;
        }
        RecyclerView rv = (RecyclerView) target.getParent();
        View under = rv.findChildViewUnder(target.getLeft() + x, target.getTop() + y);
        if (under == null) {
            return;
        }
        RecyclerView.ViewHolder holder = rv.getChildViewHolder(under);
        if (!(holder instanceof VH)) {
            return;
        }
        long id = ((VH) holder).photoId;
        if (id == lastPaintId) {
            return;
        }
        lastPaintId = id;
        int anchorPos = indexOf(anchorId);
        int curPos = indexOf(id);
        if (anchorPos < 0 || curPos < 0) {
            return;
        }
        if (!paintModeDecided) {
            // 起笔那格决定整笔方向：落在已选图上 = 这笔取消，落在未选图上 = 这笔加选。
            // 方向全程不翻转，区间内外的裁决都用它。
            paintModeDecided = true;
            paintSelecting = !selected.contains(anchorId);
        }
        int lo = Math.min(anchorPos, curPos);
        int hi = Math.max(anchorPos, curPos);
        boolean changed = false;
        // 区间内：本笔没动过的按方向涂；被回拖撤销过的翻回来
        for (int p = lo; p <= hi; p++) {
            long cid = items.get(p).id;
            if (strokeApplied.contains(cid)) {
                continue;
            }
            boolean ok = paintSelecting ? selected.add(cid) : selected.remove(cid);
            if (ok) {
                strokeTouched.add(cid);
                strokeApplied.add(cid);
                notifyItemChanged(p, PAYLOAD_SELECTION);
                changed = true;
            }
        }
        // 区间外：本笔涂过但现在甩出去的（回拖收缩）→ 撤销。只碰本笔动过的，
        // 之前就选好的不在 strokeTouched 里，天然不受影响
        if (!strokeApplied.isEmpty()) {
            Iterator<Long> it = strokeApplied.iterator();
            while (it.hasNext()) {
                long cid = it.next();
                int p = indexOf(cid);
                if (p >= lo && p <= hi) {
                    continue;
                }
                it.remove();
                boolean ok = paintSelecting ? selected.remove(cid) : selected.add(cid);
                if (ok) {
                    notifyItemChanged(p, PAYLOAD_SELECTION);
                    changed = true;
                }
            }
        }
        if (changed && listener != null) {
            listener.onSelectionChanged();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView image;
        final View ring;
        long photoId;

        VH(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.item_thumb);
            ring = itemView.findViewById(R.id.item_ring);
        }
    }
}
