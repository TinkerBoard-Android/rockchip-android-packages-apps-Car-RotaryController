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

import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class used for finding the next focusable node when the rotary controller is rotated or
 * nudged.
 */
class NavigationHelper {
    private static final String TAG = "NavigationHelper";
    private static final boolean DEBUG = false;

    @NonNull
    private static Utils sUtils = Utils.getInstance();

    /** How many levels in the node tree to scan for focus areas. */
    private int mFocusAreaMaxDepth;

    NavigationHelper(int focusAreaMaxDepth) {
        mFocusAreaMaxDepth = focusAreaMaxDepth;
    }

    /**
     * Returns the target focusable for a nudge. The caller is responsible for recycling the result.
     *
     * @param windows    a list of windows to search from
     * @param sourceNode the current focus
     * @param direction  nudge direction, must be {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
     *                   {@link View#FOCUS_LEFT}, or {@link View#FOCUS_RIGHT}
     * @return a view that can take focus (visible, focusable and enabled) within another {@link
     *         FocusArea}, which is in the given {@code direction} from the current {@link
     *         FocusArea}, or null if not found
     */
    AccessibilityNodeInfo findNudgeTarget(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo sourceNode, int direction) {
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        AccessibilityNodeInfo targetFocusArea =
                findNudgeTargetFocusArea(windows, sourceNode, currentFocusArea, direction);
        Utils.recycleNode(currentFocusArea);
        if (targetFocusArea == null) {
            return null;
        }

        // Make a list of candidate nodes in the target FocusArea.
        List<AccessibilityNodeInfo> candidateNodes = new ArrayList<>();
        addFocusDescendants(targetFocusArea, candidateNodes);

        // Choose the best candidate as the target node.
        AccessibilityNodeInfo bestCandidate =
                chooseBestNudgeCandidate(sourceNode, candidateNodes, direction);

        Utils.recycleNodes(candidateNodes);
        Utils.recycleNode(targetFocusArea);
        return bestCandidate;
    }

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
     *         same {@link FocusArea}. If the first or last view is reached before counting up to
     *         {@code rotationCount}, the first or last view is returned. However, if there are no
     *         views that can take focus in the given {@code direction}, {@code null} is returned.
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

    /**
     * Returns the target focus area for a nudge in the given {@code direction} from the current
     * focus, or null if not found. The caller is responsible for recycling the result.
     */
    private AccessibilityNodeInfo findNudgeTargetFocusArea(
            @NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo focusedNode,
            @NonNull AccessibilityNodeInfo currentFocusArea,
            int direction) {
        AccessibilityWindowInfo currentWindow = focusedNode.getWindow();
        if (currentWindow == null) {
            loge("Currently focused window is null");
            return null;
        }

        // Build a list of candidate focus areas, starting with all the other focus areas in the
        // same window as the current focus area.
        List<AccessibilityNodeInfo> candidateFocusAreas = findFocusAreas(currentWindow);
        for (AccessibilityNodeInfo focusArea : candidateFocusAreas) {
            if (focusArea.equals(currentFocusArea)) {
                candidateFocusAreas.remove(focusArea);
                focusArea.recycle();
                break;
            }
        }

        // Add candidate focus areas in other windows in the given direction.
        List<AccessibilityWindowInfo> candidateWindows = new ArrayList<>();
        addWindowsInDirection(windows, currentWindow, candidateWindows, direction);
        currentWindow.recycle();
        for (AccessibilityWindowInfo window : candidateWindows) {
            List<AccessibilityNodeInfo> focusAreasInAnotherWindow = findFocusAreas(window);
            candidateFocusAreas.addAll(focusAreasInAnotherWindow);
        }

        // Choose the best candidate as our target focus area.
        AccessibilityNodeInfo targetFocusArea =
                chooseBestNudgeCandidate(focusedNode, candidateFocusAreas, direction);
        Utils.recycleNodes(candidateFocusAreas);

        return targetFocusArea;
    }

