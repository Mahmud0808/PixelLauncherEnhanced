package com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.ViewGroup;

import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.FreeformHintView.HintPhase;

public class FreeformHintViewController {

    private static FreeformHintView sHintView;
    private static ViewGroup sDragLayer;

    /**
     * @param dragLayer       DragLayer instance
     * @param context         Launcher context
     * @param moduleResources Module's own Resources (for i18n string loading)
     * @param modulePackage   Module's package name
     */
    public static void init(ViewGroup dragLayer, Context context,
                             Resources moduleResources, String modulePackage) {
        if (sHintView != null) return;
        sDragLayer = dragLayer;

        sHintView = new FreeformHintView(context, moduleResources, modulePackage);
        sHintView.setVisibility(ViewGroup.VISIBLE);

        // Create correct LayoutParams BEFORE addView to avoid InsettableFrameLayout crash
        ViewGroup.LayoutParams lp = createLayoutParams(dragLayer);
        sHintView.setLayoutParams(lp);

        dragLayer.addView(sHintView);
        sHintView.initLayout();
    }

    /**
     * Ensures FreeformHintView exists and is attached to DragLayer.
     * If DragLayer was cleared (e.g. removeAllViews during recents cleanup),
     * re-creates the view. Safe to call on every gesture start.
     */
    public static void ensureFreeformHintView(ViewGroup dragLayer, Context context,
                                               Resources moduleResources, String modulePackage) {
        if (sHintView != null && sHintView.getParent() != null
                && sHintView.isAttachedToWindow()) {
            return; // already alive
        }
        // Stale reference: clear and re-init
        sHintView = null;
        sDragLayer = null;
        init(dragLayer, context, moduleResources, modulePackage);
    }

    private static ViewGroup.LayoutParams createLayoutParams(ViewGroup parent) {
        try {
            Class<?> lpClass = parent.getClass().getClassLoader().loadClass(
                    "com.android.launcher3.InsettableFrameLayout$LayoutParams");
            ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams)
                    lpClass.getConstructor(int.class, int.class)
                            .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                ((android.widget.FrameLayout.LayoutParams) lp).gravity =
                        Gravity.TOP | Gravity.START;
            }
            return lp;
        } catch (Exception e) {
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            return lp;
        }
    }

    public static FreeformHintView getHintView() {
        return sHintView;
    }

    public static void setPhase(HintPhase phase) {
        if (sHintView != null) sHintView.setPhase(phase);
    }

    public static void setTaskBounds(Rect bounds) {
        if (sHintView != null) sHintView.setTaskBounds(bounds);
    }

    public static void setDisplayRotation(int rotation) {
        if (sHintView != null) sHintView.setDisplayRotation(rotation);
    }

    public static void reset() {
        if (sHintView != null) {
            sHintView.setPhase(HintPhase.HIDDEN);
        }
    }
}
