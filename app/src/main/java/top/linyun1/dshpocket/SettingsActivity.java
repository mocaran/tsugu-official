package top.linyun1.dshpocket;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：地址、密码、显示、维护。 */
public class SettingsActivity extends Activity {

    private EditText etUrl;
    private EditText etPin;
    private EditText etLocalToken;
    private TextView tvPinState;
    private TextView tvZoom;
    private TextView tvLocalState;
    private SeekBar sbZoom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarsPadding();

        etUrl = findViewById(R.id.et_url);
        etPin = findViewById(R.id.et_pin);
        etLocalToken = findViewById(R.id.et_local_token);
        tvPinState = findViewById(R.id.tv_pin_state);
        tvLocalState = findViewById(R.id.tv_local_state);
        tvZoom = findViewById(R.id.tv_zoom);
        sbZoom = findViewById(R.id.sb_zoom);

        etUrl.setText(Prefs.url(this));
        etLocalToken.setText(Prefs.localToken(this));
        etPin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        CheckBox cbAuto = findViewById(R.id.cb_auto);
        cbAuto.setChecked(Prefs.autoLogin(this));
        cbAuto.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setAutoLogin(this, checked);
            MainActivity.needReload = true;
        });

        Switch swDark = findViewById(R.id.sw_dark);
        swDark.setChecked(Prefs.darkWeb(this));
        swDark.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setDarkWeb(this, checked);
            MainActivity.needReload = true;
        });

        Switch swFull = findViewById(R.id.sw_fullscreen);
        swFull.setChecked(Prefs.fullscreen(this));
        swFull.setOnCheckedChangeListener((b, checked) -> Prefs.setFullscreen(this, checked));

        Switch swKeep = findViewById(R.id.sw_keep_screen);
        swKeep.setChecked(Prefs.keepScreen(this));
        swKeep.setOnCheckedChangeListener((b, checked) -> Prefs.setKeepScreen(this, checked));

        Switch swDesktop = findViewById(R.id.sw_desktop);
        swDesktop.setChecked(Prefs.desktopUa(this));
        swDesktop.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setDesktopUa(this, checked);
            MainActivity.needReload = true;
        });

        sbZoom.setMax(90);
        sbZoom.setProgress(Prefs.textZoom(this) - 60);
        tvZoom.setText(Prefs.textZoom(this) + "%");
        sbZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                int zoom = value + 60;
                tvZoom.setText(zoom + "%");
                if (fromUser) {
                    Prefs.setTextZoom(SettingsActivity.this, zoom);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        findViewById(R.id.btn_done).setOnClickListener(v -> finish());

        findViewById(R.id.btn_url_reset).setOnClickListener(v -> {
            etUrl.setText(Prefs.DEFAULT_URL);
            Prefs.setUrl(this, Prefs.DEFAULT_URL);
            MainActivity.needReload = true;
        });

        findViewById(R.id.btn_url_local).setOnClickListener(v -> {
            String url = Prefs.localUrl(this);
            etUrl.setText(url);
            Prefs.setUrl(this, url);
            MainActivity.needReload = true;
            toast(getString(R.string.settings_url_local_set));
        });

        findViewById(R.id.btn_local_start).setOnClickListener(v -> {
            boolean ok = sendTermuxCommand("nohup bash $HOME/all.sh >> $HOME/all.out 2>&1 &");
            toast(getString(ok ? R.string.settings_local_ctl_sent : R.string.settings_local_ctl_denied));
            if (ok) {
                scheduleLocalStateChecks();
            }
        });

        findViewById(R.id.btn_local_stop).setOnClickListener(v -> {
            boolean ok = sendTermuxCommand("bash $HOME/stop.sh");
            toast(getString(ok ? R.string.settings_local_ctl_sent : R.string.settings_local_ctl_denied));
            if (ok) {
                scheduleLocalStateChecks();
            }
        });

        findViewById(R.id.btn_save_pin).setOnClickListener(v -> savePin());

        Button clearPin = findViewById(R.id.btn_clear_pin);
        clearPin.setOnClickListener(v -> {
            PinStore.clear(this);
            etPin.setText("");
            refreshPinState();
            MainActivity.needReload = true;
            toast(getString(R.string.pin_cleared));
        });

        findViewById(R.id.btn_clear_cookie).setOnClickListener(v -> {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
            MainActivity.needReload = true;
            toast(getString(R.string.settings_cookie_cleared));
        });

        findViewById(R.id.btn_reload).setOnClickListener(v -> {
            MainActivity.needReload = true;
            finish();
        });

        ((TextView) findViewById(R.id.tv_about)).setText(getString(R.string.about_text));
        refreshPinState();
        refreshLocalState();
    }

    /** 通过 Termux 的 RUN_COMMAND 服务在手机本地执行一条命令（需要在 Termux 里开启 allow-external-apps）。 */
    /** 启动/停止之后分几次探测：dsh web 要 20~40 秒才起来，单次探测会一直显示“未运行”。 */
    private void scheduleLocalStateChecks() {
        long[] delays = {1500L, 6000L, 12000L, 20000L, 30000L, 45000L};
        for (long d : delays) {
            tvLocalState.postDelayed(this::refreshLocalState, d);
        }
    }

    private boolean sendTermuxCommand(String script) {
        if (getPackageManager().getLaunchIntentForPackage("com.termux") == null) {
            toast(getString(R.string.settings_local_no_termux));
            return false;
        }
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try {
            startService(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 探测 3081（固定入口）与 3080（dsh web），判断本机 DSH 是否在跑。 */
    private void refreshLocalState() {
        tvLocalState.setText(R.string.settings_local_state_unknown);
        new Thread(() -> {
            boolean up = probe(3081) || probe(3080);
            runOnUiThread(() -> tvLocalState.setText(up
                    ? R.string.settings_local_state_running
                    : R.string.settings_local_state_stopped));
        }).start();
    }

    private boolean probe(int port) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + port + "/").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(800);
            c.setReadTimeout(800);
            int code = c.getResponseCode();
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** targetSdk 36 强制 edge-to-edge，手动留出状态栏 / 导航栏高度，否则标题会压在状态栏下面。 */
    private void applySystemBarsPadding() {
        final View content = findViewById(android.R.id.content);
        content.setBackgroundColor(getColor(R.color.page_bg));
        content.setOnApplyWindowInsetsListener((v, insets) -> {
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

    @Override
    protected void onPause() {
        super.onPause();
        String token = etLocalToken.getText().toString().trim();
        if (!token.equals(Prefs.localToken(this))) {
            Prefs.setLocalToken(this, token);
        }
        String url = etUrl.getText().toString().trim();
        if (!url.isEmpty() && !url.equals(Prefs.url(this))) {
            Prefs.setUrl(this, url);
            MainActivity.needReload = true;
        }
    }

    private void savePin() {
        String pin = etPin.getText().toString().trim();
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            url = Prefs.DEFAULT_URL;
        }
        Prefs.setUrl(this, url);

        if (pin.isEmpty()) {
            if (PinStore.has(this)) {
                MainActivity.needReload = true;
                finish();
            } else {
                toast(getString(R.string.pin_empty));
            }
            return;
        }
        if (pin.length() < 8) {
            toast(getString(R.string.pin_too_short));
            return;
        }
        if (PinStore.save(this, pin)) {
            etPin.setText("");
            Prefs.setAutoLogin(this, ((CheckBox) findViewById(R.id.cb_auto)).isChecked());
            refreshPinState();
            MainActivity.needReload = true;
            toast(getString(R.string.pin_saved));
        } else {
            toast(getString(R.string.pin_save_failed));
        }
    }

    private void refreshPinState() {
        if (PinStore.has(this)) {
            tvPinState.setText(R.string.settings_pin_saved_hint);
            findViewById(R.id.btn_clear_pin).setVisibility(View.VISIBLE);
        } else {
            tvPinState.setText(R.string.settings_pin_empty_hint);
            findViewById(R.id.btn_clear_pin).setVisibility(View.GONE);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
