package com.usstock.androidsmoke;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import compat.AndroidCoreSmokeLogic;

/**
 * Minimal framework-only Activity for Android Core Compatibility Smoke.
 * Java (not Kotlin) so this module can use AGP without {@code org.jetbrains.kotlin.android}
 * in the same Gradle build as the root {@code kotlin("jvm")} project.
 * No Compose / AppCompat / Material.
 */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String result = AndroidCoreSmokeLogic.run();
        TextView textView = new TextView(this);
        textView.setText(result);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        textView.setPadding(48, 48, 48, 48);
        textView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(textView);
        setContentView(scroll);
    }
}
