package com.lanzou.cloud.ui;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.LanzouClient;
import com.lanzou.cloud.core.LanzouException;
import com.lanzou.cloud.core.Models;
import com.lanzou.cloud.core.VolumeDownloader;
import com.lanzou.cloud.core.VolumeManifest;
import com.lanzou.cloud.core.VolumeUploader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/** 主界面：文件浏览、上传、下载、分享链接下载、新建文件夹 */
public class MainActivity extends BaseActivity {

    private static final int REQ_UPLOAD = 1001;

    private Session session;
    private LanzouClient client;
    private AppPrefs prefs;

    private ListView fileList;
    private TextView pathText;
    private FileListAdapter adapter;

    private long currentFolderId = -1;
    private final Stack<Long> idStack = new Stack<>();
    private final Stack<String> nameStack = new Stack<>();
    private boolean ready;
    /** 当前目录的文件列表，分卷下载时用来按名字找齐所有卷 */
    private volatile List<Models.FileItem> currentFiles = new ArrayList<>();
    /** 下次选完文件强制分卷 */
    private boolean forceVolume;
    /** 分卷上传是否被用户取消 */
    private volatile boolean uploadCancelled;

    private final Handler handler = new Handler();
    private final Runnable clipCheck = new Runnable() {
        @Override
        public void run() {
            checkClipboard(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fileList = findViewById(R.id.file_list);
        pathText = findViewById(R.id.path_text);
        Button backBtn = findViewById(R.id.back_btn);
        Button downloadsBtn = findViewById(R.id.downloads_btn);
        Button settingsBtn = findViewById(R.id.settings_btn);
        Button uploadBtn = findViewById(R.id.upload_btn);
        Button mkdirBtn = findViewById(R.id.mkdir_btn);
        Button linkBtn = findViewById(R.id.link_btn);
        Button refreshBtn = findViewById(R.id.refresh_btn);

        session = Session.get(this);
        client = session.getClient();
        prefs = AppPrefs.get(this);

        backBtn.setOnClickListener(v -> goBack());
        downloadsBtn.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, DownloadsActivity.class)));
        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));
        uploadBtn.setOnClickListener(v -> pickAndUpload(false));
        // 长按「上传」：选完文件强制分卷（适合想控制单卷大小的场景）
        uploadBtn.setOnLongClickListener(v -> {
            pickAndUpload(true);
            return true;
        });
        mkdirBtn.setOnClickListener(v -> mkdir());
        linkBtn.setOnClickListener(v -> downloadByLink());
        // 长按「链接下载」直接读剪贴板，方便只复制了链接的场景
        linkBtn.setOnLongClickListener(v -> {
            checkClipboard(true);
            return true;
        });
        refreshBtn.setOnClickListener(v -> loadDir(currentFolderId));

        fileList.setOnItemClickListener((parent, view, position, id) -> {
            Object o = adapter.getItem(position);
            if (o instanceof Models.FolderItem) {
                enterFolder((Models.FolderItem) o);
            } else if (o instanceof Models.FileItem) {
                onFileClick((Models.FileItem) o);
            }
        });

        // 自定义下载目录位于公共存储时，Android 6.0+ 需要运行时存储权限
        ensureStoragePermissionIfNeeded();
        init();
    }

    /** 启动时若自定义目录需要存储权限，主动弹窗请求一次 */
    private void ensureStoragePermissionIfNeeded() {
        if (prefs.getDownloadTree() != null) return;
        String custom = prefs.getDownloadDir();
        if (custom == null || custom.trim().isEmpty()) return;
        if (!PermissionHelper.needsStorage(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("下载目录位于公共存储，写入需要「存储」权限。现在去授权？\n\n拒绝后下载会自动改用默认目录。")
                .setPositiveButton("去授权", (d, w) -> PermissionHelper.requestStorage(this))
                .setNegativeButton("暂不", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ready && prefs.isClipboardAuto()) {
            // 稍等一下，等窗口拿到焦点（Android 10+ 只有前台才能读剪贴板）
            handler.postDelayed(clipCheck, 600);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clipCheck);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PermissionHelper.REQ_STORAGE) return;
        if (PermissionHelper.isGranted(grantResults)) {
            toast("存储权限已授予，可以写入该目录");
        } else if (!PermissionHelper.canAskAgain(this)) {
            toast("已拒绝且不再询问：公共目录无法写入，请改用默认目录或到系统设置手动开启");
        } else {
            toast("未授予存储权限：下载会自动改用默认目录");
        }
    }

    // ================================================================ 初始化

    private void init() {
        final ProgressDialog pd = ProgressDialog.show(this, "登录", "正在验证 Cookie...", true, false);
        new Thread(() -> {
            try {
                client.login(session.getCookie());
                runOnUiThread(() -> {
                    pd.dismiss();
                    ready = true;
                    loadDir(-1, true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                    session.clearCookie();
                    startActivity(new Intent(MainActivity.this, LoginActivity.class));
                    finish();
                });
            }
        }).start();
    }

    private void loadDir(final long folderId) {
        loadDir(folderId, false);
    }

    private void loadDir(final long folderId, final boolean afterLogin) {
        final ProgressDialog pd = ProgressDialog.show(this, "加载", "正在加载目录...", true, false);
        new Thread(() -> {
            try {
                Models.DirListing d = client.listDir(folderId);
                currentFiles = d.files == null ? new ArrayList<Models.FileItem>() : d.files;
                final List<Object> list = new ArrayList<>();
                list.addAll(d.folders);
                list.addAll(d.files);
                runOnUiThread(() -> {
                    pd.dismiss();
                    adapter = new FileListAdapter(MainActivity.this, list);
                    fileList.setAdapter(adapter);
                    if (afterLogin) {
                        maybeAskClipboard();
                        // 已开启自动拾取的，登录成功后也检查一次（onResume 时还没 ready）
                        if (prefs.isClipboardAuto()) handler.postDelayed(clipCheck, 800);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                    if (afterLogin && prefs.isClipboardAuto()) handler.postDelayed(clipCheck, 800);
                });
            }
        }).start();
    }

    // ================================================================ 导航

    private void enterFolder(Models.FolderItem f) {
        idStack.push(currentFolderId);
        nameStack.push(pathText.getText().toString());
        currentFolderId = f.id;
        pathText.setText(f.name);
        loadDir(f.id);
    }

    private void goBack() {
        if (!idStack.isEmpty()) {
            currentFolderId = idStack.pop();
            pathText.setText(nameStack.pop());
            loadDir(currentFolderId);
        } else {
            // 已在根目录：先确认，避免误触直接退出
            confirmExit();
        }
    }

    /** 根目录按返回键时的退出确认 */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("退出")
                .setMessage("当前已经在网盘根目录，要退出吗？")
                .setPositiveButton("退出", (d, w) -> finish())
                .setNegativeButton("留在网盘", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    // ================================================================ 文件操作

    private void onFileClick(final Models.FileItem f) {
        if (VolumeManifest.isManifest(f.name)) {
            new AlertDialog.Builder(this)
                    .setTitle(f.name)
                    .setMessage("这是分卷说明文件。\n\n"
                            + "「合并下载」会读取说明，下载同目录下的全部分卷，"
                            + "按顺序拼接成一个文件并校验。")
                    .setPositiveButton("合并下载", (d, w) -> openVolume(f))
                    .setNeutralButton("普通下载", (d, w) -> downloadFile(f.id))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final String[] options = {"下载", "复制分享链接", "复制下载直链"};
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        downloadFile(f.id);
                    } else if (which == 1) {
                        copyShare(f.id);
                    } else {
                        copyDirect(f.id);
                    }
                })
                .show();
    }

    /** 读取清单，确认分卷齐全后整组加入下载队列 */
    private void openVolume(final Models.FileItem f) {
        final ProgressDialog pd = ProgressDialog.show(this, "分卷", "正在读取说明文件...", true, false);
        new Thread(() -> {
            try {
                Models.ShareInfo s = client.share(f.id, true);
                final VolumeManifest m = VolumeFlow.read(client, s.url, s.pwd);
                final List<Models.FileItem> files = new ArrayList<>(currentFiles);
                final List<String> miss = VolumeFlow.missing(m, VolumeFlow.namesOfFiles(files));
                runOnUiThread(() -> {
                    pd.dismiss();
                    if (!miss.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("分卷不完整")
                                .setMessage("缺少 " + miss.size() + " 个分卷：\n" + preview(miss)
                                        + "\n\n请确认说明文件与全部分卷在同一个文件夹内。")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    VolumeFlow.start(this, prefs, m, VolumeFlow.fromFiles(client, files), null);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
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

    private void downloadFile(final long fid) {
        // 跳转浏览器模式：只取分享链接，交给系统浏览器下载
        if (prefs.isDownloadViaBrowser()) {
            final ProgressDialog pd = ProgressDialog.show(this, "跳转浏览器", "正在获取分享链接...", true, false);
            new Thread(() -> {
                try {
                    Models.ShareInfo s = client.share(fid, true);
                    final String url = s.url;
                    final String pwd = s.pwd;
                    runOnUiThread(() -> {
                        pd.dismiss();
                        openInBrowser(url, pwd);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        pd.dismiss();
                        toast(e.getMessage());
                    });
                }
            }).start();
            return;
        }

        // 自定义公共目录需要存储权限（API 23~29），否则取消本次下载
        if (!ensureStorageOk()) return;

        final ProgressDialog pd = ProgressDialog.show(this, "解析", "正在解析下载地址...", true, false);
        new Thread(() -> {
            try {
                Models.ShareInfo s = client.share(fid, true);
                Models.DownloadInfo info = client.directUrl(s.url, s.pwd);
                String name = LanzouClient.isUsableFileName(info.name) ? info.name : "";
                if (name.isEmpty() && LanzouClient.isUsableFileName(s.name)) name = s.name;
                if (name.isEmpty()) name = "file_" + fid;
                name = LanzouClient.localName(new File(name).getName());
                if (name.isEmpty()) name = "file_" + fid;

                final DownloadHelper.Dest dest = DownloadHelper.resolveDest(MainActivity.this, prefs, name);
                final String shownName = name;
                final String referer = s.url;
                final String pwd = s.pwd;
                final String durl = info.durl;
                HistoryStore.get(MainActivity.this).add(referer, pwd, shownName, HistoryStore.TYPE_FILE);
                runOnUiThread(() -> {
                    pd.dismiss();
                    DownloadCenter.get(MainActivity.this).enqueue(shownName, durl, referer, pwd, dest);
                    toast("已加入下载队列：" + shownName);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    private void copyShare(final long fid) {
        final ProgressDialog pd = ProgressDialog.show(this, "获取链接", "正在获取...", true, false);
        new Thread(() -> {
            try {
                Models.ShareInfo s = client.share(fid, true);
                final String text = "链接：" + s.url
                        + (s.pwd != null && !s.pwd.isEmpty() ? "\n提取码：" + s.pwd : "");
                runOnUiThread(() -> {
                    pd.dismiss();
                    CopyUtil.copy(MainActivity.this, text);
                    // 自己复制的链接不再触发「发现分享链接」弹窗
                    prefs.setLastClipboard(text);
                    toast("已复制到剪贴板\n" + text);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    private void copyDirect(final long fid) {
        final ProgressDialog pd = ProgressDialog.show(this, "获取直链", "正在获取...", true, false);
        new Thread(() -> {
            try {
                Models.ShareInfo s = client.share(fid, true);
                Models.DownloadInfo info = client.directUrl(s.url, s.pwd);
                final String durl = info.durl;
                runOnUiThread(() -> {
                    pd.dismiss();
                    CopyUtil.copy(MainActivity.this, durl);
                    toast("已复制直链（有时效，下载需带 Referer）");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    // ================================================================ 上传

    private void pickAndUpload(boolean forceVolume) {
        this.forceVolume = forceVolume;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQ_UPLOAD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UPLOAD && resultCode == RESULT_OK && data != null && data.getData() != null) {
            uploadFile(data.getData());
        }
    }

    private void uploadFile(final Uri uri) {
        String name = "file.bin";
        long size = 0;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni);
                if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        final String fname = name;
        final long fsize = size;

        final int partMb = prefs.getPartMb();
        final long partBytes = partMb * 1024L * 1024L;
        boolean wantVolume = forceVolume;
        forceVolume = false;
        if (wantVolume && fsize <= partBytes) {
            toast("文件未超过单卷大小（" + partMb + " MB），已改为普通上传");
            wantVolume = false;
        }
        if (wantVolume || (fsize > partBytes && partBytes > 0)) {
            final int count = (int) ((fsize + partBytes - 1) / partBytes);
            new AlertDialog.Builder(this)
                    .setTitle("分卷上传")
                    .setMessage(fname + "\n" + VolumeManifest.human(fsize)
                            + "\n\n超过单文件上限，将切成 " + count + " 卷（每卷 " + partMb + " MB）"
                            + "，并上传一个说明文件。\n\n以后在本客户端点开说明文件，"
                            + "即可自动下载全部分卷并合并还原。")
                    .setPositiveButton("开始上传", (d, w) -> doUploadVolume(uri, fname, fsize, partMb, count))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        doUpload(uri, fname, fsize);
    }

    /** 普通单文件上传 */
    private void doUpload(final Uri uri, final String fname, final long fsize) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMessage("正在上传 " + fname);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new LanzouException(LanzouException.NETWORK_ERROR, "无法读取所选文件");
                }
                Models.UploadResult r = client.upload(in, fname, fsize, currentFolderId,
                        (n, total, now) -> runOnUiThread(() -> {
                            if (total > 0) {
                                pd.setMax(100);
                                pd.setProgress((int) (now * 100 / total));
                            }
                        }));
                final String share = r.shareUrl;
                runOnUiThread(() -> {
                    pd.dismiss();
                    String msg = "上传成功：" + r.name;
                    if (share != null && !share.isEmpty()) msg += "\n分享链接：" + share;
                    toast(msg);
                    loadDir(currentFolderId);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }).start();
    }

    /** 分卷上传：切卷 → 逐卷上传 → 最后上传说明文件 */
    private void doUploadVolume(final Uri uri, final String fname, final long fsize,
                                final int partMb, final int count) {
        uploadCancelled = false;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setTitle("分卷上传");
        pd.setMessage("准备上传 " + count + " 卷...");
        pd.setMax(100);
        pd.setProgress(0);
        pd.setCancelable(true);
        pd.setOnCancelListener(d -> {
            uploadCancelled = true;
            client.cancelUpload();
        });
        pd.show();

        new Thread(() -> {
            InputStream in = null;
            long targetFolder = currentFolderId;
            String folderName = null;
            Models.ShareInfo folderShare = null;
            try {
                // 分卷与说明文件放进单独的子文件夹，分享时只需要一个文件夹链接
                if (prefs.isVolumeFolder()) {
                    folderName = volumeFolderName(fname);
                    try {
                        targetFolder = client.mkdir(currentFolderId, folderName, "分卷上传");
                    } catch (Exception e) {
                        targetFolder = currentFolderId;
                        folderName = null;
                    }
                }

                in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new LanzouException(LanzouException.NETWORK_ERROR, "无法读取所选文件");
                }
                final VolumeUploader.Result r = VolumeUploader.upload(client, in, fname, fsize,
                        targetFolder, partMb, new VolumeUploader.Listener() {
                            @Override
                            public void onProgress(final int index, final int total,
                                                   final String partName, final long done, final long totalSize) {
                                runOnUiThread(() -> {
                                    pd.setMessage("第 " + index + "/" + total + " 卷 · " + partName
                                            + "\n" + VolumeManifest.human(done)
                                            + " / " + VolumeManifest.human(totalSize));
                                    pd.setProgress((int) (done * 100 / Math.max(1, totalSize)));
                                });
                            }

                            @Override
                            public boolean isCancelled() {
                                return uploadCancelled;
                            }
                        });

                if (folderName != null && targetFolder > 0) {
                    try {
                        folderShare = client.share(targetFolder, false);
                    } catch (Exception ignored) {
                    }
                }

                final String fn = folderName;
                final Models.ShareInfo sh = folderShare;
                runOnUiThread(() -> {
                    pd.dismiss();
                    showVolumeDone(r, fn, sh);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    String msg = e.getMessage() == null ? "分卷上传失败" : e.getMessage();
                    if (uploadCancelled) msg = "已取消上传";
                    toast(msg);
                    // 取消/失败时已经传上去的卷仍在云端，刷新一下让用户看到
                    if (!uploadCancelled) loadDir(currentFolderId);
                });
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }).start();
    }

    /** 分卷文件夹名：原文件基名 + _分卷，过长截断 */
    private static String volumeFolderName(String fileName) {
        String base = fileName == null ? "file" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        if (base.length() > 40) base = base.substring(0, 40);
        return base + "_分卷";
    }

    private void showVolumeDone(VolumeUploader.Result r, String folderName, Models.ShareInfo share) {
        StringBuilder sb = new StringBuilder();
        sb.append("已上传 ").append(r.partCount).append(" 个分卷");
        if (folderName != null) sb.append("到文件夹「").append(folderName).append("」");
        sb.append("\n说明文件：").append(r.note.name);

        final String shareText;
        if (share != null && share.url != null && !share.url.isEmpty()) {
            StringBuilder st = new StringBuilder(share.url);
            if (share.pwd != null && !share.pwd.isEmpty()) st.append(" 提取码：").append(share.pwd);
            shareText = st.toString();
            sb.append("\n\n文件夹分享：").append(share.url);
            if (share.pwd != null && !share.pwd.isEmpty()) {
                sb.append("\n提取码：").append(share.pwd);
            }
            sb.append("\n\n对方用本客户端打开这个文件夹，点击说明文件即可自动合并下载。");
        } else {
            shareText = null;
            sb.append("\n\n在网盘里进入该文件夹，点击说明文件即可自动合并下载。");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("分卷上传完成")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null);
        if (shareText != null) {
            b.setNeutralButton("复制分享", (d, w) -> {
                CopyUtil.copy(this, shareText);
                toast("已复制：" + shareText);
            });
        }
        b.show();
        loadDir(currentFolderId);
    }

    // ================================================================ 链接下载

    private void downloadByLink() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 0);
        final EditText linkInput = new EditText(this);
        linkInput.setHint("分享链接：/iXXXX 文件、/bXXXX 文件夹");
        final EditText pwdInput = new EditText(this);
        pwdInput.setHint("提取码（无则留空）");

        // 剪贴板里已有链接时直接预填，省一次粘贴
        ShareLinks.Link clip = ShareLinks.parse(CopyUtil.read(this));
        if (clip != null) {
            linkInput.setText(clip.url);
            if (clip.pwd != null && !clip.pwd.isEmpty()) pwdInput.setText(clip.pwd);
        }

        ll.addView(linkInput);
        ll.addView(pwdInput);

        new AlertDialog.Builder(this)
                .setTitle("打开分享链接")
                .setMessage("支持文件分享与文件夹分享：\n"
                        + "· https://xxx.lanzn.com/iXXXXXX\n"
                        + "· https://xxx.lanzn.com/bXXXXXX（文件夹，可一键全部下载）")
                .setView(ll)
                .setPositiveButton("打开", (dialog, which) -> {
                    String url = linkInput.getText().toString().trim();
                    String pwd = pwdInput.getText().toString().trim();
                    if (url.isEmpty()) {
                        toast("请输入分享链接");
                        return;
                    }
                    downloadByUrl(url, pwd);
                })
                .setNeutralButton("历史记录", (dialog, which) ->
                        startActivity(new Intent(MainActivity.this, HistoryActivity.class)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadByUrl(final String url, final String pwd) {
        // 文件夹分享：进入列表浏览，而不是直接下载
        if (LanzouClient.isFolderShareUrl(url)) {
            HistoryStore.get(this).add(url, pwd, null, HistoryStore.TYPE_FOLDER);
            Intent i = new Intent(this, ShareBrowseActivity.class);
            i.putExtra(ShareBrowseActivity.EXTRA_URL, url);
            i.putExtra(ShareBrowseActivity.EXTRA_PWD, pwd);
            startActivity(i);
            return;
        }

        // 跳转浏览器模式：直接打开分享链接
        if (prefs.isDownloadViaBrowser()) {
            openInBrowser(url, pwd);
            return;
        }

        // 自定义公共目录需要存储权限（API 23~29），否则取消本次下载
        if (!ensureStorageOk()) return;

        final ProgressDialog pd = ProgressDialog.show(this, "解析", "正在解析...", true, false);
        new Thread(() -> {
            try {
                Models.DownloadInfo info = client.directUrl(url, pwd);
                final boolean nameKnown = LanzouClient.isUsableFileName(info.name);
                String name = nameKnown ? info.name : "download.bin";
                name = LanzouClient.localName(new File(name).getName());
                if (name.isEmpty()) name = "download.bin";

                final DownloadHelper.Dest dest = DownloadHelper.resolveDest(MainActivity.this, prefs, name);
                final String shownName = name;
                final String durl = info.durl;
                HistoryStore.get(MainActivity.this).add(url, pwd, shownName, HistoryStore.TYPE_FILE);
                runOnUiThread(() -> {
                    pd.dismiss();
                    DownloadCenter.get(MainActivity.this).enqueue(shownName, durl, url, pwd, dest);
                    toast(nameKnown ? ("已加入下载队列：" + shownName)
                            : "已加入下载队列，文件名将在连接后自动识别");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    // ================================================================ 新建文件夹

    private void mkdir() {
        final EditText input = new EditText(this);
        input.setHint("文件夹名称");
        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("请输入名称");
                        return;
                    }
                    doMkdir(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doMkdir(final String name) {
        final ProgressDialog pd = ProgressDialog.show(this, "新建文件夹", "正在创建...", true, false);
        new Thread(() -> {
            try {
                client.mkdir(currentFolderId, name, "");
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("创建成功");
                    loadDir(currentFolderId);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
                });
            }
        }).start();
    }

    // ================================================================ 工具

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int i = 1;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        while (true) {
            File g = new File(dir, base + "(" + i + ")" + ext);
            if (!g.exists()) return g;
            i++;
        }
    }

    /** 用系统浏览器打开分享页（提取码自动复制到剪贴板） */
    private void openInBrowser(String shareUrl, String pwd) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(shareUrl));
            startActivity(i);
            if (pwd != null && !pwd.isEmpty()) {
                CopyUtil.copy(this, pwd);
                toast("已打开浏览器，提取码已复制：" + pwd);
            } else {
                toast("已打开浏览器");
            }
        } catch (Exception e) {
            toast("无法打开浏览器：" + e.getMessage());
        }
    }

    /**
     * 下载前检查：自定义公共目录 + Android 6.0~12L 需要存储权限。
     * 返回 false 表示本次下载已取消（等待授权）。
     */
    private boolean ensureStorageOk() {
        return DownloadHelper.ensureStorage(this, prefs);
    }

    // ================================================================ 剪贴板拾取

    /** 首次进入主界面时询问一次是否开启剪贴板自动拾取 */
    private void maybeAskClipboard() {
        if (prefs.isClipboardAsked()) return;
        prefs.setClipboardAsked(true);
        new AlertDialog.Builder(this)
                .setTitle("剪贴板自动拾取")
                .setMessage("开启后，回到 App 时会读取剪贴板中的蓝奏云分享链接，并提示是否打开。\n\n"
                        + "· 只在本机识别，不会上传任何内容\n"
                        + "· 随时可以在设置里关闭")
                .setPositiveButton("开启", (d, w) -> {
                    prefs.setClipboardAuto(true);
                    handler.postDelayed(clipCheck, 300);
                })
                .setNegativeButton("暂不", (d, w) -> prefs.setClipboardAuto(false))
                .show();
    }

    /** 读取剪贴板并提示；manual=true 表示用户主动触发，读不到时会给出提示 */
    private void checkClipboard(boolean manual) {
        String text = CopyUtil.read(this);
        if (text == null || text.trim().isEmpty()) {
            if (manual) toast("剪贴板是空的");
            return;
        }
        if (prefs.isRecentClipboard(text)) {
            if (manual) toast("这段内容刚刚已经处理过了");
            return;
        }
        final ShareLinks.Link link = ShareLinks.parse(text);
        if (link == null) {
            if (manual) toast("剪贴板里没有识别到蓝奏云分享链接");
            return;
        }
        prefs.setLastClipboard(text);
        showClipboardDialog(link);
    }

    private void showClipboardDialog(final ShareLinks.Link link) {
        String msg = (link.folder ? "文件夹分享" : "文件分享") + "\n" + link.url
                + (link.pwd == null || link.pwd.isEmpty() ? "" : "\n提取码：" + link.pwd);
        new AlertDialog.Builder(this)
                .setTitle("发现分享链接")
                .setMessage(msg)
                .setPositiveButton(link.folder ? "浏览" : "下载", (d, w) -> {
                    if (link.folder) {
                        HistoryStore.get(this).add(link.url, link.pwd, null, HistoryStore.TYPE_FOLDER);
                        Intent i = new Intent(this, ShareBrowseActivity.class);
                        i.putExtra(ShareBrowseActivity.EXTRA_URL, link.url);
                        i.putExtra(ShareBrowseActivity.EXTRA_PWD, link.pwd == null ? "" : link.pwd);
                        startActivity(i);
                    } else {
                        downloadByUrl(link.url, link.pwd == null ? "" : link.pwd);
                    }
                })
                .setNeutralButton("复制链接", (d, w) -> {
                    CopyUtil.copy(this, link.url + (link.pwd == null ? "" : " 提取码：" + link.pwd));
                    toast("已复制链接");
                })
                .setNegativeButton("忽略", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
