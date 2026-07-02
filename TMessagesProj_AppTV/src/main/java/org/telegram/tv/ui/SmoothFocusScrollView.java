package org.telegram.tv.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;

/**
 * ScrollView that intercepts D-PAD-driven focus requests and makes them smooth
 * instead of jumping instantly. The system calls requestChildRectangleOnScreen
 * with immediate=true for keyboard/D-PAD focus; overriding to false activates
 * ScrollView's own smoothScrollBy path.
 */
public class SmoothFocusScrollView extends ScrollView {

    public SmoothFocusScrollView(Context context) {
        super(context);
    }

    public SmoothFocusScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SmoothFocusScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        return super.requestChildRectangleOnScreen(child, rectangle, false);
    }
}
