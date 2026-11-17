package top.linyun1.dshpocket;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量偏好存储，不依赖 AndroidX。 */
public final class Prefs {

    public static final String DEFAULT_URL = "https://pocket.linyun1.top/";
    /** 手机上的固定入口：跳到当前 dsh web 的带 token 地址（端口 3081）。 */
    public static final String LOCAL_URL = "http://127.0.0.1:3081/";
    /** dsh web 本身监听的端口。 */
    public static final String LOCAL_DSH = "http://127.0.0.1:3080/";

    private static final String NAME = "tsugu_prefs";
    private static final String K_URL = "base_url";
    private static final String K_AUTO_LOGIN = "auto_login";
    private static final String K_DARK = "dark_web";
    private static final String K_FULLSCREEN = "fullscreen";
    private static final String K_KEEP_SCREEN = "keep_screen";
    private static final String K_DESKTOP_UA = "desktop_ua";
    private static final String K_TEXT_ZOOM = "text_zoom";
    private static final String K_PIN_PROMPTED = "pin_prompted";
    private static final String K_LOCAL_TOKEN = "local_token";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String url(Context c) {
        String v = sp(c).getString(K_URL, DEFAULT_URL);
        if (v == null || v.trim().isEmpty()) {
            return DEFAULT_URL;
        }
        v = v.trim();
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "https://" + v;
        }
        return v;
    }

    public static void setUrl(Context c, String v) {
        sp(c).edit().putString(K_URL, v).apply();
    }

    public static boolean autoLogin(Context c) {
        return sp(c).getBoolean(K_AUTO_LOGIN, true);
    }

    public static void setAutoLogin(Context c, boolean v) {
        sp(c).edit().putBoolean(K_AUTO_LOGIN, v).apply();
    }

    public static boolean darkWeb(Context c) {
        return sp(c).getBoolean(K_DARK, false);
    }

    public static void setDarkWeb(Context c, boolean v) {
        sp(c).edit().putBoolean(K_DARK, v).apply();
    }

    public static boolean fullscreen(Context c) {
        return sp(c).getBoolean(K_FULLSCREEN, false);
    }

    public static void setFullscreen(Context c, boolean v) {
        sp(c).edit().putBoolean(K_FULLSCREEN, v).apply();
    }

    public static boolean keepScreen(Context c) {
        return sp(c).getBoolean(K_KEEP_SCREEN, false);
    }

    public static void setKeepScreen(Context c, boolean v) {
        sp(c).edit().putBoolean(K_KEEP_SCREEN, v).apply();
    }

    public static boolean desktopUa(Context c) {
        return sp(c).getBoolean(K_DESKTOP_UA, false);
    }

    public static void setDesktopUa(Context c, boolean v) {
        sp(c).edit().putBoolean(K_DESKTOP_UA, v).apply();
    }

    /** 60..150，默认 100。 */
    public static int textZoom(Context c) {
        int v = sp(c).getInt(K_TEXT_ZOOM, 100);
        return Math.max(60, Math.min(150, v));
    }

    public static void setTextZoom(Context c, int v) {
        sp(c).edit().putInt(K_TEXT_ZOOM, Math.max(60, Math.min(150, v))).apply();
    }

    public static boolean pinPrompted(Context c) {
        return sp(c).getBoolean(K_PIN_PROMPTED, false);
    }

    public static void setPinPrompted(Context c, boolean v) {
        sp(c).edit().putBoolean(K_PIN_PROMPTED, v).apply();
    }

    /** 手机本地 dsh web 的访问 token（命令行打出来的 ?token=... 那一段）。 */
    public static String localToken(Context c) {
        String v = sp(c).getString(K_LOCAL_TOKEN, "");
        return v == null ? "" : v.trim();
    }

    public static void setLocalToken(Context c, String v) {
        sp(c).edit().putString(K_LOCAL_TOKEN, v == null ? "" : v.trim()).apply();
    }

    /** 本机地址：默认走固定入口 3081（自动带上当前 token）；手动填了 token 则直连 3080。 */
    public static String localUrl(Context c) {
        String token = localToken(c);
        return token.isEmpty() ? LOCAL_URL : LOCAL_DSH + "?token=" + token;
    }
}
