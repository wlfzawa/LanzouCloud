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

/** 文件列表适配器：混合展示文件夹与文件 */
public class FileListAdapter extends BaseAdapter {

    private final Context context;
    private final List<Object> items;

    public FileListAdapter(Context context, List<Object> items) {
        this.context = context;
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
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
            convertView = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false);
            holder = new ViewHolder();
            holder.name = convertView.findViewById(R.id.item_name);
            holder.meta = convertView.findViewById(R.id.item_meta);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Object o = items.get(position);
        if (o instanceof Models.FolderItem) {
            Models.FolderItem f = (Models.FolderItem) o;
            holder.name.setText("\uD83D\uDCC1 " + f.name);
            holder.meta.setText("文件夹");
        } else if (o instanceof Models.FileItem) {
            Models.FileItem f = (Models.FileItem) o;
            holder.name.setText("\uD83D\uDCC4 " + f.name);
            String meta = f.size;
            if (f.time != null && !f.time.isEmpty()) {
                meta = meta.isEmpty() ? f.time : meta + " · " + f.time;
            }
            if (f.hasPwd) meta += " · 有码";
            holder.meta.setText(meta);
        }
        return convertView;
    }

    private static final class ViewHolder {
        TextView name;
        TextView meta;
    }
}
