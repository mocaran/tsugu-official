package top.linyun1.dshpocket;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * 通过 Termux 的 RUN_COMMAND 服务在手机本地执行脚本。
 * 需要：Termux 已安装、~/.termux/termux.properties 里 allow-external-apps=true、
 * 且本 App 已被授予 com.termux.permission.RUN_COMMAND。
 */
final class PhoneDsh {

    private PhoneDsh() {
    }

    static boolean isTermuxInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo("com.termux", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** 拉起 dsh web 并起 3081 固定入口。 */
    static boolean start(Context c) {
        return run(c, "nohup bash $HOME/all.sh >> $HOME/all.out 2>&1 &");
    }

    /** 关掉 dsh web、固定入口并释放 wake-lock。 */
    static boolean stop(Context c) {
        return run(c, "bash $HOME/stop.sh");
    }

    private static boolean run(Context c, String script) {
        if (!isTermuxInstalled(c)) {
            return false;
        }
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try {
            c.startService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
