package com.lanzou.cloud.ui;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.LanzouClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 账号管理：多账号列表，支持切换 / 重命名 / 删除 / 登录新账号 */
public class AccountsActivity extends BaseActivity {

    private Session session;
    private ListView accountList;
    private AccountAdapter adapter;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accounts);

        session = Session.get(this);
        accountList = findViewById(R.id.account_list);
        Button backBtn = findViewById(R.id.accounts_back_btn);
        Button addBtn = findViewById(R.id.btn_add_account);

        backBtn.setOnClickListener(v -> finish());
        addBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, LoginActivity.class);
            i.putExtra("force", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });

        accountList.setOnItemClickListener((parent, view, position, id) -> {
            AccountStore.Account a = (AccountStore.Account) adapter.getItem(position);
            if (a != null) showAccountMenu(a);
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<AccountStore.Account> list = session.listAccounts();
        adapter = new AccountAdapter(list);
        accountList.setAdapter(adapter);
    }

    // ================================================================ 账号操作

    private void showAccountMenu(final AccountStore.Account a) {
        String[] options = {"切换到此账号", "重命名", "删除", "取消"};
        new AlertDialog.Builder(this)
                .setTitle(a.name + "（UID " + a.uid + "）")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) switchTo(a);
                    else if (which == 1) rename(a);
                    else if (which == 2) remove(a);
                })
                .show();
    }

    private void switchTo(final AccountStore.Account a) {
        if (a.uid != null && a.uid.equals(session.getUid())) {
            toast("已经是当前账号");
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(this, "切换账号", "正在验证 Cookie...", true, false);
        new Thread(() -> {
            try {
                // 先用独立客户端验证 Cookie 有效性，失败不影响当前登录态
                LanzouClient probe = new LanzouClient();
                probe.login(a.cookie);
                final String uid = probe.getUid();
                runOnUiThread(() -> {
                    // 验证通过：重置主客户端并切换到新账号
                    LanzouClient c = session.getClient();
                    try {
                        c.reset();
                        c.login(a.cookie);
                    } catch (Exception ignored) {
                    }
                    session.setCookie(a.cookie);
                    pd.dismiss();
                    toast("已切换到：" + a.name + (uid == null ? "" : "（UID " + uid + "）"));
                    refresh();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    toast("切换失败，该账号 Cookie 已失效：" + e.getMessage());
                });
            }
        }).start();
    }

    private void rename(final AccountStore.Account a) {
        final EditText input = new EditText(this);
        input.setText(a.name == null ? "" : a.name);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名账号")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    session.renameAccount(a.uid, input.getText().toString().trim());
                    refresh();
                    toast("已重命名");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void remove(final AccountStore.Account a) {
        final boolean isCurrent = a.uid != null && a.uid.equals(session.getUid());
        String msg = isCurrent
                ? "这是当前登录的账号，删除后将退出登录并返回登录页。确定删除？"
                : "确定删除账号「" + a.name + "」？";
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage(msg)
                .setPositiveButton("删除", (dialog, which) -> {
                    session.removeAccount(a.uid);
                    if (isCurrent) {
                        session.clearCookie();
                        Intent i = new Intent(this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    } else {
                        refresh();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================ 列表适配器

    private class AccountAdapter extends BaseAdapter {

        private final List<AccountStore.Account> data;

        AccountAdapter(List<AccountStore.Account> data) {
            this.data = data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(AccountsActivity.this)
                        .inflate(R.layout.item_account, parent, false);
            }
            AccountStore.Account a = data.get(position);
            TextView nameView = convertView.findViewById(R.id.account_name);
            TextView uidView = convertView.findViewById(R.id.account_uid);
            TextView timeView = convertView.findViewById(R.id.account_time);
            TextView currentView = convertView.findViewById(R.id.account_current);

            nameView.setText(a.name == null || a.name.isEmpty() ? ("账号 " + a.uid) : a.name);
            uidView.setText("UID: " + (a.uid == null ? "?" : a.uid));
            String time;
            try {
                time = timeFmt.format(new Date(a.time));
            } catch (Exception e) {
                time = "";
            }
            timeView.setText("最近登录：" + time);
            boolean isCurrent = a.uid != null && a.uid.equals(session.getUid());
            currentView.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
