package top.linyun1.dshpocket;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 主界面：原生标题栏 + 抽屉 + WebView。 */
public class MainActivity extends Activity {

    /** 设置页改动后置位，回到前台时重载网页。 */
    public static boolean needReload = false;

    private static final int REQ_FILE = 1001;
    private static final int REQ_MEDIA_PERM = 1002;
    private static final int REQ_STORAGE = 1003;

    private WebView web;
    private ProgressBar progress;
    private View errorOverlay;
    private TextView errorDetail;

    private float swipeDownX = 0f;
    private float swipeDownY = 0f;
    private boolean swipeTracking = false;
    private boolean swipeFired = false;
    private boolean swipeFromEdge = false;
    private long swipeDownTime = 0L;
    private volatile boolean sidebarOpen = false;
    /** 最近一次横滑触发的时间：短时间内拦掉系统返回，避免收起侧边栏时又顺带返回一页。 */
    private long swipeFiredAt = 0L;
    /** 最近一次“从屏幕边缘按下”的时间：边缘手势常被系统抢走，按下就先记一笔，返回一律压掉。 */
    private long edgeDownAt = 0L;

    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermission;

    private long lastBackTime = 0L;

    /** 当前页面地址（JS 回调线程不能直接调 WebView.getUrl）。 */
    private volatile String currentUrl = null;

    /** target=_blank 目标只处理一次（onPageStarted 和 shouldOverrideUrlLoading 会各来一次）。 */
    private boolean windowTargetHandled = false;

    /** 错误层是否正在显示：显示期间 onPageStarted/onPageFinished 不许把它藏掉（否则只剩浏览器自带的丑错误页）。 */
    private boolean errorVisible = false;
    /** 本次加载过程中是否已经报过错：只有“一次干净的加载”才允许把错误层收起来。 */
    private boolean loadHadError = false;

    /** 本机模式自动重试次数（dsh web 启动要 20~40 秒）。 */
    private int loopRetries = 0;
    /** 网络恢复回调，联网后自动重试。 */
    private android.net.ConnectivityManager.NetworkCallback netCallback = null;

    private final String bridgeTag = "TsuguBridge";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        errorOverlay = findViewById(R.id.error_overlay);
        errorDetail = findViewById(R.id.error_detail);

        applySystemBarsPadding();

        setupWebView();
        setupChrome();
        setupGestureExclusion();
        setupPredictiveBack();
        setupNetworkRetry();

