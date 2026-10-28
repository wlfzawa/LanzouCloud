package com.lanzou.cloud.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/** 主界面：文件浏览、上传、下载、分享链接下载、新建文件夹 */
public class MainActivity extends Activity {

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
        uploadBtn.setOnClickListener(v -> pickAndUpload());
        mkdirBtn.setOnClickListener(v -> mkdir());
        linkBtn.setOnClickListener(v -> downloadByLink());
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
        String custom = prefs.getDownloadDir();
        if (custom == null || custom.trim().isEmpty()) return;
        if (!PermissionHelper.needsStorage(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("自定义下载目录位于公共存储，需要「存储」权限才能写入。现在去授权？\n\n拒绝后下载会自动改用默认目录。")
                .setPositiveButton("去授权", (d, w) -> PermissionHelper.requestStorage(this))
                .setNegativeButton("暂不", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PermissionHelper.REQ_STORAGE) return;
        if (PermissionHelper.isGranted(grantResults)) {
            toast("存储权限已授予，自定义下载目录可正常写入");
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
                    loadDir(-1);
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
        final ProgressDialog pd = ProgressDialog.show(this, "加载", "正在加载目录...", true, false);
        new Thread(() -> {
            try {
                Models.DirListing d = client.listDir(folderId);
                final List<Object> list = new ArrayList<>();
                list.addAll(d.folders);
                list.addAll(d.files);
                runOnUiThread(() -> {
                    pd.dismiss();
                    adapter = new FileListAdapter(MainActivity.this, list);
                    fileList.setAdapter(adapter);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast(e.getMessage());
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
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    // ================================================================ 文件操作

    private void onFileClick(final Models.FileItem f) {
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

                File dir = resolveDownloadDir();
                File outFile = targetFile(dir, name);
                final String shownName = name;
                final String referer = s.url;
                final String pwd = s.pwd;
                final String durl = info.durl;
                runOnUiThread(() -> {
                    pd.dismiss();
                    DownloadCenter.get(MainActivity.this).enqueue(shownName, durl, referer, pwd, outFile);
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

    private void pickAndUpload() {
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

    // ================================================================ 链接下载

    private void downloadByLink() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 20, 40, 0);
        final EditText linkInput = new EditText(this);
        linkInput.setHint("分享链接：/iXXXX 文件、/bXXXX 文件夹");
        final EditText pwdInput = new EditText(this);
        pwdInput.setHint("提取码（无则留空）");
        ll.addView(linkInput);
        ll.addView(pwdInput);

        new AlertDialog.Builder(this)
                .setTitle("打开分享链接")
                .setMessage("支持文件分享与文件夹分享：\n"
                        + "· https://xxx.lanzn.com/iXXXXXX\n"
                        + "· https://xxx.lanzn.com/bXXXXXX（文件夹）")
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
                .setNegativeButton("取消", null)
                .show();
    }

    private void downloadByUrl(final String url, final String pwd) {
        // 文件夹分享：进入列表浏览，而不是直接下载
        if (LanzouClient.isFolderShareUrl(url)) {
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

                File dir = resolveDownloadDir();
                File outFile = targetFile(dir, name);
                final String shownName = name;
                final String durl = info.durl;
                runOnUiThread(() -> {
                    pd.dismiss();
                    DownloadCenter.get(MainActivity.this).enqueue(shownName, durl, url, pwd, outFile);
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
        String custom = prefs.getDownloadDir();
        if (custom == null || custom.trim().isEmpty()) return true;
        if (!PermissionHelper.needsStorage(this)) return true;
        new AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("自定义下载目录位于公共存储，需要「存储」权限才能写入。\n\n去授权，或到设置中改回默认目录（无需权限）。")
                .setPositiveButton("去授权", (d, w) -> PermissionHelper.requestStorage(this))
                .setNegativeButton("取消下载", null)
                .show();
        return false;
    }

    /** 解析下载目录：优先自定义路径（需权限/系统支持），否则应用专属目录 */
    private File resolveDownloadDir() {
        return DownloadHelper.resolveDir(this, prefs);
    }

    /** 根据「重名覆盖」设置决定目标文件 */
    private File targetFile(File dir, String name) {
        return DownloadHelper.targetFile(prefs, dir, name);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