    /**
     * Adds all the {@code windows} in the given {@code direction} of the given {@code source}
     * window to the given list.
     */
    private void addWindowsInDirection(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityWindowInfo source,
            @NonNull List<AccessibilityWindowInfo> results,
            int direction) {
        Rect sourceBounds = new Rect();
        source.getBoundsInScreen(sourceBounds);
        Rect destBounds = new Rect();
        for (AccessibilityWindowInfo window : windows) {
            if (!window.equals(source)) {
                window.getBoundsInScreen(destBounds);

                // Even if only part of destBounds is in the given direction of sourceBounds, we
                // still include it because that part may contain the target focus area.
                if (FocusFinder.isPartiallyInDirection(sourceBounds, destBounds, direction)) {
                    results.add(window);
                }
            }
        }
    }

    /**
     * Scans the view hierarchy of the given {@code window} looking for focus areas and returns
     * them. If there are no explicitly declared {@link FocusArea}s in the top {@link
     * #mFocusAreaMaxDepth}, returns the root view. The caller is responsible for recycling the
     * result.
     */
    private @NonNull
    List<AccessibilityNodeInfo> findFocusAreas(@NonNull AccessibilityWindowInfo window) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        AccessibilityNodeInfo rootNode = window.getRoot();
        if (rootNode != null) {
            addFocusAreas(rootNode, results, mFocusAreaMaxDepth);
            if (results.isEmpty()) {
                results.add(copyNode(rootNode));
            }
            rootNode.recycle();
        }
        return results;
    }

    /**
     * Scans descendants of the given {@code rootNode} looking for focus areas and adds them to the
     * given list. It doesn't scan inside focus areas since nested focus areas aren't allowed. The
     * caller is responsible for recycling added nodes.
     *
     * @param rootNode the root to start scanning from
     * @param results  a list of focus areas to add to
     * @param maxDepth how many levels to search
     */
    private static void addFocusAreas(@NonNull AccessibilityNodeInfo rootNode,
            @NonNull List<AccessibilityNodeInfo> results,
            int maxDepth) {
        if (isFocusArea(rootNode)) {
            results.add(copyNode(rootNode));
        } else if (maxDepth > 1) {
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                AccessibilityNodeInfo childNode = rootNode.getChild(i);
                if (childNode != null) {
                    addFocusAreas(childNode, results, maxDepth - 1);
                    childNode.recycle();
                }
            }
        }
    }

    /**
     * Adds the given {@code node} and all its descendants to the given list, but only if they can
     * take focus (visible, focusable and enabled) and they are not focus areas. The caller is
     * responsible for recycling added nodes.
     */
    private static void addFocusDescendants(@NonNull AccessibilityNodeInfo node,
            @NonNull List<AccessibilityNodeInfo> results) {
        if (canTakeFocus(node) && !isFocusArea(node)) {
            results.add(copyNode(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            addFocusDescendants(child, results);
            child.recycle();
        }
    }

    /** Returns whether the given {@code node} can be focused by a rotary controller. */
    private static boolean canTakeFocus(@NonNull AccessibilityNodeInfo node) {
        return node.isVisibleToUser() && node.isFocusable() && node.isEnabled();
    }

    /**
     * Returns a copy of the best candidate from among the given {@code candidates} for a nudge
     * from {@code sourceNode} in the given {@code direction}. Returns null if none of the {@code
     * candidates} are in the given {@code direction}. The caller is responsible for recycling the
     * result.
     */
    private static AccessibilityNodeInfo chooseBestNudgeCandidate(
            @NonNull AccessibilityNodeInfo sourceNode,
            @NonNull List<AccessibilityNodeInfo> candidates,
            int direction) {
        if (candidates.isEmpty()) {
            return null;
        }
        Rect sourceBounds = new Rect();
        sourceNode.getBoundsInScreen(sourceBounds);

        AccessibilityNodeInfo bestNode = null;
        Rect bestBounds = new Rect();

        Rect candidateBounds = new Rect();
        for (AccessibilityNodeInfo candidate : candidates) {
            candidate.getBoundsInScreen(candidateBounds);
            if (FocusFinder.isCandidate(sourceBounds, candidateBounds, direction)) {
                if (bestNode == null || FocusFinder.isBetterCandidate(
                        direction, sourceBounds, candidateBounds, bestBounds)) {
                    bestNode = candidate;
                    bestBounds.set(candidateBounds);
                }
            }
        }
        return copyNode(bestNode);
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

    private static void loge(String str) {
        if (DEBUG) {
            Log.e(TAG, str);
        }
    }

    private static void logw(String str) {
        if (DEBUG) {
            Log.w(TAG, str);
        }
    }
}
