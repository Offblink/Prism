package org.offblink.rgbcam;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本应用相册 {@code Pictures/Prism} 的唯一读写口。
 *
 * <p>拍摄模式的「相册入口」只列这个目录下的照片，和系统相册互不干扰；
 * API 29+ 靠 {@code RELATIVE_PATH} 过滤，26-28 靠 {@code DATA} 路径过滤。
 */
public final class AlbumStore {

    private static final String TAG = "AlbumStore";
    /** 相册目录名（Pictures/Prism） */
    public static final String ALBUM = "Prism";

    /** 相册里的一张照片 */
    public static final class Photo {
        public final long id;
        public final Uri uri;

        Photo(long id, Uri uri) {
            this.id = id;
            this.uri = uri;
        }
    }

    private AlbumStore() {
    }

    // ------------------------------------------------------------ 缩略图缓存

    /** uri → 解好的缩略图：进相册秒显，不每次重解（覆盖/删除时按 uri 失效） */
    private static final android.util.LruCache<String, Bitmap> THUMBS =
            new android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** 从缓存直接取（不解码）；没有返回 null */
    @Nullable
    public static Bitmap cachedThumb(Uri uri, int maxPx) {
        return THUMBS.get(uri + "@" + maxPx);
    }

    /** 记进缓存（PhotoAdapter 解完就放） */
    public static void cacheThumb(Uri uri, int maxPx, Bitmap bmp) {
        if (bmp != null) {
            THUMBS.put(uri + "@" + maxPx, bmp);
        }
    }

    /** 某张图变了（覆盖保存/删除）：把它所有尺寸的缓存都扔掉 */
    public static void invalidateThumb(Uri uri) {
        for (String k : THUMBS.snapshot().keySet()) {
            if (k.startsWith(uri.toString() + "@")) {
                THUMBS.remove(k);
            }
        }
    }

