package com.lanzou.cloud.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanzou.cloud.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 链接历史：打开/下载过的分享链接，重启后仍然保留 */
public class HistoryActivity extends BaseActivity {

    private HistoryStore store;
    private AppPrefs prefs;
    private ListView listView;
    private TextView emptyText;
    private final List<HistoryStore.Entry> items = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        store = HistoryStore.get(this);
        prefs = AppPrefs.get(this);
        listView = findViewById(R.id.history_list);
        emptyText = findViewById(R.id.history_empty);
        Button backBtn = findViewById(R.id.history_back_btn);
        Button clearBtn = findViewById(R.id.history_clear_btn);

        backBtn.setOnClickListener(v -> finish());
        clearBtn.setOnClickListener(v -> confirmClear());

        adapter = new Adapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> open(items.get(position)));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemMenu(items.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items.clear();
        items.addAll(store.list());
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void open(final HistoryStore.Entry e) {
        if (e == null) return;
        if (HistoryStore.TYPE_FOLDER.equals(e.type)) {
            Intent i = new Intent(this, ShareBrowseActivity.class);
            i.putExtra(ShareBrowseActivity.EXTRA_URL, e.url);
            i.putExtra(ShareBrowseActivity.EXTRA_PWD, e.pwd == null ? "" : e.pwd);
            startActivity(i);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(e.name == null || e.name.isEmpty() ? "分享文件" : e.name)
                .setMessage(e.url + (e.pwd == null || e.pwd.isEmpty() ? "" : "\n提取码：" + e.pwd))
                .setPositiveButton("下载", (d, w) -> DownloadHelper.startFromShare(
                        this, Session.get(this).getClient(), prefs, e.url, e.pwd, e.name))
                .setNeutralButton("复制链接", (d, w) -> {
                    CopyUtil.copy(this, e.url);
                    toast("已复制链接");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showItemMenu(final HistoryStore.Entry e) {
        if (e == null) return;
        final String[] options = {"复制链接", "删除这条记录"};
        new AlertDialog.Builder(this)
                .setTitle(e.name == null || e.name.isEmpty() ? e.url : e.name)
                .setItems(options, (d, w) -> {
                    if (w == 0) {
                        String text = e.url + (e.pwd == null || e.pwd.isEmpty() ? "" : "\n提取码：" + e.pwd);
                        CopyUtil.copy(this, text);
                        toast("已复制");
                    } else {
                        store.remove(e);
                        reload();
                        toast("已删除");
                    }
                })
                .show();
    }

    private void confirmClear() {
        if (items.isEmpty()) {
            toast("没有可清空的记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空历史")
                .setMessage("将删除全部 " + items.size() + " 条链接记录，确定继续？")
                .setPositiveButton("清空", (DialogInterface.OnClickListener) (d, w) -> {
                    store.clear();
                    reload();
                    toast("已清空");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private class Adapter extends BaseAdapter {
        private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public HistoryStore.Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_share, parent, false);
            }
            HistoryStore.Entry e = getItem(position);
            TextView name = convertView.findViewById(R.id.share_item_name);
            TextView meta = convertView.findViewById(R.id.share_item_meta);

            String title = e.name;
            if (title == null || title.trim().isEmpty()) {
                String code = ShareLinks.codeOf(e.url);
                title = code == null ? e.url : (HistoryStore.TYPE_FOLDER.equals(e.type)
                        ? "文件夹 " : "文件 ") + code;
            }
            name.setText(title);

            StringBuilder sb = new StringBuilder();
            sb.append(HistoryStore.TYPE_FOLDER.equals(e.type) ? "文件夹" : "文件");
            if (e.pwd != null && !e.pwd.isEmpty()) sb.append(" · 提取码 ").append(e.pwd);
            sb.append(" · ").append(fmt.format(new Date(e.time)));
            meta.setText(sb.toString());
            return convertView;
        }
    }
}
