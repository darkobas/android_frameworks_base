/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.android.internal.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.widget.AbsListView;
import android.widget.OverScroller;
import com.android.internal.R;

public class ResolverDrawerLayout extends ViewGroup {
    private static final String TAG = "ResolverDrawerLayout";

    /**
     * Max width of the whole drawer layout
     */
    private int mMaxWidth;

    /**
     * Max total visible height of views not marked always-show when in the closed/initial state
     */
    private int mMaxCollapsedHeight;

    /**
     * Max total visible height of views not marked always-show when in the closed/initial state
     * when a default option is present
     */
    private int mMaxCollapsedHeightSmall;

    private boolean mSmallCollapsed;

    /**
     * Move views down from the top by this much in px
     */
    private float mCollapseOffset;

    private int mCollapsibleHeight;

    private int mTopOffset;

    private boolean mIsDragging;
    private boolean mOpenOnClick;
    private final int mTouchSlop;
    private final float mMinFlingVelocity;
    private final OverScroller mScroller;
    private final VelocityTracker mVelocityTracker;

    private OnClickListener mClickOutsideListener;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private float mLastTouchY;
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;

    private final Rect mTempRect = new Rect();

    private final ViewTreeObserver.OnTouchModeChangeListener mTouchModeChangeListener =
            new ViewTreeObserver.OnTouchModeChangeListener() {
                @Override
                public void onTouchModeChanged(boolean isInTouchMode) {
                    if (!isInTouchMode && hasFocus() && isDescendantClipped(getFocusedChild())) {
                        smoothScrollTo(0, 0);
                    }
                }
            };

    public ResolverDrawerLayout(Context context) {
        this(context, null);
    }

