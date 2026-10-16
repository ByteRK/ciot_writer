package com.ciot.writer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品列表适配器：只渲染筛选后的可见行，行内提供“设为当前使用 / 编辑 / 删除”三个动作。
 * 回调中的 index 均为 {@link #products} 中的真实下标，便于增删改定位。
 */
public class ProductAdapter extends BaseAdapter {

    /** 行交互回调。 */
    public interface Listener {
        void onSelect(int index);
        void onEdit(Product product);
        void onDelete(int index);
    }

    private final Context context;
    private final Listener listener;
    private final List<Product> products;
    private final List<Integer> visible = new ArrayList<>();
    private int current = 0;

    public ProductAdapter(Context context, Listener listener, List<Product> products) {
        this.context = context;
        this.listener = listener;
        this.products = products;
    }

    /**
     * 更新筛选结果与“当前使用”下标。注意 {@code products} 需保持同一实例，
     * 因此导入产品时应原地 clear/addAll 而不是重新赋值。
     */
    public void submit(List<Integer> indices, int current) {
        visible.clear();
        visible.addAll(indices);
        this.current = current;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return visible.size(); }

    @Override public Object getItem(int position) { return products.get(indexAt(position)); }

    @Override public long getItemId(int position) { return indexAt(position); }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if (row == null) row = LayoutInflater.from(context).inflate(R.layout.item_product, parent, false);
        final int index = indexAt(position);
        Product product = products.get(index);
        boolean active = index == current;

        TextView dot = row.findViewById(R.id.rowDot);
        TextView name = row.findViewById(R.id.rowName);
        TextView sub = row.findViewById(R.id.rowSub);
        TextView remove = row.findViewById(R.id.rowDelete);
        View divider = row.findViewById(R.id.rowDivider);

        dot.setTextColor(context.getColor(active ? R.color.colorPrimary : R.color.colorDotIdle));
        name.setTextColor(context.getColor(active ? R.color.colorPrimary : R.color.colorDark));
        name.setText(product.name);
        sub.setText("PID " + (product.pid.isEmpty() ? "未设置" : product.pid)
                + " · " + product.networkInterface + " · " + product.targetPath);
        if (divider != null) divider.setVisibility(position == getCount() - 1 ? View.GONE : View.VISIBLE);

        dot.setOnClickListener(v -> listener.onSelect(index));
        row.findViewById(R.id.rowMain).setOnClickListener(v -> listener.onEdit(product));
        remove.setOnClickListener(v -> listener.onDelete(index));
        return row;
    }

    private int indexAt(int position) { return visible.get(position); }
}
