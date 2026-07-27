/*
 * Copyright (C) 2026 The AviumUI Project
 * Adapted for PixelLauncherEnhanced Xposed module
 */
package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;

/**
 * A hint view for freeform gesture, owned by DragLayer as a permanent child.
 * Lifecycle is bound to DragLayer — created once, never removed.
 * Callers use setPhase()/setTaskBounds()/setDisplayRotation() to control state.
 */
public class FreeformHintView extends FrameLayout {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270})
    public @interface SurfaceRotation {}

    public enum HintPhase { HIDDEN, SWIPE_UP_HINT, EXPAND }

    private static final int CARD_HEIGHT_DP = 56, ICON_SIZE_DP = 24;
    private static final int ICON_PADDING_DP = 16, TEXT_SIZE_SP = 14;
    private static final int CARD_MARGIN_DP = 2, EXPAND_MARGIN_DP = 8;
    private static final int ANIM_DURATION_MS = 250;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mCardRect = new RectF();
    private final String mSwipeUpText;
    private final Rect mTaskBounds = new Rect();

    private final float mCardHeight, mCornerRadius, mIconSize, mIconPadding;
    private final int mCardMargin;
    private final float mInnerPadding;

    @SurfaceRotation
    private int mRotation = ROTATION_0;
    private HintPhase mPhase = HintPhase.HIDDEN;
    private boolean mIsVisible, mHasTaskBounds;
    private float mHintAlpha = 0f, mScale = 0.85f, mExpandProgress = 0f;
    private String mDisplayText;

    private ValueAnimator mProgressAnimator;
    private AnimatorSet mVisibilityAnimator;
    private final int[] mPosTmp = new int[2];

    /** Active release animation (shrink + fade), null when idle. */
    private AnimatorSet mReleaseAnimator;

    private final ImageView mIconView;

    /**
     * @param context     Context (launcher process)
     * @param moduleRes   Module's own Resources for i18n string loading (can be null)
     * @param modulePackageName Module's package name for resource lookup
     */
    public FreeformHintView(@NonNull Context context,
                            @Nullable Resources moduleRes,
                            @Nullable String modulePackageName) {
        super(context);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setWillNotDraw(false);
        float density = context.getResources().getDisplayMetrics().density;

        mCardHeight = CARD_HEIGHT_DP * density;
        mIconSize = ICON_SIZE_DP * density;
        mIconPadding = ICON_PADDING_DP * density;
        mCardMargin = (int) (CARD_MARGIN_DP * density);
        mInnerPadding = 4 * density;
        mCornerRadius = getSystemCornerRadius(context) + mInnerPadding / 2;

        // Accent color: try Monet dynamic color, fallback #39FFCC
        mBgPaint.setColor(getDynamicAccentColor(context));
        mBgPaint.setStyle(Paint.Style.FILL);

        // Text color: follow theme's primary text
        mTextPaint.setColor(getDynamicTextColor(context));
        mTextPaint.setTextSize(TEXT_SIZE_SP * density);
        mTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        // Hint text: load from module's string resource for i18n support
        mSwipeUpText = loadHintText(moduleRes, modulePackageName);

        // Icon: try launcher's manage-windows icon, fallback white circle
        mIconView = new ImageView(context);
        Drawable icon = loadLauncherIcon(context);
        if (icon == null) {
            icon = createFallbackIcon();
        }
        mIconView.setImageDrawable(icon);
        mIconView.setColorFilter(getDynamicTextColor(context));
        int iconSizePx = (int) mIconSize;
        int topMargin = (int) ((mCardHeight - mIconSize) / 2);
        LayoutParams iconLp = new LayoutParams(iconSizePx, iconSizePx);
        iconLp.leftMargin = (int) mIconPadding;
        iconLp.topMargin = topMargin;
        iconLp.gravity = Gravity.TOP | Gravity.START;
        addView(mIconView, iconLp);

        setVisibility(View.VISIBLE);
        setScaleX(0.85f);
        setScaleY(0.85f);
    }

    // ── Resource helpers ──

    private static int getDynamicAccentColor(Context context) {
        try {
            TypedValue tv = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                int type = tv.type;
                if (type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
            }
        } catch (Exception ignored) {}
        return 0xFF39FFCC; // fallback teal
    }

    private static float getSystemCornerRadius(Context context) {
        // Try common system dimen resources for rounded corner radius.
        // config_dialogCornerRadius is universally available on Android 12+.
        float density = context.getResources().getDisplayMetrics().density;
        try {
            int id = context.getResources().getIdentifier(
                    "config_dialogCornerRadius", "dimen", "android");
            if (id != 0) {
                return context.getResources().getDimension(id);
            }
        } catch (Exception ignored) {}
        // Fallback: 28dp
        return 28 * density;
    }

    private static int getDynamicTextColor(Context context) {
        // On Android 12+, use Monet's on-primary color for text on accent background
        try {
            TypedValue tv = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.textColorPrimaryInverse, tv, true)) {
                int type = tv.type;
                if (type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
            }
        } catch (Exception ignored) {}
        return Color.WHITE;
    }

    private static String loadHintText(Resources moduleRes, String modulePkg) {
        if (moduleRes != null && modulePkg != null) {
            try {
                int id = moduleRes.getIdentifier(
                        "freeform_gesture_swipe_up_hint", "string", modulePkg);
                if (id != 0) return moduleRes.getString(id);
            } catch (Exception ignored) {}
        }
        return "Swipe up to enter Freeform";
    }

    @Nullable
    private static Drawable loadLauncherIcon(Context context) {
        try {
            Resources launcherRes = context.getResources();
            int id = launcherRes.getIdentifier(
                    "desktop_mode_ic_taskbar_menu_manage_windows",
                    "drawable", context.getPackageName());
            if (id != 0) {
                return launcherRes.getDrawable(id, context.getTheme());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Drawable createFallbackIcon() {
        GradientDrawable d =
                new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.WHITE);
        return d;
    }

    // ── Phase control ──
    public HintPhase getPhase() {
        return mPhase;
    }

    public void setPhase(@NonNull HintPhase phase) {
        if (mPhase == phase) return;
        HintPhase prev = mPhase;
        mPhase = phase;

        // Cancel ALL running animations (visibility, progress, release)
        // and restore View-level properties. The release animation sets
        // View.setAlpha(0) + setScaleX/Y(0.5) which pollutes the View;
        // reset them here. Internal transparency is controlled via
        // mHintAlpha/mIconView/onDraw, so View alpha should always be 1.
        cancelAnimators();
        animate().cancel();
        setAlpha(1f);
        setScaleX(mScale);
        setScaleY(mScale);

        // Restore position before starting new animation. After a cancelled
        // release animation the LayoutParams may be left at the collapsed-ball
        // position; adjustVisibilityAnimation(true) only animates alpha+scale
        // without calling updatePositionAndSize(), so the card would appear
        // at the stale position. Force-reposition here for visible phases.
        if (phase != HintPhase.HIDDEN) {
            updatePositionAndSize();
        }

        switch (phase) {
            case HIDDEN:
                if (prev == HintPhase.EXPAND) {
                    adjustProgressAnimation(0f, () -> adjustVisibilityAnimation(false));
                } else {
                    adjustVisibilityAnimation(false);
                }
                break;

            case SWIPE_UP_HINT:
                mDisplayText = mSwipeUpText;
                if (prev == HintPhase.HIDDEN) {
                    adjustVisibilityAnimation(true);
                } else if (prev == HintPhase.EXPAND) {
                    adjustVisibilityAnimation(true);
                    adjustProgressAnimation(0f, null);
                }
                break;

            case EXPAND:
                mDisplayText = null;
                if (prev == HintPhase.SWIPE_UP_HINT) {
                    adjustProgressAnimation(1f, () -> {});
                } else if (prev == HintPhase.HIDDEN) {
                    // Shift jumped from HIDDEN to EXPAND; show + expand simultaneously
                    adjustProgressAnimation(1f, () -> {});
                    adjustVisibilityAnimation(true);
                }
                break;
        }
    }

    public void setDisplayRotation(@SurfaceRotation int rotation) {
        if (mRotation == rotation) return;
        mRotation = rotation;
        if (mIsVisible) {
            updatePositionAndSize();
        }
    }

    public void setTaskBounds(Rect bounds) {
        if (bounds == null) {
            mHasTaskBounds = false;
            return;
        }
        mTaskBounds.set(bounds);
        mHasTaskBounds = true;
        if (mPhase == HintPhase.EXPAND) {
            updatePositionAndSize();
        }
    }

    public void initLayout() {
        try {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent == null) return;

            Class<?> lpClass = parent.getClass().getClassLoader().loadClass(
                    parent.getClass().getName() + "$LayoutParams");
            ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams)
                    lpClass.getConstructor(int.class, int.class)
                            .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (lp instanceof LayoutParams) {
                ((LayoutParams) lp).gravity =
                        Gravity.TOP | Gravity.START;
            }
            setLayoutParams(lp);
        } catch (Exception ignored) {}

        post(() -> {
            if (getParent() != null) {
                updatePositionAndSize();
            }
        });
    }

    // ── Release animation ──

    /**
     * Play the release shrink animation: collapse to an icon-sized ball,
     * then scale + alpha fade out. Calls [onComplete] when fully hidden.
     */
    public void playReleaseAnimation(@Nullable Runnable onComplete) {
        // Cancel any running internal animations
        cancelAnimators();
        animate().cancel();

        // Step 1: collapse card width to just the icon ball
        final float startScale = getScaleX();
        final Rect startLp = new Rect();
        LayoutParams lp = (LayoutParams) getLayoutParams();
        if (lp != null) {
            startLp.set(lp.leftMargin, lp.topMargin, lp.width, lp.height);
        }

        // Target: icon-sized circle (ICON_SIZE + some padding)
        float density = getResources().getDisplayMetrics().density;
        int ballSize = (int) ((ICON_SIZE_DP + 8) * density); // icon + padding
        int targetL = startLp.left + (startLp.width() - ballSize) / 2;
        int targetT = startLp.top + (startLp.height() - ballSize) / 2;

        ValueAnimator collapse = ValueAnimator.ofFloat(0f, 1f);
        collapse.setDuration(150L);
        collapse.setInterpolator(new FastOutSlowInInterpolator());
        collapse.addUpdateListener(a -> {
            float p = a.getAnimatedFraction();
            if (lp != null) {
                lp.leftMargin = (int) (startLp.left + (targetL - startLp.left) * p);
                lp.topMargin = (int) (startLp.top + (targetT - startLp.top) * p);
                lp.width = (int) (startLp.width() + (ballSize - startLp.width()) * p);
                lp.height = (int) (startLp.height() + (ballSize - startLp.height()) * p);
                setLayoutParams(lp);
            }
        });

        // Step 2: scale down + fade out
        final boolean[] wasCancelled = new boolean[1];
        ValueAnimator fadeOut = ValueAnimator.ofFloat(1f, 0f);
        fadeOut.setDuration(150L);
        fadeOut.setInterpolator(new FastOutSlowInInterpolator());
        fadeOut.addUpdateListener(a -> {
            float p = a.getAnimatedFraction();
            setScaleX(1f - p * 0.7f);
            setScaleY(1f - p * 0.7f);
            setAlpha(1f - p);
        });
        fadeOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                wasCancelled[0] = true;
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!wasCancelled[0]) {
                    mHintAlpha = 0f;
                    mIsVisible = false;
                    requestLayout();
                    if (onComplete != null) onComplete.run();
                }
            }
        });

        AnimatorSet set = new AnimatorSet();
        set.playTogether(collapse, fadeOut);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mReleaseAnimator = null;
            }
        });
        mReleaseAnimator = set;
        set.start();
    }

    // ── Animations ──

    private void adjustProgressAnimation(float target, @Nullable Runnable onEnd) {
        if (mProgressAnimator != null) mProgressAnimator.cancel();

        mProgressAnimator = ValueAnimator.ofFloat(mExpandProgress, target);
        mProgressAnimator.setInterpolator(new FastOutSlowInInterpolator());
        mProgressAnimator.addUpdateListener(a -> {
            mExpandProgress = (float) a.getAnimatedValue();
            updatePositionAndSize();
            applyContentAlpha();
        });
        mProgressAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onEnd != null) onEnd.run();
            }
        });

        long duration = (long) (ANIM_DURATION_MS * Math.abs(target - mExpandProgress));
        mProgressAnimator.setDuration(Math.max(duration, 1));
        mProgressAnimator.start();
    }

    private void adjustVisibilityAnimation(boolean visible) {
        if (mVisibilityAnimator != null) mVisibilityAnimator.cancel();

        requestLayout();

        ValueAnimator alpha = ValueAnimator.ofFloat(mHintAlpha, visible ? 1f : 0f);
        alpha.addUpdateListener(a -> {
            mHintAlpha = (float) a.getAnimatedValue();
            applyContentAlpha();
            invalidate();
        });

        ValueAnimator scale = ValueAnimator.ofFloat(mScale, visible ? 1f : 0.85f);
        scale.addUpdateListener(a -> {
            mScale = (float) a.getAnimatedValue();
            setScaleX(mScale);
            setScaleY(mScale);
        });

        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, scale);
        set.setInterpolator(new FastOutSlowInInterpolator());
        set.setDuration(ANIM_DURATION_MS);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mIsVisible = visible;
                // Only reset expand progress when hiding; during show transitions
                // the progress animator may still be running to collapse from EXPAND
                // or expand to EXPAND — let it own mExpandProgress.
                if (!visible) {
                    mExpandProgress = 0f;
                }
                mHintAlpha = visible ? 1f : 0f;
                applyContentAlpha();
                invalidate();
                if (!visible) {
                    requestLayout();
                }
            }
        });
        mVisibilityAnimator = set;
        set.start();
    }

    private void applyContentAlpha() {
        float contentAlpha = Math.max(0f, 1f - mExpandProgress * 2f);
        if (mIconView != null) {
            int iconAlpha = (int) (255 * mHintAlpha * contentAlpha);
            mIconView.setAlpha(iconAlpha / 255f);
        }
        invalidate();
    }

    private void cancelAnimators() {
        if (mProgressAnimator != null) {
            mProgressAnimator.cancel();
            mProgressAnimator = null;
        }
        if (mVisibilityAnimator != null) {
            mVisibilityAnimator.cancel();
            mVisibilityAnimator = null;
        }
        if (mReleaseAnimator != null) {
            mReleaseAnimator.cancel();
            mReleaseAnimator = null;
        }
    }

    // ── Layout & positioning ──

    private int computeHintCardWidth() {
        String text = mDisplayText != null ? mDisplayText : mSwipeUpText;
        float w = mTextPaint.measureText(text);
        return (int) (mIconPadding + mIconSize + mInnerPadding + w + mIconPadding + 0.5f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!mIsVisible && mHintAlpha == 0f
                && mVisibilityAnimator == null && mProgressAnimator == null) {
            setMeasuredDimension(0, 0);
            return;
        }

        int hintW = computeHintCardWidth();
        int hintH = (int) mCardHeight;

        if (mHasTaskBounds && mExpandProgress > 0f) {
            int taskW = mTaskBounds.width() + (int) (EXPAND_MARGIN_DP * getResources().getDisplayMetrics().density);
            int taskH = mTaskBounds.height() + (int) (EXPAND_MARGIN_DP * getResources().getDisplayMetrics().density);
            setMeasuredDimension(
                    (int) (hintW + (taskW - hintW) * mExpandProgress),
                    (int) (hintH + (taskH - hintH) * mExpandProgress));
        } else {
            setMeasuredDimension(hintW, hintH);
        }
        measureChildren(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }

    private void updatePositionAndSize() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || parent.getWidth() <= 0) return;

        int pw = parent.getWidth();
        int ph = parent.getHeight();
        int hintW = computeHintCardWidth();
        int hintH = (int) mCardHeight;
        int cw, ch, l, t;

        if (mHasTaskBounds && mExpandProgress > 0f) {
            // Expand from small card to task bounds (with 4dp margin)
            int expandPx = (int) (EXPAND_MARGIN_DP * getResources().getDisplayMetrics().density);
            int taskW = mTaskBounds.width() + expandPx;
            int taskH = mTaskBounds.height() + expandPx;
            cw = (int) (hintW + (taskW - hintW) * mExpandProgress);
            ch = (int) (hintH + (taskH - hintH) * mExpandProgress);

            getSmallCardPos(pw, ph, hintW, hintH, mPosTmp);
            float halfW = cw / 2f;
            float halfH = ch / 2f;
            int cx = (int) (mPosTmp[0] + halfW
                    + (mTaskBounds.centerX() - (mPosTmp[0] + halfW)) * mExpandProgress);
            int cy = (int) (mPosTmp[1] + halfH
                    + (mTaskBounds.centerY() - (mPosTmp[1] + halfH)) * mExpandProgress);
            l = cx - cw / 2;
            t = cy - ch / 2;
        } else {
            cw = hintW;
            ch = hintH;
            getSmallCardPos(pw, ph, cw, ch, mPosTmp);
            l = mPosTmp[0];
            t = mPosTmp[1];
        }

        l = Math.max(0, Math.min(l, pw - cw));
        t = Math.max(0, Math.min(t, ph - ch));

        LayoutParams lp = (LayoutParams) getLayoutParams();
        if (lp != null) {
            if (lp.leftMargin != l || lp.topMargin != t || lp.width != cw || lp.height != ch) {
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.leftMargin = l;
                lp.topMargin = t;
                lp.width = cw;
                lp.height = ch;
                setLayoutParams(lp);
            }
        }
        invalidate();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id)
                : (int) (24 * getResources().getDisplayMetrics().density);
    }

    private int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id)
                : (int) (48 * getResources().getDisplayMetrics().density);
    }

    private void getSmallCardPos(int pw, int ph, int cw, int ch, int[] out) {
        int sbH = statusBarHeight();
        int nbH = navigationBarHeight();
        switch (mRotation) {
            case ROTATION_90:
                out[0] = mCardMargin;
                out[1] = mCardMargin + sbH;
                break;
            case ROTATION_180:
                out[0] = mCardMargin;
                out[1] = mCardMargin + nbH;
                break;
            default:
                out[0] = pw - cw - mCardMargin;
                out[1] = mCardMargin + sbH;
                break;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (mHintAlpha <= 0.01f || (mPhase == HintPhase.HIDDEN && !mIsVisible)) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        int bgAlpha = (int) (255 * mHintAlpha);
        mBgPaint.setAlpha(bgAlpha);
        mCardRect.set(0, 0, w, h);
        canvas.drawRoundRect(mCardRect, mCornerRadius, mCornerRadius, mBgPaint);

        float contentAlpha = Math.max(0f, 1f - mExpandProgress * 2f);

        if (contentAlpha > 0f && mDisplayText != null) {
            int textAlpha = (int) (255 * mHintAlpha * contentAlpha);
            mTextPaint.setAlpha(textAlpha);
            float tx = mIconPadding + mIconSize + mInnerPadding;
            float ty = h / 2f - (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
            canvas.drawText(mDisplayText, tx, ty, mTextPaint);
        }
    }
}
