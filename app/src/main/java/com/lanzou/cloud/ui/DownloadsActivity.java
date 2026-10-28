package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.MiniFileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 内建下载器界面：任务列表、进度、打开文件、删除记录 */
public class DownloadsActivity extends Activity {

    private DownloadCenter center;
    private ListView listView;
    private TaskAdapter adapter;
    private final Handler handler = new Handler();
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 600);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);

        center = DownloadCenter.get(this);
        listView = findViewById(R.id.download_list);
        Button backBtn = findViewById(R.id.downloads_back_btn);
        Button clearBtn = findViewById(R.id.downloads_clear_btn);

        backBtn.setOnClickListener(v -> finish());
        clearBtn.setOnClickListener(v -> {
            center.clearFinished();
            refresh();
            Toast.makeText(this, "已清空完成/失败记录", Toast.LENGTH_SHORT).show();
        });

        adapter = new TaskAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            DownloadCenter.Task t = adapter.getItem(position);
            if (t == null) return;
            if (t.status == DownloadCenter.DONE) {
                openFile(t.target);
            } else if (t.status == DownloadCenter.FAILED) {
                showFailed(t);
            }
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            DownloadCenter.Task t = adapter.getItem(position);
            if (t == null) return false;
            if (center.remove(t)) {
                refresh();
                Toast.makeText(this, "已删除记录", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "下载中，无法删除", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.postDelayed(refresher, 600);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void refresh() {
        List<DownloadCenter.Task> data = center.list();
        adapter.setData(data);
        adapter.notifyDataSetChanged();
    }

    private void showFailed(final DownloadCenter.Task t) {
        final File dbg = new File(t.target.getPath() + ".error.html");
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("下载失败")
                .setMessage(t.error == null || t.error.isEmpty() ? "未知错误" : t.error)
                .setPositiveButton("重试", (d, w) -> {
                    if (center.retry(t)) {
                        Toast.makeText(this, "已重新加入下载队列", Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                })
                .setNegativeButton("关闭", null);
        if (dbg.exists()) {
            b.setNeutralButton("查看诊断页", (d, w) -> openFile(dbg));
        }
        b.show();
    }

    private void openFile(File f) {
        if (f == null || !f.exists()) {
            Toast.makeText(this, "文件不存在：" + (f == null ? "" : f.getPath()), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            // 自研 provider 覆盖全部版本，API 9~34 统一走 content://，无需 API 24 分支
            Uri uri = MiniFileProvider.uriFor(this, f);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setDataAndType(uri, MiniFileProvider.guessMime(f.getName()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有应用能打开此文件\n路径：" + f.getPath(), Toast.LENGTH_LONG).show();
        }
    }

    private static String guessMime(String name) {
        return MiniFileProvider.guessMime(name);
    }

    private class TaskAdapter extends BaseAdapter {
        private List<DownloadCenter.Task> data = new ArrayList<>();

        void setData(List<DownloadCenter.Task> d) {
            this.data = d;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public DownloadCenter.Task getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_download, parent, false);
            }
            DownloadCenter.Task t = getItem(position);
            TextView name = convertView.findViewById(R.id.dl_name);
            TextView status = convertView.findViewById(R.id.dl_status);
            ProgressBar bar = convertView.findViewById(R.id.dl_progress);

            name.setText(t.name);
            int pct = t.percent();
            switch (t.status) {
                case DownloadCenter.WAITING:
                    status.setText("等待中…");
                    bar.setProgress(0);
                    bar.setIndeterminate(false);
                    break;
                case DownloadCenter.DOWNLOADING:
                    status.setText(pct >= 0 ? ("下载中 " + pct + "%") : "下载中…");
                    bar.setProgress(pct >= 0 ? pct : 0);
                    bar.setIndeterminate(pct < 0);
                    break;
                case DownloadCenter.DONE:
                    status.setText("完成 · 点击打开\n" + t.target.getPath());
                    bar.setProgress(100);
                    bar.setIndeterminate(false);
                    break;
                case DownloadCenter.FAILED:
                    status.setText("失败：" + t.error);
                    bar.setProgress(0);
                    bar.setIndeterminate(false);
                    break;
            }
            return convertView;
        }
    }
}
