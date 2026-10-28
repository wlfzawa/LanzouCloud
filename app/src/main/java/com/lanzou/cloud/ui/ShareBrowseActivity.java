package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.LanzouClient;
import com.lanzou.cloud.core.LanzouException;
import com.lanzou.cloud.core.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹分享浏览：输入 https://xxx.lanzout.com/bXXXXXX 形式的链接后，
 * 列出文件夹内所有文件（每页 50 条），点击即可下载。
 */
public class ShareBrowseActivity extends Activity {

    public static final String EXTRA_URL = "share_url";
    public static final String EXTRA_PWD = "share_pwd";

    private LanzouClient client;
    private AppPrefs prefs;

    private TextView titleText;
    private TextView infoText;
    private Button moreBtn;
    private final List<Models.ShareFile> items = new ArrayList<>();
    private ShareFileAdapter adapter;

    private LanzouClient.FolderRef ref;
    private int page = 0;
    private boolean hasMore = false;
    private boolean loading = false;
    private String url;
    private String pwd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);

        titleText = findViewById(R.id.share_title);
        infoText = findViewById(R.id.share_info);
        moreBtn = findViewById(R.id.share_more_btn);
        Button backBtn = findViewById(R.id.share_back_btn);
        Button downloadsBtn = findViewById(R.id.share_downloads_btn);
        ListView list = findViewById(R.id.share_list);

        client = Session.get(this).getClient();
        prefs = AppPrefs.get(this);

        adapter = new ShareFileAdapter(this, items);
        list.setAdapter(adapter);

        url = getIntent().getStringExtra(EXTRA_URL);
        pwd = getIntent().getStringExtra(EXTRA_PWD);
        if (pwd == null) pwd = "";

        backBtn.setOnClickListener(v -> finish());
        downloadsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, DownloadsActivity.class)));
        moreBtn.setOnClickListener(v -> loadPage(page + 1));
        list.setOnItemClickListener((parent, view, position, id) -> onFileClick(items.get(position)));

        open();
    }

    // ================================================================ 加载

    private void open() {
        final ProgressDialog pd = ProgressDialog.show(this, "加载", "正在打开文件夹...", true, false);
        new Thread(() -> {
            try {
                final LanzouClient.FolderRef r = client.openFolder(url, pwd);
                final Models.FolderShare first = client.listFolder(r, 1);
                runOnUiThread(() -> {
                    pd.dismiss();
                    ref = r;
                    titleText.setText(first.name);
                    applyPage(first, true);
                });
            } catch (final LanzouException e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    onLoadFailed(e);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    private void loadPage(final int pg) {
        if (ref == null || loading) return;
        loading = true;
        moreBtn.setEnabled(false);
        moreBtn.setText("正在加载...");
        new Thread(() -> {
            try {
                final Models.FolderShare fs = client.listFolder(ref, pg);
                runOnUiThread(() -> {
                    loading = false;
                    moreBtn.setEnabled(true);
                    applyPage(fs, false);
                });
            } catch (final LanzouException e) {
                runOnUiThread(() -> {
                    loading = false;
                    moreBtn.setEnabled(true);
                    moreBtn.setText("加载更多");
                    toast(e.getMessage());
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    loading = false;
                    moreBtn.setEnabled(true);
                    moreBtn.setText("加载更多");
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    private void applyPage(Models.FolderShare fs, boolean first) {
        if (first) {
            items.clear();
            page = 0;
        }
        if (fs.files != null) items.addAll(fs.files);
        page = fs.page;
        hasMore = fs.hasMore;
        adapter.notifyDataSetChanged();

        String tip;
        if (items.isEmpty()) {
            tip = "该文件夹没有文件";
        } else {
            tip = "共 " + items.size() + " 个文件" + (hasMore ? "，下面还有更多" : "");
        }
        infoText.setText(tip);
        moreBtn.setText(hasMore ? ("加载更多（第 " + (page + 1) + " 页）") : "没有更多了");
        moreBtn.setVisibility(hasMore || items.isEmpty() ? View.VISIBLE : View.GONE);
        moreBtn.setEnabled(hasMore);
    }

    private void onLoadFailed(LanzouException e) {
        if (e.code == LanzouException.PASSWORD_ERROR || e.code == LanzouException.LACK_PASSWORD) {
            askPassword(e.getMessage());
            return;
        }
        infoText.setText("加载失败");
        new AlertDialog.Builder(this)
                .setTitle("打开失败")
                .setMessage(e.getMessage() == null ? "未知错误" : e.getMessage())
                .setPositiveButton("关闭", (d, w) -> finish())
                .setNegativeButton("重试", (d, w) -> open())
                .show();
    }

    /** 密码错误时重新输入 */
    private void askPassword(String msg) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("提取码");
        input.setText(pwd);
        new AlertDialog.Builder(this)
                .setTitle("需要提取码")
                .setMessage(msg == null || msg.isEmpty() ? "请输入该文件夹的提取码" : msg)
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    pwd = input.getText().toString().trim();
                    open();
                })
                .setNegativeButton("取消", (d, w) -> finish())
                .show();
    }

    // ================================================================ 下载

    private void onFileClick(final Models.ShareFile f) {
        if (ref == null) return;
        final String shareUrl = ref.baseUrl + "/" + f.code;
        String meta = (f.size == null ? "" : f.size)
                + (f.time == null || f.time.isEmpty() ? "" : "  " + f.time);
        new AlertDialog.Builder(this)
                .setTitle(f.name == null || f.name.isEmpty() ? f.code : f.name)
                .setMessage(meta)
                .setPositiveButton("下载", (d, w) -> DownloadHelper.startFromShare(
                        this, client, prefs, shareUrl, ref.pwd, f.name))
                .setNeutralButton("复制链接", (d, w) -> {
                    CopyUtil.copy(this, shareUrl);
                    toast("链接已复制：" + shareUrl);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg == null ? "操作失败" : msg, Toast.LENGTH_LONG).show();
    }
}
