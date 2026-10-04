package org.offblink.rgbcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 三个通道的乘法调色，输出整张 JPEG。
 *
 * <p>数学上和网页端 canvas 的逐像素乘法是同一件事
 * （{@code out = in * (v/100)}），所以预览和存下来的图一致；
 * 网页端看的是缩小版预览，这里对的是原图，顺手按 EXIF 摆正方向。
 */
final class ChannelFilter {

    private ChannelFilter() {
    }

    /** @param r/g/b 各通道百分比，100 = 原样 */
    static byte[] apply(String path, int r, int g, int b) throws IOException {
        Bitmap src = decode(path);
        if (src == null) {
            throw new IOException("照片解码失败");
        }

        int deg = rotationDegrees(path);
        boolean swap = deg == 90 || deg == 270;
        Bitmap out = Bitmap.createBitmap(swap ? src.getHeight() : src.getWidth(),
                swap ? src.getWidth() : src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (deg != 0) {
            canvas.concat(rotationMatrix(deg, src.getWidth(), src.getHeight()));
        }

        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                r / 100f, 0, 0, 0, 0,
                0, g / 100f, 0, 0, 0,
                0, 0, b / 100f, 0, 0,
                0, 0, 0, 1, 0}));
        canvas.drawBitmap(src, 0, 0, paint);
        src.recycle();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!out.compress(Bitmap.CompressFormat.JPEG, 92, bos)) {
            out.recycle();
            throw new IOException("JPEG 编码失败");
        }
        out.recycle();
        return bos.toByteArray();
    }

    /** 解整图；内存吃紧（超大照片）时退一级采样，宁可小一点也不 OOM */
    private static Bitmap decode(String path) {
        try {
            return BitmapFactory.decodeFile(path);
        } catch (OutOfMemoryError e) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return BitmapFactory.decodeFile(path, o);
        }
    }

    /**
     * EXIF 方向 → 旋转角（0/90/180/270），网页预览和存图共用同一角度。
     * 只有旋转需要摆正；镜像类当作原样——相机和系统选图器不会给镜像图。
     */
    static int rotationDegrees(String path) {
        try (InputStream in = new FileInputStream(path)) {
            return rotationDegrees(in);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 同上，但流式读（缩略图走 content:// 没有文件路径） */
    static int rotationDegrees(InputStream in) {
        int orientation;
        try {
            ExifInterface exif = new ExifInterface(in);
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException e) {
            return 0;
        }
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    /**
     * 旋转矩阵 + 平移：Android 的 {@code rotate} 以原点为轴，旋转后内容会落到
     * 负坐标区，必须按角度平移回来，否则画出来是空的。
     */
    private static Matrix rotationMatrix(int deg, int w, int h) {
        Matrix m = new Matrix();
        m.setRotate(deg);
        switch (deg) {
            case 90:
                m.postTranslate(h, 0);
                break;
            case 180:
                m.postTranslate(w, h);
                break;
            case 270:
                m.postTranslate(0, w);
                break;
            default:
                break;
        }
        return m;
    }
}
