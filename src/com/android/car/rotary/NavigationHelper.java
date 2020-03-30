/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.car.rotary;

import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * A helper class used for finding the next focusable node when the rotary controller is rotated or
 * nudged.
 */
class NavigationHelper {
    private static final String TAG = "NavigationHelper";
    private static final boolean DEBUG = false;

    @NonNull
    private static Utils sUtils = Utils.getInstance();

    /**
     * Returns the target focusable for a rotate. The caller is responsible for recycling the
     * result.
     *
     * @param sourceNode    the current focus
     * @param direction     rotate direction, must be {@link View#FOCUS_FORWARD} or {@link
     *                      View#FOCUS_BACKWARD}
     * @param rotationCount the number of "ticks" to rotate. Only count nodes that can take focus (
     *                      visible, focusable and enabled).
     * @return a focusable view in the given {@code direction} from the current focus within the
     * same {@link FocusArea}. If the first or last view is reached before counting up to {@code
     * rotationCount}, the first or last view is returned. However, if there are no views that can
     * take focus in the given {@code direction}, {@code null} is returned.
     */
    static AccessibilityNodeInfo findRotateTarget(@NonNull AccessibilityNodeInfo sourceNode,
            int direction, int rotationCount) {
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        AccessibilityNodeInfo targetNode = copyNode(sourceNode);
        for (int i = 0; i < rotationCount; i++) {
            AccessibilityNodeInfo nextTargetNode = targetNode.focusSearch(direction);
            AccessibilityNodeInfo targetFocusArea =
                    nextTargetNode == null ? null : getAncestorFocusArea(nextTargetNode);
            if (nextTargetNode != null && !isFocusArea(nextTargetNode)
                    // TODO(b/151458195): remove !isFocusArea(nextTargetNode) once FocusArea is
                    //  not focusable.
                    && currentFocusArea.equals(targetFocusArea)) {
                Utils.recycleNode(targetNode);
                Utils.recycleNode(targetFocusArea);
                targetNode = nextTargetNode;
            } else {
                Utils.recycleNode(nextTargetNode);
                Utils.recycleNode(targetFocusArea);
                break;
            }
        }
        Utils.recycleNode(currentFocusArea);
        if (sourceNode.equals(targetNode)) {
            targetNode.recycle();
            return null;
        }
        return targetNode;
    }

    /** Sets a mock Utils instance for testing. */
    @VisibleForTesting
    static void setUtils(@NonNull Utils utils) {
        sUtils = utils;
        // TODO(b/151349253): RotaryCache.setUtils(utils);
    }

    private static AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return sUtils.copyNode(node);
    }

    /**
     * Finds the closest ancestor focus area of the given {@code node}. If the given {@code node}
     * is a focus area, returns it; if there are no explicitly declared {@link FocusArea}s among the
     * ancestors of this view, returns the root view. The caller is responsible for recycling the
     * result.
     */
    private @NonNull
    static AccessibilityNodeInfo getAncestorFocusArea(@NonNull AccessibilityNodeInfo node) {
        if (isFocusArea(node)) {
            return copyNode(node);
        }
        AccessibilityNodeInfo parentNode = node.getParent();
        if (parentNode == null) {
            logw("Couldn't find ancestor focus area for given node: " + node);
            return copyNode(node);
        }
        AccessibilityNodeInfo result = getAncestorFocusArea(parentNode);
        parentNode.recycle();
        return result;
    }

    /** Returns whether the given {@code node} represents a {@link FocusArea}. */
    private static boolean isFocusArea(@NonNull AccessibilityNodeInfo node) {
        CharSequence className = node.getClassName() == null ? "" : node.getClassName();
        // TODO(b/151458195): return FocusArea.class.getName().contentEquals(className);
        String focusArea = "com.android.car.ui.FocusArea";
        return focusArea.contentEquals(className);
    }

    private static void logw(String str) {
        if (DEBUG) {
            Log.w(TAG, str);
        }
    }
}
