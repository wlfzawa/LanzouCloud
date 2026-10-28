package com.lanzou.cloud.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.lanzou.cloud.R;
import com.lanzou.cloud.core.Models;

import java.util.List;

/** 文件夹分享的文件列表适配器 */
public class ShareFileAdapter extends BaseAdapter {

    private final Context context;
    private final List<Models.ShareFile> items;

    public ShareFileAdapter(Context context, List<Models.ShareFile> items) {
        this.context = context;
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Models.ShareFile getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_share, parent, false);
            holder = new ViewHolder();
            holder.name = convertView.findViewById(R.id.share_item_name);
            holder.meta = convertView.findViewById(R.id.share_item_meta);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        Models.ShareFile f = items.get(position);
        holder.name.setText(f.name == null || f.name.isEmpty() ? f.code : f.name);
        StringBuilder meta = new StringBuilder();
        if (f.size != null && !f.size.isEmpty()) meta.append(f.size);
        if (f.time != null && !f.time.isEmpty()) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(f.time);
        }
        holder.meta.setText(meta.length() == 0 ? "" : meta.toString());
        return convertView;
    }

    private static final class ViewHolder {
        TextView name;
        TextView meta;
    }
}
