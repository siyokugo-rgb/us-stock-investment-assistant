package com.usstock.androidsmoke;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import compat.AndroidCoreSmokeLogic;

/**
 * PR35 FIX RUNTIME DIAG only.
 *
 * setContentView first so a hung/crashing {@link AndroidCoreSmokeLogic#run()} cannot leave
 * a blank Activity. Core still runs on the Android UI thread (same as PR #35 MainActivity),
 * deferred via {@link View#post(Runnable)} only so the initial frame can paint.
 */
public final class MainActivity extends Activity {
    private static final String BUILD_MARKER = "PR35 FIX RUNTIME DIAG";

    private TextView textView;
    private final StringBuilder screen = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        textView = new TextView(this);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        textView.setPadding(48, 48, 48, 48);
        textView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(textView);
        setContentView(scroll);

        screen.append(BUILD_MARKER).append('\n');
        screen.append("packageName=").append(getPackageName()).append('\n');
        screen.append("versionName=");
        try {
            screen.append(
                    getPackageManager()
                            .getPackageInfo(getPackageName(), 0)
                            .versionName);
        } catch (Throwable t) {
            screen.append("(unavailable: ").append(t.getClass().getName()).append(')');
        }
        screen.append('\n');
        screen.append("versionCode=");
        try {
            screen.append(
                    getPackageManager()
                            .getPackageInfo(getPackageName(), 0)
                            .versionCode);
        } catch (Throwable t) {
            screen.append("(unavailable: ").append(t.getClass().getName()).append(')');
        }
        screen.append('\n');
        screen.append("BEFORE_RUN\n");
        textView.setText(screen.toString());

        // Paint BEFORE_RUN first; then run core on the same UI thread as PR #35.
        textView.post(
                new Runnable() {
                    @Override
                    public void run() {
                        runSmokeOnUiThread();
                    }
                });
    }

    private void runSmokeOnUiThread() {
        try {
            String result = AndroidCoreSmokeLogic.run();
            screen.append("AFTER_RUN\n");
            screen.append("RESULT=[").append(result).append("]\n");
            screen.append("resultClass=")
                    .append(result == null ? "null" : result.getClass().getName())
                    .append('\n');
            screen.append("resultLength=")
                    .append(result == null ? -1 : result.length())
                    .append('\n');
        } catch (Throwable t) {
            screen.append("AFTER_RUN\n");
            screen.append("MainActivity caught Throwable\n");
            screen.append("throwableClass=").append(t.getClass().getName()).append('\n');
            screen.append("throwableMessage=")
                    .append(t.getMessage() == null ? "(no message)" : t.getMessage())
                    .append('\n');
            for (StackTraceElement el : t.getStackTrace()) {
                screen.append("  at ").append(el.toString()).append('\n');
            }
        }
        textView.setText(screen.toString());
    }
}
