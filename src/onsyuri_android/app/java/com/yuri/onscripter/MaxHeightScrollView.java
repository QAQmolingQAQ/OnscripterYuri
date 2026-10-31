/**
 * ScrollView that never grows taller than a part of the available height,
 * so the settings panel can not cover the whole screen on small screens.
 */
package com.yuri.onscripter;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

public class MaxHeightScrollView extends ScrollView {
    private float m_maxRatio = 0.62f;

    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMaxHeightRatio(float ratio) {
        m_maxRatio = ratio;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int cap;
        if (mode == MeasureSpec.UNSPECIFIED) {
            cap = (int) (getResources().getDisplayMetrics().heightPixels * m_maxRatio);
        } else {
            cap = (int) (size * m_maxRatio);
        }
        int childSpec = MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, childSpec);
    }
}
