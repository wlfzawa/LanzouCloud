package com.lanzou.cloud.ui;

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
import com.lanzou.cloud.core.VolumeManifest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文件夹分享浏览：输入 https://xxx.lanzout.com/bXXXXXX 形式的链接后，
 * 列出文件夹内所有文件（每页 50 条），点击即可下载。
 */
public class ShareBrowseActivity extends BaseActivity {

    public static final String EXTRA_URL = "share_url";
    public static final String EXTRA_PWD = "share_pwd";

    private LanzouClient client;
    private AppPrefs prefs;

    private TextView titleText;
    private TextView infoText;
    private Button moreBtn;
    private Button allBtn;
    private final List<Models.ShareFile> items = new ArrayList<>();
    private ShareFileAdapter adapter;

    private LanzouClient.FolderRef ref;
    private int page = 0;
    private boolean hasMore = false;
    private boolean loading = false;
    private String url;
    private String pwd;

    /** 「全部下载」是否正在读取列表/解析地址 */
    private volatile boolean batchRunning = false;
    private volatile boolean batchCancel = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);

        titleText = findViewById(R.id.share_title);
        infoText = findViewById(R.id.share_info);
        moreBtn = findViewById(R.id.share_more_btn);
        allBtn = findViewById(R.id.share_all_btn);
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
        allBtn.setOnClickListener(v -> downloadAll());
        allBtn.setEnabled(false);
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
                    HistoryStore.get(ShareBrowseActivity.this).add(
                            url, pwd, first.name, HistoryStore.TYPE_FOLDER);
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
        refreshFooter();
    }

    /** 刷新底部统计与按钮状态 */
    private void refreshFooter() {
        infoText.setText(items.isEmpty()
                ? "该文件夹没有文件"
                : "共 " + items.size() + " 个文件" + (hasMore ? "，下面还有更多" : ""));
        moreBtn.setText(hasMore ? ("加载更多（第 " + (page + 1) + " 页）") : "没有更多了");
        moreBtn.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        moreBtn.setEnabled(hasMore);
        allBtn.setEnabled(!items.isEmpty() && !batchRunning);
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
        if (VolumeManifest.isManifest(f.name)) {
            new AlertDialog.Builder(this)
                    .setTitle(f.name)
                    .setMessage(meta + "\n\n这是分卷说明文件。\n\n"
                            + "「合并下载」会下载文件夹里的全部分卷，按顺序拼接成一个文件并校验。")
                    .setPositiveButton("合并下载", (d, w) -> openVolume(f))
                    .setNeutralButton("普通下载", (d, w) -> DownloadHelper.startFromShare(
                            this, client, prefs, shareUrl, ref.pwd, f.name))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
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

    // ================================================================ 分卷

    /** 读清单 → 补齐所有分页 → 核对分卷齐全 → 加入下载队列 */
    private void openVolume(final Models.ShareFile f) {
        if (ref == null) return;
        if (batchRunning) {
            toast("正在处理其它任务，请稍候");
            return;
        }
        final String shareUrl = ref.baseUrl + "/" + f.code;
        batchRunning = true;
        batchCancel = false;
        allBtn.setEnabled(false);

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("分卷下载");
        pd.setMessage("正在读取说明文件...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1);
        pd.setCancelable(true);
        pd.setOnCancelListener(d -> batchCancel = true);
        pd.show();

        new Thread(() -> {
            try {
                final VolumeManifest m = VolumeFlow.read(client, shareUrl, ref.pwd);
                runOnUiThread(() -> {
                    if (batchCancel) {
                        pd.dismiss();
                        batchRunning = false;
                        allBtn.setEnabled(!items.isEmpty());
                        return;
                    }
                    pd.setMessage("正在核对分卷...");
                    final List<Models.ShareFile> seed = new ArrayList<>(items);
                    final int startPage = page + 1;
                    final boolean more = hasMore;
                    new Thread(() -> collectAll(seed, startPage, more, pd,
                            (all, err) -> afterVolumeCollect(m, all, err))).start();
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    batchRunning = false;
                    allBtn.setEnabled(!items.isEmpty());
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    private void afterVolumeCollect(VolumeManifest m, List<Models.ShareFile> all, String err) {
        List<String> miss = VolumeFlow.missing(m, VolumeFlow.namesOfShare(all));
        if (!miss.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("分卷不完整")
                    .setMessage("说明文件要求 " + m.parts.size() + " 卷，当前文件夹里缺少 "
                            + miss.size() + " 个：\n" + preview(miss)
                            + "\n\n可以让分享者确认是否传完整。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String note = err == null || err.isEmpty() ? null
                : "读取列表时有分页失败（" + err + "），但清单所需的分卷已齐全。";
        VolumeFlow.start(this, prefs, m, VolumeFlow.fromShare(client, ref, all), note);
    }

    private static String preview(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 5; i++) {
            if (i > 0) sb.append('\n');
            sb.append("· ").append(list.get(i));
        }
        if (list.size() > 5) sb.append("\n… 共 ").append(list.size()).append(" 个");
        return sb.toString();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg == null ? "操作失败" : msg, Toast.LENGTH_LONG).show();
    }

    // ================================================================ 全部下载

    /** 把整个文件夹加入下载队列：先补齐所有分页，再逐个解析直链入队 */
    private void downloadAll() {
        if (ref == null) {
            toast("文件夹还没加载完成");
            return;
        }
        if (batchRunning) {
            toast("正在处理上一个批量任务，请稍候");
            return;
        }
        final List<Models.ShareFile> seed = new ArrayList<>(items);
        if (seed.isEmpty() && !hasMore) {
            toast("这个文件夹里没有文件");
            return;
        }

        batchRunning = true;
        batchCancel = false;
        allBtn.setEnabled(false);

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("全部下载");
        pd.setMessage("正在读取文件列表...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(1);
        pd.setProgress(0);
        pd.setCancelable(true);
        pd.setOnCancelListener(d -> batchCancel = true);
        pd.show();

        final int startPage = page + 1;
        final boolean more = hasMore;
        new Thread(() -> collectAll(seed, startPage, more, pd,
                (all, err) -> confirmBatch(all, err))).start();
    }

    /** 补齐分页后的回调（自定义接口，避免依赖 API 24+ 的 java.util.function） */
    private interface CollectCallback {
        void onDone(List<Models.ShareFile> all, String error);
    }

    /** 后台补齐剩余分页，按 code 去重后回到主线程确认 */
    private void collectAll(final List<Models.ShareFile> seed, int startPage,
                            boolean more, final ProgressDialog pd, final CollectCallback done) {
        final List<Models.ShareFile> all = new ArrayList<>();
        final Set<String> codes = new HashSet<>();
        for (Models.ShareFile f : seed) {
            if (codes.add(f.code)) all.add(f);
        }

        String error = null;
        int pg = startPage;
        int guard = 0;
        // 单页 50 条，40 页上限足够覆盖 2000 个文件，避免异常情况下死循环
        while (more && !batchCancel && guard++ < 40) {
            final int show = pg;
            runOnUiThread(() -> pd.setMessage("正在读取第 " + show + " 页..."));
            try {
                Models.FolderShare fs = client.listFolder(ref, pg);
                if (fs.files != null) {
                    for (Models.ShareFile f : fs.files) {
                        if (codes.add(f.code)) all.add(f);
                    }
                }
                more = fs.hasMore;
                pg = fs.page + 1;
                final int n = all.size();
                final boolean still = more;
                runOnUiThread(() -> pd.setMessage(
                        "已读取 " + n + " 个文件" + (still ? "，继续..." : "")));
                if (more) Thread.sleep(600);
            } catch (Exception e) {
                // 翻页被限频时保留已读到的部分，下面会提示
                error = e.getMessage();
                break;
            }
        }

        final String err = error;
        final boolean remain = more;
        final int lastPage = pg - 1;
        runOnUiThread(() -> {
            pd.dismiss();
            batchRunning = false;
            // 把补齐的分页合并进列表，用户能看到完整清单
            if (!batchCancel && all.size() > items.size()) {
                items.clear();
                items.addAll(all);
                adapter.notifyDataSetChanged();
                page = Math.max(page, lastPage);
                hasMore = remain;
            }
            refreshFooter();
            if (batchCancel) return;
            if (all.isEmpty()) {
                toast(err == null || err.isEmpty() ? "没有可下载的文件" : err);
                return;
            }
            done.onDone(all, err);
        });
    }

    private void confirmBatch(final List<Models.ShareFile> list, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(list.size()).append(" 个文件，全部加入下载队列？\n\n");
        sb.append("任务会依次排队下载，可以在「下载列表」查看进度。");
        if (note != null && !note.isEmpty()) {
            sb.append("\n\n注意：读取后续分页时出错（").append(note)
                    .append("），可能只包含前 ").append(list.size()).append(" 个文件。");
        }
        new AlertDialog.Builder(this)
                .setTitle("全部下载")
                .setMessage(sb.toString())
                .setPositiveButton("开始下载", (d, w) -> runBatch(list))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 逐个解析直链并入队；解析而非下载在主线程，界面不卡 */
    private void runBatch(final List<Models.ShareFile> list) {
        if (!DownloadHelper.ensureStorage(this, prefs)) return;

        batchRunning = true;
        batchCancel = false;

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("加入下载队列");
        pd.setMessage("准备中...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(list.size());
        pd.setProgress(0);
        pd.setCancelable(true);
        pd.setOnCancelListener(d -> batchCancel = true);
        pd.show();

        new Thread(() -> {
            int ok = 0;
            final List<String> failed = new ArrayList<>();
            final Set<String> used = new HashSet<>();

            for (int i = 0; i < list.size(); i++) {
                if (batchCancel) break;
                Models.ShareFile f = list.get(i);
                final int idx = i + 1;
                final String label = displayName(f);
                runOnUiThread(() -> {
                    pd.setMessage("正在解析 " + idx + "/" + list.size() + "：" + label);
                    pd.setProgress(idx - 1);
                });

                String shareUrl = ref.baseUrl + "/" + f.code;
                try {
                    Models.DownloadInfo info;
                    try {
                        info = client.directUrl(shareUrl, ref.pwd);
                    } catch (Exception first) {
                        if (ref.pwd == null || ref.pwd.isEmpty()) throw first;
                        // 文件夹里个别文件没设密码，带上文件夹密码反而被拒
                        info = client.directUrl(shareUrl, "");
                    }
                    String name = LanzouClient.isUsableFileName(info.name) ? info.name : f.name;
                    if (!LanzouClient.isUsableFileName(name)) name = "download.bin";
                    name = LanzouClient.localName(new File(name).getName());
                    if (name.isEmpty()) name = "download.bin";
                    name = uniqueInBatch(name, used);

                    DownloadHelper.Dest dest = DownloadHelper.resolveDest(this, prefs, name);
                    DownloadCenter.get(this).enqueue(name, info.durl, shareUrl, ref.pwd, dest);
                    ok++;
                } catch (Exception e) {
                    failed.add(label);
                }

                runOnUiThread(() -> pd.setProgress(idx));
                if (i + 1 < list.size() && !batchCancel) {
                    try {
                        // 与翻页同样的限频防护，太快会被服务端拒绝
                        Thread.sleep(400);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            final int okN = ok;
            final List<String> fails = failed;
            runOnUiThread(() -> {
                pd.dismiss();
                batchRunning = false;
                allBtn.setEnabled(!items.isEmpty());
                StringBuilder sb = new StringBuilder("已加入 ").append(okN).append(" 个下载任务");
                if (!fails.isEmpty()) {
                    sb.append("，失败 ").append(fails.size()).append(" 个：");
                    int show = Math.min(3, fails.size());
                    for (int i = 0; i < show; i++) {
                        if (i > 0) sb.append("、");
                        sb.append(fails.get(i));
                    }
                    if (fails.size() > show) sb.append(" 等");
                }
                toast(sb.toString());
            });
        }).start();
    }

    private static String displayName(Models.ShareFile f) {
        if (f.name != null && !f.name.isEmpty()) return f.name;
        return f.code == null ? "文件" : f.code;
    }

    /** 同一批任务里重名时自动加 (1)(2)，避免互相覆盖 */
    private static String uniqueInBatch(String name, Set<String> used) {
        if (used.add(name.toLowerCase(Locale.US))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        while (true) {
            String cand = base + "(" + i + ")" + ext;
            if (used.add(cand.toLowerCase(Locale.US))) return cand;
            i++;
        }
    }
}
