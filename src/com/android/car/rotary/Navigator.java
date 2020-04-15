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
import android.os.SystemClock;
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
class Navigator {
    @NonNull
    private static Utils sUtils = Utils.getInstance();

    private final RotaryCache mRotaryCache;

    Navigator(@RotaryCache.CacheType int focusHistoryCacheType,
            int focusHistoryCacheSize,
            int focusHistoryExpirationTimeMs,
            @RotaryCache.CacheType int focusAreaHistoryCacheType,
            int focusAreaHistoryCacheSize,
            int focusAreaHistoryExpirationTimeMs) {
        mRotaryCache = new RotaryCache(focusHistoryCacheType,
                focusHistoryCacheSize,
                focusHistoryExpirationTimeMs,
                focusAreaHistoryCacheType,
                focusAreaHistoryCacheSize,
                focusAreaHistoryExpirationTimeMs);
    }

    /** Clears focus area history cache. */
    void clearFocusAreaHistory() {
        mRotaryCache.clearFocusAreaHistory();
    }

    /** Caches the focused node by focus area. */
    void saveFocusedNode(@NonNull AccessibilityNodeInfo focusedNode) {
        AccessibilityNodeInfo focusArea = getAncestorFocusArea(focusedNode);
        mRotaryCache.saveFocusedNode(focusArea, focusedNode, SystemClock.elapsedRealtime());
        Utils.recycleNode(focusArea);
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

        // Return the recently focused node within the target focus area, if any.
        AccessibilityNodeInfo cachedFocusedNode =
                mRotaryCache.getFocusedNode(targetFocusArea, SystemClock.elapsedRealtime());
        if (cachedFocusedNode != null) {
            Utils.recycleNode(targetFocusArea);
            return cachedFocusedNode;
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

    /**
     * Searches the {@code rootNode} and its descendants in depth-first order, and returns the first
     * focus descendant (a node inside a focus area that can take focus) if any, or returns null if
     * not found. The caller is responsible for recycling the result.
     */
    static AccessibilityNodeInfo findFirstFocusDescendant(@NonNull AccessibilityNodeInfo rootNode) {
        // First try finding the first focus area and searching forward from the focus area. This
        // is a quick way to find the first node but it doesn't always work.
        AccessibilityNodeInfo focusDescendant = findFirstFocus(rootNode);
        if (focusDescendant != null) {
            return focusDescendant;
        }

        // Fall back to tree traversal.
        L.w("Falling back to tree traversal");
        focusDescendant = findDepthFirstFocus(rootNode);
        if (focusDescendant == null) {
            L.w("No node can take focus in the current window");
        }
        return focusDescendant;
    }

    /** Sets a mock Utils instance for testing. */
    @VisibleForTesting
    static void setUtils(@NonNull Utils utils) {
        sUtils = utils;
        RotaryCache.setUtils(utils);
    }

    /**
     * Searches the {@code rootNode} and its descendants in depth-first order for the first focus
     * area, and returns the first node that can take focus in tab order from the focus area.
     * The return value could be a node inside or outside the first focus area, or null if not
     * found. The caller is responsible for recycling result.
     */
    private static AccessibilityNodeInfo findFirstFocus(@NonNull AccessibilityNodeInfo rootNode) {
        AccessibilityNodeInfo focusArea = findFirstFocusArea(rootNode);
        if (focusArea == null) {
            L.e("No FocusArea in the tree");
            return null;
        }

        AccessibilityNodeInfo targetNode = focusArea.focusSearch(View.FOCUS_FORWARD);
        focusArea.recycle();
        return targetNode;
    }

    /**
     * Searches the given {@code node} and its descendants in depth-first order, and returns the
     * first {@link FocusArea}, or returns null if not found. The caller is responsible for
     * recycling the result.
     */
    private static AccessibilityNodeInfo findFirstFocusArea(@NonNull AccessibilityNodeInfo node) {
        if (isFocusArea(node)) {
            return copyNode(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childNode = node.getChild(i);
            if (childNode != null) {
                AccessibilityNodeInfo focusArea = findFirstFocusArea(childNode);
                childNode.recycle();
                if (focusArea != null) {
                    return focusArea;
                }
            }
        }
        return null;
    }

    /**
     * Searches the given {@code node} and its descendants in depth-first order, and returns the
     * first node that can take focus, or returns null if not found. The caller is responsible for
     * recycling result.
     */
    private static AccessibilityNodeInfo findDepthFirstFocus(@NonNull AccessibilityNodeInfo node) {
        if (canTakeFocus(node)) {
            return copyNode(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findDepthFirstFocus(child);
            child.recycle();
            if (result != null) {
                return result;
            }
        }
        return null;
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
        long elapsedRealtime = SystemClock.elapsedRealtime();
        // If there is a target focus area in the cache, returns it.
        AccessibilityNodeInfo cachedTargetFocusArea =
                mRotaryCache.getTargetFocusArea(currentFocusArea, direction, elapsedRealtime);
        if (cachedTargetFocusArea != null) {
            // We already got nudge history in the cache. Before nudging back, let's save "nudge
            // back" history.
            mRotaryCache.saveTargetFocusArea(
                    currentFocusArea, cachedTargetFocusArea, direction, elapsedRealtime);
            return cachedTargetFocusArea;
        }

        // No target focus area in the cache; we need to search the node tree to find it.
        AccessibilityWindowInfo currentWindow = focusedNode.getWindow();
        if (currentWindow == null) {
            L.e("Currently focused window is null");
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

        if (targetFocusArea != null) {
            // Save nudge history.
            mRotaryCache.saveTargetFocusArea(
                    currentFocusArea, targetFocusArea, direction, elapsedRealtime);
        }

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
     * them. If there are no explicitly declared {@link FocusArea}s, returns the root view. The
     * caller is responsible for recycling the
     * result.
     */
    private @NonNull
    List<AccessibilityNodeInfo> findFocusAreas(@NonNull AccessibilityWindowInfo window) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        AccessibilityNodeInfo rootNode = window.getRoot();
        if (rootNode != null) {
            addFocusAreas(rootNode, results);
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
     */
    private static void addFocusAreas(@NonNull AccessibilityNodeInfo rootNode,
            @NonNull List<AccessibilityNodeInfo> results) {
        if (isFocusArea(rootNode)) {
            results.add(copyNode(rootNode));
        } else {
            for (int i = 0; i < rootNode.getChildCount(); i++) {
                AccessibilityNodeInfo childNode = rootNode.getChild(i);
                if (childNode != null) {
                    addFocusAreas(childNode, results);
                    childNode.recycle();
                }
            }
        }
    }

    /**
     * Adds the given {@code node} and all its focus descendants (nodes that can take focus) to the
     * given list. The caller is responsible for recycling added nodes.
     */
    private static void addFocusDescendants(@NonNull AccessibilityNodeInfo node,
            @NonNull List<AccessibilityNodeInfo> results) {
        if (canTakeFocus(node)) {
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
        // TODO(b/151458195): remove "!isFocusArea(node)" once FocusArea is not focusable.
        return node.isVisibleToUser() && node.isFocusable() && node.isEnabled()
                && !isFocusArea(node);
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
            L.w("Couldn't find ancestor focus area for given node: " + node);
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
}
