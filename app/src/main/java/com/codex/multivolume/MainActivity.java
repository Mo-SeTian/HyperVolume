package com.codex.multivolume;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/** Minimal status and recovery page. */
public final class MainActivity extends Activity {
    private TextView status;
    private Switch enabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText(getString(com.codex.multivolume.R.string.app_name));
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(64)));

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        enabled = new Switch(this);
        enabled.setText(R.string.module_enabled);
        enabled.setTextSize(17);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            SafeState.setEnabled(this, checked);
            refresh();
        });
        root.addView(enabled, new LinearLayout.LayoutParams(-1, dp(56)));

        Button clear = new Button(this);
        clear.setText(R.string.clear_safe_mode);
        clear.setOnClickListener(v -> {
            SafeState.clearSafeMode(this);
            refresh();
        });
        root.addView(clear, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView hint = new TextView(this);
        hint.setText("模块只在目标系统组件中运行；发生异常时会自动停止介入，保留系统原始音量面板。\n\n目标组件：SystemUI 音量插件 + MiSound 原生多应用音量服务。\n横屏、折叠屏和大屏使用系统实际加载的资源布局。\n\n如果安全模式已开启，确认系统稳定后点击“清除安全模式”恢复。\n\n注意：目标 ROM 版本必须与随附 HyperOS 4 组件同代。");
        hint.setTextSize(14);
        hint.setPadding(0, dp(24), 0, 0);
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        Bundle state = SafeState.get(this);
        if (state == null) {
            status.setText(R.string.status_unknown);
            enabled.setOnCheckedChangeListener(null);
            enabled.setChecked(false);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                SafeState.setEnabled(this, checked);
                refresh();
            });
            return;
        }
        boolean isEnabled = state.getBoolean(SafeStateProvider.KEY_ENABLED, true);
        boolean safeMode = state.getBoolean(SafeStateProvider.KEY_SAFE_MODE, false);
        long lastActive = state.getLong(SafeStateProvider.KEY_LAST_ACTIVE, 0L);
        String last = lastActive == 0L ? "无" : DateFormat.getDateTimeInstance().format(new Date(lastActive));
        status.setText(getString(
                R.string.status_template,
                isEnabled ? getString(R.string.on) : getString(R.string.off),
                safeMode ? getString(R.string.yes) : getString(R.string.no),
                last));
        enabled.setOnCheckedChangeListener(null);
        enabled.setChecked(isEnabled);
        enabled.setOnCheckedChangeListener((button, checked) -> {
            SafeState.setEnabled(this, checked);
            refresh();
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
