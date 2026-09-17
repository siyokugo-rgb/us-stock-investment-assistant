package com.usstock.androidsmoke;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import compat.AndroidCoreSmokeLogic;
import java.util.Currency;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal framework-only Activity for Android Core Compatibility Smoke.
 * Java (not Kotlin) so this module can use AGP without {@code org.jetbrains.kotlin.android}
 * in the same Gradle build as the root {@code kotlin("jvm")} project.
 * No Compose / AppCompat / Material.
 *
 * PR35 RUNTIME DIAG: temporary instrumentation only. Does not change Iso4217AlphabeticCodes.
 */
public final class MainActivity extends Activity {
    private static final String BUILD_MARKER = "PR35 RUNTIME DIAG";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StringBuilder screen = new StringBuilder();
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
        screen.append("\n\n");

        appendAvailableCurrencyProbe(screen);
        screen.append('\n');

        try {
            String result = AndroidCoreSmokeLogic.run();
            screen.append("RESULT=[").append(result).append("]\n");
            screen.append("resultClass=")
                    .append(result == null ? "null" : result.getClass().getName())
                    .append('\n');
            screen.append("resultLength=")
                    .append(result == null ? -1 : result.length())
                    .append('\n');
        } catch (Throwable t) {
            screen.append("MainActivity caught Throwable\n");
            screen.append("throwableClass=").append(t.getClass().getName()).append('\n');
            screen.append("throwableMessage=")
                    .append(t.getMessage() == null ? "(no message)" : t.getMessage())
                    .append('\n');
            for (StackTraceElement el : t.getStackTrace()) {
                screen.append("  at ").append(el.toString()).append('\n');
            }
        }

        TextView textView = new TextView(this);
        textView.setText(screen.toString());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        textView.setPadding(48, 48, 48, 48);
        textView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(textView);
        setContentView(scroll);
    }

    /**
     * Device probe only: compare getAvailableCurrencies() membership vs getInstance("ABC").
     * Does not alter Iso4217AlphabeticCodes / TradingCurrencyEvidence contracts.
     */
    private static void appendAvailableCurrencyProbe(StringBuilder screen) {
        screen.append("CURRENCY_PROBE\n");
        try {
            Set<String> codes = new HashSet<>();
            for (Currency currency : Currency.getAvailableCurrencies()) {
                codes.add(currency.getCurrencyCode());
            }
            screen.append("AVAILABLE_SIZE=").append(codes.size()).append('\n');
            screen.append("AVAILABLE_USD=").append(codes.contains("USD")).append('\n');
            screen.append("AVAILABLE_ABC=").append(codes.contains("ABC")).append('\n');
        } catch (Throwable t) {
            screen.append("AVAILABLE_USD=error\n");
            screen.append("AVAILABLE_ABC=error\n");
            screen.append("availableCurrenciesError=")
                    .append(t.getClass().getName())
                    .append(": ")
                    .append(t.getMessage() == null ? "(no message)" : t.getMessage())
                    .append('\n');
        }

        try {
            Currency.getInstance("ABC");
            screen.append("GET_INSTANCE_ABC=accepted\n");
        } catch (IllegalArgumentException rejected) {
            screen.append("GET_INSTANCE_ABC=rejected\n");
        } catch (Throwable t) {
            screen.append("GET_INSTANCE_ABC=error:")
                    .append(t.getClass().getName())
                    .append('\n');
        }
    }
}
