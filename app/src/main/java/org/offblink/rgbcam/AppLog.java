package org.offblink.rgbcam;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 环形内存日志：给「读不出老照片」这类只在用户机器上出现的问题取证用。
 *
 * <p>关键失败点（缩略图解码、编辑器取图、MediaStore 查询）都往这里写一份，
 * 长按快门打开日志页看/复制。只留最近 {@link #CAP} 条，不落盘。
 */
public final class AppLog {

    private static final int CAP = 500;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat F =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private AppLog() {
    }

    public static void w(String tag, String msg) {
        Log.w("Prism/" + tag, msg);
        add(tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e("Prism/" + tag, msg, t);
        add(tag, msg + " : " + t);
    }

    private static synchronized void add(String tag, String msg) {
        LINES.addLast(F.format(new Date()) + " [" + tag + "] " + msg);
        while (LINES.size() > CAP) {
            LINES.removeFirst();
        }
    }

    /** 全部日志，新 → 旧 */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