        loadStart(Prefs.url(this));
        applyPrefs(false);
        maybeAskPin();
    }

    // ---------------------------------------------------------------- WebView

    /** targetSdk 36 强制 edge-to-edge，手动留出状态栏 / 导航栏高度。 */
    private void applySystemBarsPadding() {
        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSaveFormData(false);
        s.setTextZoom(Prefs.textZoom(this));
        if (Prefs.desktopUa(this)) {
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        }
        web.setBackgroundColor(getColor(R.color.page_bg));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), bridgeTag);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentUrl = url;
                progress.setVisibility(View.VISIBLE);
                if (!errorVisible) {
                    errorOverlay.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if (loadHadError) {
                    // 这一页其实是浏览器自带的错误页（WebView 里 URL 仍是被访问的地址），
                    // 不能再注入脚本，也不能把错误层收起来
                    return;
                }
                errorVisible = false;
                loopRetries = 0;
                errorOverlay.setVisibility(View.GONE);
                View retryBtn = findViewById(R.id.error_retry);
                if (retryBtn != null) {
                    retryBtn.setVisibility(View.VISIBLE);
                }
                injectAutofill();
                injectPinCapture();
                injectDarkMode();
                injectModeSwitch();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null || scheme.equals("http") || scheme.equals("https")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) {
                    return;
                }
                loadHadError = true;
                // 本机模式：dsh web 刚被拉起时需要 20~40 秒，这里自动重试而不是让人手点
                if (isLoopbackUrl(currentUrl) && loopRetries < 15) {
                    loopRetries++;
                    progress.setVisibility(View.GONE);
                    errorVisible = true;
                    TextView title = findViewById(R.id.error_title);
                    if (title != null) {
                        title.setText(R.string.error_waiting_local);
                    }
                    errorDetail.setText(R.string.error_waiting_local_hint);
                    View retry = findViewById(R.id.error_retry);
                    if (retry != null) {
                        retry.setVisibility(View.GONE);
                    }
                    errorOverlay.setVisibility(View.VISIBLE);
                    web.postDelayed(MainActivity.this::retryLoad, 3000);
                    return;
                }
                showError(describeError(error));
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                startDownload(url, contentDisposition, mimeType);
            }
        });
    }

    private void setupChrome() {
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                }
            }

            /**
             * target=_blank / window.open 的目标页。开多窗口支持后不来这里的话，
             * 这类链接会被直接丢弃（点了没反应），所以必须接管。
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                final WebView probe = new WebView(MainActivity.this);
                windowTargetHandled = false;
                probe.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        openNewTarget(req.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        openNewTarget(Uri.parse(url));
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handlePermissionRequest(request));
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                                                           GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }
        });
    }

    private void openNewTarget(Uri uri) {
        if (uri == null || windowTargetHandled) {
            return;
        }
        String scheme = uri.getScheme();
        if (scheme != null && !scheme.startsWith("http")) {
            return;
        }
        windowTargetHandled = true;
        String host = uri.getHost();
        String curHost = null;
        try {
            Uri cur = Uri.parse(currentUrl == null ? Prefs.url(this) : currentUrl);
            curHost = cur.getHost();
        } catch (Exception ignore) {
            // 解析失败就当作外部链接
        }
        if (host != null && curHost != null && host.equalsIgnoreCase(curHost)) {
            web.loadUrl(uri.toString());
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            web.loadUrl(uri.toString());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    List<Uri> list = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        Uri u = data.getClipData().getItemAt(i).getUri();
                        if (u != null) {
                            list.add(u);
                        }
                    }
                    if (!list.isEmpty()) {
                        result = list.toArray(new Uri[0]);
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    private void handlePermissionRequest(PermissionRequest request) {
        List<String> wanted = new ArrayList<>();
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.CAMERA);
            }
        }
        if (wanted.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        pendingPermission = request;
        requestPermissions(wanted.toArray(new String[0]), REQ_MEDIA_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MEDIA_PERM && pendingPermission != null) {
            boolean allGranted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                pendingPermission.grant(pendingPermission.getResources());
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    private void startDownload(String url, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            toast("请允许存储权限后重新点下载");
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) {
                req.setMimeType(mimeType);
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                req.addRequestHeader("Cookie", cookie);
            }
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                toast("已开始下载：" + name);
            }
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------- 自动登录 JS

    /** 页面里有 PIN 表单时自动填入并提交，随后 Cookie 会维持 30 天免登录。 */
    private void injectAutofill() {
        if (!Prefs.autoLogin(this)) {
            return;
        }
        String pin = PinStore.get(this);
        if (pin == null || pin.isEmpty()) {
            return;
        }
        String js = "(function(){try{"
                + "var f=document.querySelector('form[action*=\"pocket-login\"]');"
                + "if(!f)return 'nopin';"
                + "var el=f.querySelector('input[type=password]')||document.querySelector('#current-password');"
                + "if(!el)return 'nofield';"
                + "el.focus();el.value=" + JSONObject.quote(pin) + ";"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "if(f.requestSubmit){f.requestSubmit();}else{f.submit();}"
                + "return 'submitted';}catch(e){return 'err'+e;}})()";
        web.evaluateJavascript(js, null);
    }

    /** 用户手动输入密码登录时，把密码加密存下来，下次自动填。 */
    private void injectPinCapture() {
        String js = "(function(){try{"
                + "var f=document.querySelector('form[action*=\"pocket-login\"]');"
                + "if(!f||f.__tsuguHooked)return;"
                + "f.__tsuguHooked=1;"
                + "f.addEventListener('submit',function(){"
                + "var el=f.querySelector('input[type=password]');"
                + "if(el&&el.value&&window." + bridgeTag + "){window." + bridgeTag
                + ".onPinSubmitted(el.value);}},true);"
                + "}catch(e){}})()";
        web.evaluateJavascript(js, null);
    }

    private void injectDarkMode() {
        if (!Prefs.darkWeb(this)) {
            return;
        }
        String js = "(function(){try{"
                + "var id='tsugu-dark';if(document.getElementById(id))return;"
                + "var s=document.createElement('style');s.id=id;"
                + "s.textContent='html{filter:invert(1) hue-rotate(180deg);background:#fff}"
                + "img,video,canvas,svg,picture{filter:invert(1) hue-rotate(180deg)}';"
                + "document.documentElement.appendChild(s);}catch(e){}})()";
        web.evaluateJavascript(js, null);
    }

    /** 只允许同源页面通过桥接回报密码。 */
    private boolean isTrustedOrigin(String url) {
        if (url == null) {
            return false;
        }
        try {
            String base = Uri.parse(Prefs.url(this)).getHost();
            String host = Uri.parse(url).getHost();
            return base != null && host != null
                    && (host.equalsIgnoreCase(base) || host.endsWith("." + base));
        } catch (Exception e) {
            return false;
        }
    }

    public class Bridge {
        @android.webkit.JavascriptInterface
        public void onPinSubmitted(final String pin) {
            if (pin == null || pin.length() < 8 || pin.length() > 64) {
                return;
            }
            final String current = currentUrl;
            runOnUiThread(() -> {
                if (!isTrustedOrigin(current)) {
                    return;
                }
                if (PinStore.save(MainActivity.this, pin)) {
                    Prefs.setAutoLogin(MainActivity.this, true);
                }
            });
        }

        /** 由网页侧边栏里注入的「模式切换」按钮调用。 */
        @android.webkit.JavascriptInterface
        public void openModeSwitch() {
            final String current = currentUrl;
            runOnUiThread(() -> {
                if (!isTrustedOrigin(current)) {
                    return;
                }
                showModeSwitchDialog();
            });
        }
    }

    /** 电脑模式 / 本机模式的切换入口（网页侧边栏「更多」上面那一项）。 */
    private void showModeSwitchDialog() {
        final boolean local = isLoopbackUrl(Prefs.url(this));
        final String[] items = {
                (local ? "✓ " : "") + getString(R.string.mode_item_local),
                (local ? "" : "✓ ") + getString(R.string.mode_item_remote),
                getString(R.string.mode_item_start),
                getString(R.string.mode_item_stop),
                getString(R.string.mode_item_settings),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.mode_switch_title)
                .setItems(items, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) {
                        Prefs.setUrl(this, Prefs.localUrl(this));
                        needReload = true;
                        toast(getString(R.string.mode_switched_local));
                        loadStart(Prefs.url(this));
                    } else if (which == 1) {
                        Prefs.setUrl(this, Prefs.DEFAULT_URL);
                        needReload = true;
                        toast(getString(R.string.mode_switched_remote));
                        loadStart(Prefs.url(this));
                    } else if (which == 2) {
                        toast(getString(PhoneDsh.start(this)
                                ? R.string.settings_local_ctl_sent : R.string.settings_local_ctl_denied));
                    } else if (which == 3) {
                        toast(getString(PhoneDsh.stop(this)
                                ? R.string.settings_local_ctl_sent : R.string.settings_local_ctl_denied));
                    } else {
                        startActivity(new Intent(this, SettingsActivity.class));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 手势

    /** 网页侧边栏按钮的定位正则：展开态是“收起侧边栏”，收起态是“打开侧边栏”。 */
    private static final String JS_FIND_CLOSE =
            "var els=document.querySelectorAll('[aria-label],[title]');"
            + "var re=/收起侧边栏|收起右侧边栏|collapse sidebar/i;";

    private static final String JS_OPEN_SIDEBAR =
            "(function(){" + JS_FIND_CLOSE
            + "var o=/打开侧边栏|打开右侧边栏|open sidebar/i;"
            + "for(var i=0;i<els.length;i++){"
            + "var a=(els[i].getAttribute('aria-label')||'')+' '+(els[i].getAttribute('title')||'');"
            + "if(o.test(a)){els[i].click();return 'ok'}}return 'miss'})()";

    private static final String JS_CLOSE_SIDEBAR =
            "(function(){" + JS_FIND_CLOSE
            + "for(var i=0;i<els.length;i++){"
            + "var a=(els[i].getAttribute('aria-label')||'')+' '+(els[i].getAttribute('title')||'');"
            + "if(re.test(a)){els[i].click();return 'ok'}}return 'miss'})()";

    /** 侧边栏当前是否展开（靠“收起侧边栏”按钮是否存在判断）。 */
    private static final String JS_IS_SIDEBAR_OPEN =
            "(function(){" + JS_FIND_CLOSE
            + "for(var i=0;i<els.length;i++){"
            + "var a=(els[i].getAttribute('aria-label')||'')+' '+(els[i].getAttribute('title')||'');"
            + "if(re.test(a))return '1'}return '0'})()";

    private void openWebSidebar() {
        web.evaluateJavascript(JS_OPEN_SIDEBAR, value -> sidebarOpen = "ok".equals(unquote(value)));
    }

    /** 关侧边栏；网页里没有展开的侧边栏时执行 fallback。 */
    private void closeWebSidebarThen(Runnable fallback) {
        web.evaluateJavascript(JS_CLOSE_SIDEBAR, value -> {
            if ("ok".equals(unquote(value))) {
                sidebarOpen = false;
                return;
            }
            if (fallback != null) {
                fallback.run();
            }
        });
    }

    /**
     * 往网页侧边栏「更多」上面插一项「模式切换」。
     * 做法是克隆「更多」那一项（样式/图标跟着走），把文字换掉，点击转给 App 弹窗。
     * SPA 会重渲染，所以挂一个 MutationObserver 做兜底补插。
     */
    private static final String JS_INSERT_MODE_ITEM =
            "(function(){"
            + "if(document.querySelector('[data-tsugu-mode]')){return 'exists'}"
            + "function pick(){"
            + "var nodes=document.querySelectorAll('button,a,[role=\\\"button\\\"]');"
            + "for(var i=0;i<nodes.length;i++){"
            + "var t=(nodes[i].textContent||'').replace(/\\s+/g,'');"
            + "if(t==='\\u66f4\\u591a'){return nodes[i]}}"
            + "return null}"
            + "function inject(){"
            + "if(document.querySelector('[data-tsugu-mode]')){return}"
            + "var more=pick();"
            + "if(!more||!more.parentNode){return}"
            + "var clone=more.cloneNode(true);"
            + "clone.setAttribute('data-tsugu-mode','1');"
            + "clone.removeAttribute('aria-label');clone.removeAttribute('title');"
            + "var done=false;"
            + "var w=document.createTreeWalker(clone,NodeFilter.SHOW_TEXT,null);"
            + "while(w.nextNode()){var n=w.currentNode;"
            + "if(n.nodeValue&&n.nodeValue.replace(/\\s+/g,'')==='\\u66f4\\u591a'){n.nodeValue='\\u6a21\\u5f0f\\u5207\\u6362';done=true;break}}"
            + "if(!done){clone.textContent='\\u6a21\\u5f0f\\u5207\\u6362'}"
            + "clone.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();"
            + "try{window.TsuguBridge.openModeSwitch()}catch(err){}},true);"
            + "more.parentNode.insertBefore(clone,more)}"
            + "inject();"
            + "if(!window.__tsuguModeObserver){window.__tsuguModeObserver=1;"
            + "var timer=null;"
            + "new MutationObserver(function(){if(timer){return}"
            + "timer=setTimeout(function(){timer=null;inject()},400)})"
            + ".observe(document.body,{childList:true,subtree:true})}"
            + "return 'ok'})()";

    private void injectModeSwitch() {
        web.evaluateJavascript(JS_INSERT_MODE_ITEM, null);
    }

    /** evaluateJavascript 回传的是 JSON 字符串（带引号）。 */    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return "";
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 横向滑动 = 开关网页自己的侧边栏。
     * 右滑：先试着收起侧边栏；没有侧边栏时退回上一页（forward），再不行才打开侧边栏。
     * 左滑：只收起侧边栏，绝不再触发返回，避免误划出上一页。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeFired = false;
                swipeTracking = true;
                swipeFromEdge = ev.getX() <= dp(30f);
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeDownTime = System.currentTimeMillis();
                // 左右边缘按下就记一笔：边缘手势多半会被系统返回手势抢走，这里先把返回压住
                int width = web.getWidth() > 0 ? web.getWidth() : getResources().getDisplayMetrics().widthPixels;
                if (ev.getX() <= dp(30f) || ev.getX() >= width - dp(30f)) {
                    edgeDownAt = swipeDownTime;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swipeTracking || swipeFired) {
                    break;
                }
                float dx = ev.getX() - swipeDownX;
                float dy = ev.getY() - swipeDownY;
                if (Math.abs(dx) >= dp(48f)
                        && Math.abs(dx) > Math.abs(dy) * 1.3f
                        && Math.abs(dy) < dp(80f)) {
                    swipeFired = true;
                    swipeFiredAt = System.currentTimeMillis();
                    web.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    if (dx > 0) {
                        openWebSidebar();
                    } else {
                        closeWebSidebarThen(null);
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeTracking) {
                    swipeTracking = false;
                    boolean tapped = Math.abs(ev.getX() - swipeDownX) < dp(14f)
                            && Math.abs(ev.getY() - swipeDownY) < dp(14f);
                    long held = System.currentTimeMillis() - swipeDownTime;
                    if (swipeFromEdge && !swipeFired && tapped && held >= 500) {
                        // 原生抽屉已移除：长按左边缘进设置
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }
    // ---------------------------------------------------------------- 工具栏

    /** 把左右两条边缘条都声明为系统手势排除区，避免横滑被系统的“返回手势”抢走。 */
    private void setupGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        final View root = findViewById(R.id.root);
        root.post(() -> {
            int w = Math.round(dp(30f));
            int h = root.getHeight();
            int width = root.getWidth();
            java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>();
            rects.add(new android.graphics.Rect(0, 0, w, h));
            rects.add(new android.graphics.Rect(width - w, 0, width, h));
            root.setSystemGestureExclusionRects(rects);
        });
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showError(String detail) {
        progress.setVisibility(View.GONE);
        errorVisible = true;
        errorDetail.setText(detail);
        errorOverlay.setVisibility(View.VISIBLE);
        findViewById(R.id.error_retry).setOnClickListener(v -> retryLoad());
    }

    private void retryLoad() {
        errorVisible = false;
        errorOverlay.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        loadStart(Prefs.url(this));
    }

    /** 是否指向本机（127.0.0.1 / localhost），用于决定“等 dsh 起来”的自动重试。 */
    private boolean isLoopbackUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.contains("//127.0.0.1") || u.contains("//localhost") || u.contains("//10.0.2.2");
    }

    /** 所有主动发起的加载都从这里走：重置“本次加载是否报过错”的标记。 */
    private void loadStart(String url) {
        loadHadError = false;
        web.loadUrl(url);
    }

    /** 把 WebView 的错误码翻成人话，原始错误附在后面方便排查。 */
    private String describeError(WebResourceError error) {
        String raw = "\n\n" + error.getDescription() + "（" + error.getErrorCode() + "）";
        switch (error.getErrorCode()) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                return getString(R.string.error_name) + raw;
            case WebViewClient.ERROR_TIMEOUT:
                return getString(R.string.error_timeout) + raw;
            case WebViewClient.ERROR_CONNECT:
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
                return getString(R.string.error_refused) + raw;
            case -106: // net::ERR_INTERNET_DISCONNECTED（WebViewClient 没有导出这个常量）
                return getString(R.string.error_no_network) + raw;
            default:
                return getString(R.string.error_hint) + raw;
        }
    }

    /**
     * 冷启动时蜂窝网可能还没就绪，WebView 会先报一次“连不上”。
     * 网络一恢复就自动重试，用户不用手动点。
     */
    private void setupNetworkRetry() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            netCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    runOnUiThread(() -> {
                        if (errorVisible) {
                            toast(getString(R.string.error_retrying));
                            retryLoad();
                        }
                    });
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception e) {
            netCallback = null;
        }
    }

    // ------------------------------------------------------------ 偏好与生命周期

    private void applyPrefs(boolean reload) {
        applyFullscreen(Prefs.fullscreen(this));
        applyKeepScreen(Prefs.keepScreen(this));
        WebSettings s = web.getSettings();
        s.setTextZoom(Prefs.textZoom(this));
        boolean desktopUa = Prefs.desktopUa(this);
        if (desktopUa) {
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
        }
        if (reload) {
            web.reload();
        }
    }

    private void applyFullscreen(boolean full) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (full) {
                    controller.hide(android.view.WindowInsets.Type.statusBars());
                } else {
                    controller.show(android.view.WindowInsets.Type.statusBars());
                }
            }
            return;
        }
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (full) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void applyKeepScreen(boolean keep) {
        if (keep) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void maybeAskPin() {
        if (PinStore.has(this) || Prefs.pinPrompted(this)) {
            return;
        }
        Prefs.setPinPrompted(this, true);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_pin, null);
        EditText input = content.findViewById(R.id.pin_input);
        CheckBox remember = content.findViewById(R.id.pin_remember);
        new AlertDialog.Builder(this)
                .setTitle(R.string.pin_dialog_title)
                .setView(content)
                .setPositiveButton(R.string.pin_save, (d, w) -> {
                    String pin = input.getText().toString().trim();
                    if (pin.length() < 8) {
                        toast(getString(R.string.pin_too_short));
                        return;
                    }
                    Prefs.setAutoLogin(this, remember.isChecked());
                    if (PinStore.save(this, pin)) {
                        toast(getString(R.string.pin_saved));
                        if (remember.isChecked()) {
                            web.reload();
                        }
                    } else {
                        toast(getString(R.string.pin_save_failed));
                    }
                })
                .setNegativeButton(R.string.pin_skip, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPrefs(false);
        if (needReload) {
            needReload = false;
            errorVisible = false;
            errorOverlay.setVisibility(View.GONE);
            loadStart(Prefs.url(this));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (netCallback != null) {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(netCallback);
                }
            } catch (Exception ignore) {
                // 已经注销过或系统不支持，忽略
            }
            netCallback = null;
        }
        super.onDestroy();
    }

    /** Android 13+ 用预测性返回：不注册的话 onBackPressed 根本不会被调用，返回会直接退出。 */
    private void setupPredictiveBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    /** Android 13+ 的预测性返回走 OnBackInvokedCallback，onBackPressed 不再被调用，所以两边都接上。 */
    private void handleBack() {
        // 刚划过一手、或刚在屏幕边缘按下的短时间内不响应返回，
        // 避免“收起侧边栏”顺带返回一页、也避免右边缘横滑被系统当返回带出「开始」页
        long now0 = System.currentTimeMillis();
        if (now0 - swipeFiredAt < 700 || now0 - edgeDownAt < 1200) {
            return;
        }
        // 先问网页：侧边栏是不是开着。开着就只收起它，不返回，避免划出上一页。
        if (sidebarOpen) {
            sidebarOpen = false;
            closeWebSidebarThen(null);
            return;
        }
        web.evaluateJavascript(JS_IS_SIDEBAR_OPEN, value -> {
            if ("1".equals(unquote(value))) {
                sidebarOpen = false;
                closeWebSidebarThen(null);
                return;
            }
            goBackOrExit();
        });
    }

    private void goBackOrExit() {
        // 刻意不再走 WebView 历史：返回上一页就是「开始」页，用户明确不要它出现。
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000) {
            finish();
        } else {
            lastBackTime = now;
            toast(getString(R.string.exit_hint));
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