    /**
     * 读写系统相册要的那组权限（按版本换名）。API 33+ 只读图片也要
     * {@code READ_MEDIA_IMAGES}——实测没有它，连自己插进去的行都查不出来。
     */
    static String[] storagePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    static boolean hasStoragePermission(Context ctx) {
        for (String p : storagePermissions()) {
            if (ContextCompat.checkSelfPermission(ctx, p)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** 按时间生成照片文件名：{@code IMG_20261004_112233.jpg} */
    public static String newName() {
        return "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".jpg";
    }

    /** JPEG 字节 → 应用相册。失败返回 {@code null}。 */
    @Nullable
    public static Uri insertJpeg(Context ctx, byte[] jpeg, String displayName) {
        return insertStream(ctx, new ByteArrayInputStream(jpeg), displayName);
    }

    /** 本地文件 → 应用相册（流式搬运，不整块读进内存）。失败返回 {@code null}。 */
    @Nullable
    public static Uri insertFromFile(Context ctx, File file, String displayName) {
        try {
            return insertStream(ctx, new FileInputStream(file), displayName);
        } catch (IOException e) {
            Log.w(TAG, "insertFromFile: 打不开 " + file, e);
            return null;
        }
    }

    /**
     * 图片流 → 应用相册。API 29+ 走 MediaStore（自有条目，免存储权限），
     * 26-28 写公共目录再媒体扫描（此时已持有 WRITE_EXTERNAL_STORAGE）。
     */
    @Nullable
    public static Uri insertStream(Context ctx, InputStream in, String displayName) {
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            v.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri out = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (out == null) {
                Log.w(TAG, "insert: MediaStore 拒绝建条目");
                close(in);
                return null;
            }
            try (InputStream src = in; OutputStream os = cr.openOutputStream(out)) {
                if (os == null) {
                    throw new IOException("openOutputStream 返回 null");
                }
                copy(src, os);
            } catch (IOException e) {
                Log.w(TAG, "insert: 写入失败", e);
                cr.delete(out, null, null);
                return null;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(out, done, null, null);
            return out;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), ALBUM);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "insert: 建目录失败 " + dir);
            close(in);
            return null;
        }
        File f = new File(dir, displayName);
        try (InputStream src = in; OutputStream os = new java.io.FileOutputStream(f)) {
            copy(src, os);
        } catch (IOException e) {
            Log.w(TAG, "insert: 写文件失败", e);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        android.media.MediaScannerConnection.scanFile(ctx,
                new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        return Uri.fromFile(f);
    }

    /** 本应用相册里的照片，新 → 旧。 */
    public static List<Photo> list(Context ctx) {
        boolean modern = Build.VERSION.SDK_INT >= 29;
        String selection = modern
                ? MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
                : MediaStore.Images.Media.DATA + " LIKE ?";
        // RELATIVE_PATH 形如 "Pictures/Prism/"
        String[] args = modern
                ? new String[]{Environment.DIRECTORY_PICTURES + "/" + ALBUM + "%"}
                : new String[]{"%" + File.separator + "Pictures" + File.separator + ALBUM + "%"};
        return query(ctx, selection, args);
    }

    /**
     * 设备上的全部图片，新 → 旧：给相机页「调色」的挑图页用——自己的包装，
     * 点一下直接进调色，没有系统相册那步 Done 确认。
     */
    public static List<Photo> listAll(Context ctx) {
        return query(ctx, null, null);
    }

    /** 一个系统相册（bucket）：系统怎么分就怎么分，名字原样 */
    public static final class Bucket {
        public final String name;
        public final int count;

        Bucket(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    /** 全部 bucket，按数量降序（列全部图片时顺手分组，一次查询） */
    public static List<Bucket> buckets(Context ctx) {
        List<Bucket> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] cols = Build.VERSION.SDK_INT >= 29
                ? new String[]{MediaStore.MediaColumns.BUCKET_DISPLAY_NAME}
                : new String[]{MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME};
        // NULL bucket（根目录/无目录的图）统一显示成「(无相册)」，选中时走 IS NULL
        String none = ctx.getString(R.string.gallery_no_album);
        java.util.LinkedHashMap<String, Integer> map = new java.util.LinkedHashMap<>();
        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols, null, null, null)) {
            if (c == null) {
                return out;
            }
            int idx = c.getColumnIndexOrThrow(cols[0]);
            while (c.moveToNext()) {
                String name = c.isNull(idx) ? none : c.getString(idx);
                map.merge(name, 1, Integer::sum);
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "buckets: 查询失败 " + e);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : entries) {
            out.add(new Bucket(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 某个 bucket 里的图片，新 → 旧；「(无相册)」= bucket 名为 NULL 的那批 */
    public static List<Photo> listBucket(Context ctx, String bucketName) {
        boolean modern = Build.VERSION.SDK_INT >= 29;
        String col = modern
                ? MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
                : MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME;
        if (ctx.getString(R.string.gallery_no_album).equals(bucketName)) {
            return query(ctx, col + " IS NULL", null);
        }
        return query(ctx, col + " = ?", new String[]{bucketName});
    }

    private static List<Photo> query(Context ctx, String selection, String[] args) {
        List<Photo> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        boolean modern = Build.VERSION.SDK_INT >= 29;
        String idCol = MediaStore.Images.Media._ID;
        String[] cols = modern
                ? new String[]{idCol,
                        MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED}
                : new String[]{idCol,
                        MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATA};
        // DATE_TAKEN 可能为空，退回 DATE_ADDED（秒）兜底排序。
        // 注意列名走常量：真列是 datetaken（下划线那种写法会 SQLiteException）
        String order = "COALESCE(" + MediaStore.Images.Media.DATE_TAKEN + ", "
                + MediaStore.MediaColumns.DATE_ADDED + " * 1000) DESC";

        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols, selection, args, order)) {
            if (c == null) {
                return out;
            }
            int idIdx = c.getColumnIndexOrThrow(idCol);
            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        String.valueOf(id));
                out.add(new Photo(id, uri));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "list: 查询失败", e);
        }
        return out;
    }

    /** 最近一张（相册入口缩略图）。没有照片返回 {@code null}。 */
    @Nullable
    public static Uri latestUri(Context ctx) {
        List<Photo> photos = list(ctx);
        return photos.isEmpty() ? null : photos.get(0).uri;
    }

    /** 取一张缩略图（最长边约 {@code maxPx}，两遍流：先量尺寸再按需采样）。 */
    @Nullable
    public static Bitmap thumb(Context ctx, Uri uri, int maxPx) {
        Bitmap hit = cachedThumb(uri, maxPx);
        if (hit != null) {
            return hit;
        }
        ContentResolver cr = ctx.getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > maxPx) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) {
                AppLog.w(TAG, "解码失败(返回null) " + uri);
                return null;
            }
            // 相机照片大多带 EXIF 方向，不解方向缩略图会整张横躺
            int deg;
            try (InputStream in = cr.openInputStream(uri)) {
                deg = ChannelFilter.rotationDegrees(in);
            }
            if (deg == 0) {
                cacheThumb(uri, maxPx, bmp);
                return bmp;
            }
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(deg);
            Bitmap upright = Bitmap.createBitmap(bmp, 0, 0,
                    bmp.getWidth(), bmp.getHeight(), m, true);
            if (upright != bmp) {
                bmp.recycle();
            }
            cacheThumb(uri, maxPx, upright);
            return upright;
        } catch (Exception e) {
            Log.w(TAG, "thumb: 失败 " + uri, e);
            AppLog.w(TAG, "thumb 失败 " + uri + " : " + e);
            return null;
        }
    }

    /** 删除一张照片。已被别处删掉也算成功（幂等）。 */
    public static boolean delete(Context ctx, Photo photo) {
        ContentResolver cr = ctx.getContentResolver();
        invalidateThumb(photo.uri);
        try {
            if (cr.delete(photo.uri, null, null) > 0) {
                return true;
            }
            // MediaStore 没删掉（多半是老路径下的裸文件）：退回文件删除
            String path = pathOf(cr, photo.uri);
            return path == null || new File(path).delete() || !new File(path).exists();
        } catch (RuntimeException e) {
            Log.w(TAG, "delete: 失败 " + photo.uri, e);
            AppLog.w(TAG, "delete 失败 " + photo.uri + " : " + e);
            return false;
        }
    }

    @Nullable
    private static String pathOf(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{MediaStore.Images.Media.DATA},
                null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void copy(InputStream src, OutputStream dst) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = src.read(buf)) > 0) {
            dst.write(buf, 0, n);
        }
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
