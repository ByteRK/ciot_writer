package com.ciot.writer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ListView;

/**
 * 高度自适应的 ListView：内容不足 {@link #MAX_HEIGHT_DP} 时贴合内容（不滚动），
 * 超出时封顶并在列表内部滚动，避免把设置页撑得过长。
 *
 * <p>手势策略：手指落在列表上时，若列表自身可滚就整段手势由列表独占（禁止外层 ScrollView 拦截），
 * 到边界也不再交接给外层。不做交接是因为外层一旦 scrollBy，本控件的屏幕位置随之改变，
 * 而同一次拖动事件的 getY() 是相对本控件的局部坐标，会产生“位置变了→位移变大→再滚一次”的
 * 反馈回路，表现为画面一跳一跳。
 */
public class MaxHeightListView extends ListView {
    /** 列表高度上限（dp），约等于 6 行模板。 */
    private static final int MAX_HEIGHT_DP = 320;

    /** 本次手势是否由本列表独占（仅此状态需要向外层申请禁止拦截，并在抬手时归还）。 */
    private boolean handledByList;

    public MaxHeightListView(Context context) { this(context, null); }

    public MaxHeightListView(Context context, AttributeSet attrs) { this(context, attrs, 0); }

    public MaxHeightListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 到边界时无需越界回弹，保持静止即可，也避免与外层滚动叠加。
        setOverScrollMode(OVER_SCROLL_NEVER);
        setNestedScrollingEnabled(false);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int cap = Math.round(MAX_HEIGHT_DP * getResources().getDisplayMetrics().density);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }

    /**
     * 手势归属必须在 dispatchTouchEvent 里处理而不是 OnTouchListener：ListView 是 ViewGroup，
     * 当按下落在可点击的行内控件（圆点/删除）上时 DOWN 会被子 View 消费，OnTouchListener 不会回调。
     *
     * <p>列表自身可滚时整段手势都交给列表；不可滚（条目不足一屏）时完全不干预，
     * 由外层 ScrollView 正常接管，保证页面在列表区域也能滚动。
     */
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handledByList = canScrollList(-1) || canScrollList(1);
                requestParentDisallow(handledByList);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (handledByList) requestParentDisallow(false);
                handledByList = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void requestParentDisallow(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }
}