    public ResolverDrawerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ResolverDrawerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ResolverDrawerLayout,
                defStyleAttr, 0);
        mMaxWidth = a.getDimensionPixelSize(R.styleable.ResolverDrawerLayout_maxWidth, -1);
        mMaxCollapsedHeight = a.getDimensionPixelSize(
                R.styleable.ResolverDrawerLayout_maxCollapsedHeight, 0);
        mMaxCollapsedHeightSmall = a.getDimensionPixelSize(
                R.styleable.ResolverDrawerLayout_maxCollapsedHeightSmall,
                mMaxCollapsedHeight);
        a.recycle();

        mScroller = new OverScroller(context, AnimationUtils.loadInterpolator(context,
                android.R.interpolator.decelerate_quint));
        mVelocityTracker = VelocityTracker.obtain();

        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
    }

    public void setSmallCollapsed(boolean smallCollapsed) {
        mSmallCollapsed = smallCollapsed;
        requestLayout();
    }

    public boolean isSmallCollapsed() {
        return mSmallCollapsed;
    }

    public boolean isCollapsed() {
        return mCollapseOffset > 0;
    }

    private boolean isMoving() {
        return mIsDragging || !mScroller.isFinished();
    }

    private int getMaxCollapsedHeight() {
        return isSmallCollapsed() ? mMaxCollapsedHeightSmall : mMaxCollapsedHeight;
    }

    public void setOnClickOutsideListener(OnClickListener listener) {
        mClickOutsideListener = listener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            mVelocityTracker.clear();
        }

        mVelocityTracker.addMovement(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                final float x = ev.getX();
                final float y = ev.getY();
                mInitialTouchX = x;
                mInitialTouchY = mLastTouchY = y;
                mOpenOnClick = isListChildUnderClipped(x, y) && mCollapsibleHeight > 0;
            }
            break;

            case MotionEvent.ACTION_MOVE: {
                final float x = ev.getX();
                final float y = ev.getY();
                final float dy = y - mInitialTouchY;
                if (Math.abs(dy) > mTouchSlop && findChildUnder(x, y) != null &&
                        (getNestedScrollAxes() & SCROLL_AXIS_VERTICAL) == 0) {
                    mActivePointerId = ev.getPointerId(0);
                    mIsDragging = true;
                    mLastTouchY = Math.max(mLastTouchY - mTouchSlop,
                            Math.min(mLastTouchY + dy, mLastTouchY + mTouchSlop));
                }
            }
            break;

            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
            }
            break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                resetTouch();
            }
            break;
        }

        if (mIsDragging) {
            mScroller.abortAnimation();
        }
        return mIsDragging || mOpenOnClick;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();

        boolean handled = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                final float x = ev.getX();
                final float y = ev.getY();
                mInitialTouchX = x;
                mInitialTouchY = mLastTouchY = y;
                mActivePointerId = ev.getPointerId(0);
                if (findChildUnder(mInitialTouchX, mInitialTouchY) == null &&
                        mClickOutsideListener != null) {
                    mIsDragging = handled = true;
                }
                handled |= mCollapsibleHeight > 0;
                mScroller.abortAnimation();
            }
            break;

            case MotionEvent.ACTION_MOVE: {
                int index = ev.findPointerIndex(mActivePointerId);
                if (index < 0) {
                    Log.e(TAG, "Bad pointer id " + mActivePointerId + ", resetting");
                    index = 0;
                    mActivePointerId = ev.getPointerId(0);
                    mInitialTouchX = ev.getX();
                    mInitialTouchY = mLastTouchY = ev.getY();
                }
                final float x = ev.getX(index);
                final float y = ev.getY(index);
                if (!mIsDragging) {
                    final float dy = y - mInitialTouchY;
                    if (Math.abs(dy) > mTouchSlop && findChildUnder(x, y) != null) {
                        handled = mIsDragging = true;
                        mLastTouchY = Math.max(mLastTouchY - mTouchSlop,
                                Math.min(mLastTouchY + dy, mLastTouchY + mTouchSlop));
                    }
                }
                if (mIsDragging) {
                    final float dy = y - mLastTouchY;
                    performDrag(dy);
                }
                mLastTouchY = y;
            }
            break;

            case MotionEvent.ACTION_POINTER_DOWN: {
                final int pointerIndex = ev.getActionIndex();
                final int pointerId = ev.getPointerId(pointerIndex);
                mActivePointerId = pointerId;
                mInitialTouchX = ev.getX(pointerIndex);
                mInitialTouchY = mLastTouchY = ev.getY(pointerIndex);
            }
            break;

            case MotionEvent.ACTION_POINTER_UP: {
                onSecondaryPointerUp(ev);
            }
            break;

            case MotionEvent.ACTION_UP: {
                mIsDragging = false;
                if (!mIsDragging && findChildUnder(mInitialTouchX, mInitialTouchY) == null &&
                        findChildUnder(ev.getX(), ev.getY()) == null) {
                    if (mClickOutsideListener != null) {
                        mClickOutsideListener.onClick(this);
                        resetTouch();
                        return true;
                    }
                }
                if (mOpenOnClick && Math.abs(ev.getX() - mInitialTouchX) < mTouchSlop &&
                        Math.abs(ev.getY() - mInitialTouchY) < mTouchSlop) {
                    smoothScrollTo(0, 0);
                    return true;
                }
                mVelocityTracker.computeCurrentVelocity(1000);
                final float yvel = mVelocityTracker.getYVelocity(mActivePointerId);
                if (Math.abs(yvel) > mMinFlingVelocity) {
                    smoothScrollTo(yvel < 0 ? 0 : mCollapsibleHeight, yvel);
                } else {
                    smoothScrollTo(
                            mCollapseOffset < mCollapsibleHeight / 2 ? 0 : mCollapsibleHeight, 0);
                }
                resetTouch();
            }
            break;

            case MotionEvent.ACTION_CANCEL: {
                resetTouch();
                return true;
            }
        }

        return handled;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mInitialTouchX = ev.getX(newPointerIndex);
            mInitialTouchY = mLastTouchY = ev.getY(newPointerIndex);
            mActivePointerId = ev.getPointerId(newPointerIndex);
        }
    }

    private void resetTouch() {
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mIsDragging = false;
        mOpenOnClick = false;
        mInitialTouchX = mInitialTouchY = mLastTouchY = 0;
        mVelocityTracker.clear();
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (!mScroller.isFinished()) {
            final boolean keepGoing = mScroller.computeScrollOffset();
            performDrag(mScroller.getCurrY() - mCollapseOffset);
            if (keepGoing) {
                postInvalidateOnAnimation();
            }
        }
    }

    private float performDrag(float dy) {
        final float newPos = Math.max(0, Math.min(mCollapseOffset + dy, mCollapsibleHeight));
        if (newPos != mCollapseOffset) {
            dy = newPos - mCollapseOffset;
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (!lp.ignoreOffset) {
                    child.offsetTopAndBottom((int) dy);
                }
            }
            mCollapseOffset = newPos;
            mTopOffset += dy;
            postInvalidateOnAnimation();
            return dy;
        }
        return 0;
    }

    private void smoothScrollTo(int yOffset, float velocity) {
        if (getMaxCollapsedHeight() == 0) {
            return;
        }
        mScroller.abortAnimation();
        final int sy = (int) mCollapseOffset;
        int dy = yOffset - sy;
        if (dy == 0) {
            return;
        }

        final int height = getHeight();
        final int halfHeight = height / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dy) / height);
        final float distance = halfHeight + halfHeight *
                distanceInfluenceForSnapDuration(distanceRatio);

        int duration = 0;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float pageDelta = (float) Math.abs(dy) / height;
            duration = (int) ((pageDelta + 1) * 100);
        }
        duration = Math.min(duration, 300);

        mScroller.startScroll(0, sy, 0, dy, duration);
        postInvalidateOnAnimation();
    }

    private float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    /**
     * Note: this method doesn't take Z into account for overlapping views
     * since it is only used in contexts where this doesn't affect the outcome.
     */
    private View findChildUnder(float x, float y) {
        return findChildUnder(this, x, y);
    }

    private static View findChildUnder(ViewGroup parent, float x, float y) {
        final int childCount = parent.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            final View child = parent.getChildAt(i);
            if (isChildUnder(child, x, y)) {
                return child;
            }
        }
        return null;
    }

    private View findListChildUnder(float x, float y) {
        View v = findChildUnder(x, y);
        while (v != null) {
            x -= v.getX();
            y -= v.getY();
            if (v instanceof AbsListView) {
                // One more after this.
                return findChildUnder((ViewGroup) v, x, y);
            }
            v = v instanceof ViewGroup ? findChildUnder((ViewGroup) v, x, y) : null;
        }
        return v;
    }

    /**
     * This only checks clipping along the bottom edge.
     */
    private boolean isListChildUnderClipped(float x, float y) {
        final View listChild = findListChildUnder(x, y);
        return listChild != null && isDescendantClipped(listChild);
    }

    private boolean isDescendantClipped(View child) {
        mTempRect.set(0, 0, child.getWidth(), child.getHeight());
        offsetDescendantRectToMyCoords(child, mTempRect);
        View directChild;
        if (child.getParent() == this) {
            directChild = child;
        } else {
            View v = child;
            ViewParent p = child.getParent();
            while (p != this) {
                v = (View) p;
                p = v.getParent();
            }
            directChild = v;
        }

        // ResolverDrawerLayout lays out vertically in child order;
        // the next view and forward is what to check against.
        int clipEdge = getHeight() - getPaddingBottom();
        final int childCount = getChildCount();
        for (int i = indexOfChild(directChild) + 1; i < childCount; i++) {
            final View nextChild = getChildAt(i);
            if (nextChild.getVisibility() == GONE) {
                continue;
            }
            clipEdge = Math.min(clipEdge, nextChild.getTop());
        }
        return mTempRect.bottom > clipEdge;
    }

    private static boolean isChildUnder(View child, float x, float y) {
        final float left = child.getX();
        final float top = child.getY();
        final float right = left + child.getWidth();
        final float bottom = top + child.getHeight();
        return x >= left && y >= top && x < right && y < bottom;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (!isInTouchMode() && isDescendantClipped(focused)) {
            smoothScrollTo(0, 0);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnTouchModeChangeListener(mTouchModeChangeListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnTouchModeChangeListener(mTouchModeChangeListener);
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return (nestedScrollAxes & View.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        super.onNestedScrollAccepted(child, target, axes);
    }

    @Override
    public void onStopNestedScroll(View child) {
        super.onStopNestedScroll(child);
        smoothScrollTo(mCollapseOffset < mCollapsibleHeight / 2 ? 0 : mCollapsibleHeight, 0);
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed) {
        if (dyUnconsumed > 0) {
            performDrag(-dyUnconsumed);
        }
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        if (dy < 0) {
            consumed[1] = (int) performDrag(-dy);
        }
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        if (!consumed && Math.abs(velocityY) > mMinFlingVelocity) {
            smoothScrollTo(velocityY < 0 ? 0 : mCollapsibleHeight, velocityY);
            return true;
        }
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int sourceWidth = MeasureSpec.getSize(widthMeasureSpec);
        int widthSize = sourceWidth;
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        // Single-use layout; just ignore the mode and use available space.
        // Clamp to maxWidth.
        if (mMaxWidth >= 0) {
            widthSize = Math.min(widthSize, mMaxWidth);
        }

        final int widthSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY);
        final int heightSpec = MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY);
        final int widthPadding = getPaddingLeft() + getPaddingRight();
        int heightUsed = getPaddingTop() + getPaddingBottom();

        // Measure always-show children first.
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.alwaysShow && child.getVisibility() != GONE) {
                measureChildWithMargins(child, widthSpec, widthPadding, heightSpec, heightUsed);
                heightUsed += lp.topMargin + child.getMeasuredHeight() + lp.bottomMargin;
            }
        }

        final int alwaysShowHeight = heightUsed;

        // And now the rest.
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (!lp.alwaysShow && child.getVisibility() != GONE) {
                measureChildWithMargins(child, widthSpec, widthPadding, heightSpec, heightUsed);
                heightUsed += lp.topMargin + child.getMeasuredHeight() + lp.bottomMargin;
            }
        }

        mCollapsibleHeight = Math.max(0,
                heightUsed - alwaysShowHeight - getMaxCollapsedHeight());

        if (isLaidOut()) {
            mCollapseOffset = Math.min(mCollapseOffset, mCollapsibleHeight);
        } else {
            // Start out collapsed at first
            mCollapseOffset = mCollapsibleHeight;
        }

        mTopOffset = Math.max(0, heightSize - heightUsed) + (int) mCollapseOffset;

        setMeasuredDimension(sourceWidth, heightSize);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int width = getWidth();

        int ypos = mTopOffset;
        int leftEdge = getPaddingLeft();
        int rightEdge = width - getPaddingRight();

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (child.getVisibility() == GONE) {
                continue;
            }

            int top = ypos + lp.topMargin;
            if (lp.ignoreOffset) {
                top -= mCollapseOffset;
            }
            final int bottom = top + child.getMeasuredHeight();

            final int childWidth = child.getMeasuredWidth();
            final int widthAvailable = rightEdge - leftEdge;
            final int left = leftEdge + (widthAvailable - childWidth) / 2;
            final int right = left + childWidth;

            child.layout(left, top, right, bottom);

            ypos = bottom + lp.bottomMargin;
        }
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        if (p instanceof LayoutParams) {
            return new LayoutParams((LayoutParams) p);
        } else if (p instanceof MarginLayoutParams) {
            return new LayoutParams((MarginLayoutParams) p);
        }
        return new LayoutParams(p);
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    public static class LayoutParams extends MarginLayoutParams {
        public boolean alwaysShow;
        public boolean ignoreOffset;

        public LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            final TypedArray a = c.obtainStyledAttributes(attrs,
                    R.styleable.ResolverDrawerLayout_LayoutParams);
            alwaysShow = a.getBoolean(
                    R.styleable.ResolverDrawerLayout_LayoutParams_layout_alwaysShow,
                    false);
            ignoreOffset = a.getBoolean(
                    R.styleable.ResolverDrawerLayout_LayoutParams_layout_ignoreOffset,
                    false);
            a.recycle();
        }

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(LayoutParams source) {
            super(source);
            this.alwaysShow = source.alwaysShow;
            this.ignoreOffset = source.ignoreOffset;
        }

        public LayoutParams(MarginLayoutParams source) {
            super(source);
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }
}