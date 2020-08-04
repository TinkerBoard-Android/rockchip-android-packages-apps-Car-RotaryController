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

import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD;

import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_HIGHLIGHT_BOTTOM_PADDING;
import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_HIGHLIGHT_LEFT_PADDING;
import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_HIGHLIGHT_RIGHT_PADDING;
import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_HIGHLIGHT_TOP_PADDING;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusParkingView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A helper class used for finding the next focusable node when the rotary controller is rotated or
 * nudged.
 */
class Navigator {

    @NonNull
    private NodeCopier mNodeCopier = new NodeCopier();

    @NonNull
    private final TreeTraverser mTreeTraverser = new TreeTraverser();

    private final RotaryCache mRotaryCache;

    private final int mHunLeft;
    private final int mHunRight;

    @View.FocusRealDirection
    private int mHunNudgeDirection;

    Navigator(@RotaryCache.CacheType int focusHistoryCacheType,
            int focusHistoryCacheSize,
            int focusHistoryExpirationTimeMs,
            @RotaryCache.CacheType int focusAreaHistoryCacheType,
            int focusAreaHistoryCacheSize,
            int focusAreaHistoryExpirationTimeMs,
            @RotaryCache.CacheType int focusWindowCacheType,
            int focusWindowCacheSize,
            int focusWindowExpirationTimeMs,
            int hunLeft,
            int hunRight,
            boolean showHunOnBottom) {
        mRotaryCache = new RotaryCache(focusHistoryCacheType,
                focusHistoryCacheSize,
                focusHistoryExpirationTimeMs,
                focusAreaHistoryCacheType,
                focusAreaHistoryCacheSize,
                focusAreaHistoryExpirationTimeMs,
                focusWindowCacheType,
                focusWindowCacheSize,
                focusWindowExpirationTimeMs);
        mHunLeft = hunLeft;
        mHunRight = hunRight;
        mHunNudgeDirection = showHunOnBottom ? View.FOCUS_DOWN : View.FOCUS_UP;
    }

    /** Clears focus area history cache. */
    void clearFocusAreaHistory() {
        mRotaryCache.clearFocusAreaHistory();
    }

    /** Caches the focused node by focus area and by window. */
    void saveFocusedNode(@NonNull AccessibilityNodeInfo focusedNode) {
        long elapsedRealtime = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo focusArea = getAncestorFocusArea(focusedNode);
        mRotaryCache.saveFocusedNode(focusArea, focusedNode, elapsedRealtime);
        mRotaryCache.saveWindowFocus(focusedNode, elapsedRealtime);
        Utils.recycleNode(focusArea);
    }

    /**
     * Returns the most recently focused valid node in window {@code windowId}, or {@code null} if
     * there are no valid nodes saved by {@link #saveFocusedNode}. The caller is responsible for
     * recycling the result.
     */
    AccessibilityNodeInfo getMostRecentFocus(int windowId) {
        return mRotaryCache.getMostRecentFocus(windowId, SystemClock.elapsedRealtime());
    }

    /**
     * Returns the target focusable for a nudge. Convenience method for when the {@code editNode} is
     * null.
     *
     * @see #findNudgeTarget(List, AccessibilityNodeInfo, int, AccessibilityNodeInfo)
     */
    @Nullable
    AccessibilityNodeInfo findNudgeTarget(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo sourceNode, int direction) {
        return findNudgeTarget(windows, sourceNode, direction, null);
    }

    /**
     * Returns the target focusable for a nudge:
     * <ol>
     *     <li>If the HUN is present and the nudge is towards it, a focusable in the HUN is
     *         returned. See {@link #findHunNudgeTarget} for details.
     *     <li>If the nudge is leaving the IME, return focus to the view that was left focused when
     *         the IME appeared.
     *     <li>Otherwise, a target focus area is chosen, either from the focus area history or by
     *         choosing the best candidate. See {@link #findNudgeTargetFocusArea} for details.
     *     <li>Finally a focusable view within the chosen focus area is chosen, either from the
     *         focus history or by choosing the best candidate.
     * </ol>
     * Saves nudge history except when nudging out of the IME. The caller is responsible for
     * recycling the result.
     *
     * @param windows    a list of windows to search from
     * @param sourceNode the current focus
     * @param direction  nudge direction, must be {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
     *                   {@link View#FOCUS_LEFT}, or {@link View#FOCUS_RIGHT}
     * @param editNode   node currently being edited by the IME, if any
     * @return a view that can take focus (visible, focusable and enabled) within another {@link
     *         FocusArea}, which is in the given {@code direction} from the current {@link
     *         FocusArea}, or null if not found
     */
    @Nullable
    AccessibilityNodeInfo findNudgeTarget(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo sourceNode, int direction,
            @Nullable AccessibilityNodeInfo editNode) {
        // If the user is trying to nudge to the HUN, search for a focus area in the HUN window.
        AccessibilityNodeInfo hunNudgeTarget = findHunNudgeTarget(windows, sourceNode, direction);
        if (hunNudgeTarget != null) {
            return hunNudgeTarget;
        }

        long elapsedRealtime = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        AccessibilityNodeInfo targetFocusArea =
                findNudgeTargetFocusArea(windows, sourceNode, currentFocusArea, direction);
        if (targetFocusArea == null) {
            Utils.recycleNode(currentFocusArea);
            return null;
        }

        // If the user is nudging out of an IME, return to the field they were editing, if any.
        // Don't save nudge history in this case.
        AccessibilityWindowInfo sourceWindow =
                Utils.findWindowWithId(windows, sourceNode.getWindowId());
        AccessibilityWindowInfo targetWindow =
                Utils.findWindowWithId(windows, targetFocusArea.getWindowId());
        if (sourceWindow != null && targetWindow != null
                && sourceWindow.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                && targetWindow.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
            if (editNode != null && editNode.getWindowId() == targetWindow.getId()) {
                Utils.recycleNode(currentFocusArea);
                Utils.recycleNode(targetFocusArea);
                return copyNode(editNode);
            }
        }

        // Return the recently focused node within the target focus area, if any.
        AccessibilityNodeInfo cachedFocusedNode =
                mRotaryCache.getFocusedNode(targetFocusArea, elapsedRealtime);
        if (cachedFocusedNode != null) {
            // Save nudge history.
            mRotaryCache.saveTargetFocusArea(
                    currentFocusArea, targetFocusArea, direction, elapsedRealtime);

            Utils.recycleNode(currentFocusArea);
            Utils.recycleNode(targetFocusArea);
            return cachedFocusedNode;
        }

        // Make a list of candidate nodes in the target FocusArea.
        List<AccessibilityNodeInfo> candidateNodes = new ArrayList<>();
        addFocusDescendants(targetFocusArea, candidateNodes);

        // Choose the best candidate as the target node.
        AccessibilityNodeInfo bestCandidate =
                chooseBestNudgeCandidate(sourceNode, candidateNodes, direction);

        // Save nudge history if we're going to move focus.
        if (bestCandidate != null) {
            mRotaryCache.saveTargetFocusArea(
                    currentFocusArea, targetFocusArea, direction, elapsedRealtime);
        }

        Utils.recycleNode(currentFocusArea);
        Utils.recycleNodes(candidateNodes);
        Utils.recycleNode(targetFocusArea);
        return bestCandidate;
    }

    /**
     * Returns the target focusable for a nudge to the HUN if the HUN is present and the nudge is
     * in the right direction. The target focusable is chosen as follows:
     * <ol>
     *     <li>The best candidate focus area is chosen. If there aren't any valid candidates, the
     *         first (only) focus area in the HUN is used. This happens when nudging from a view
     *         obscured by the HUN.
     *     <li>The focus history is checked. If one of the focusable views in the chosen focus area
     *         is in the cache, it's returned.
     *     <li>Finally the best candidate focusable view in the chosen focus area is selected.
     *         Again, if there aren't any candidates, the first focusable view is chosen.
     * </ol>
     * The caller is responsible for recycling the result.
     *
     * @param windows    a list of windows to search from
     * @param sourceNode the current focus
     * @param direction  nudge direction, must be {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
     *                   {@link View#FOCUS_LEFT}, or {@link View#FOCUS_RIGHT}
     * @return a view that can take focus (visible, focusable and enabled) within the HUN, or null
     *         if the HUN isn't present, the nudge isn't in the direction of the HUN, or the HUN
     *         contains no views that can take focus
     */
    @Nullable
    private AccessibilityNodeInfo findHunNudgeTarget(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo sourceNode, int direction) {
        if (direction != mHunNudgeDirection) {
            return null;
        }

        // Find the HUN window, if any.
        AccessibilityWindowInfo hunWindow = null;
        for (AccessibilityWindowInfo window : windows) {
            if (isHunWindow(window)) {
                hunWindow = window;
                break;
            }
        }
        if (hunWindow == null) {
            return null;
        }

        // Find the target focus area within the HUN. The HUN may overlap the source node, in which
        // case the geometric search will fail. The fallback is to use the first (typically only)
        // focus area.
        List<AccessibilityNodeInfo> hunFocusAreas = findFocusAreas(hunWindow);
        removeEmptyFocusAreas(hunFocusAreas);
        AccessibilityNodeInfo targetFocusArea =
                chooseBestNudgeCandidate(sourceNode, hunFocusAreas, direction);
        if (targetFocusArea == null && !hunFocusAreas.isEmpty()) {
            targetFocusArea = copyNode(hunFocusAreas.get(0));
        }
        Utils.recycleNodes(hunFocusAreas);
        if (targetFocusArea == null) {
            return null;
        }

        // Save nudge history.
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        long elapsedRealtime = SystemClock.elapsedRealtime();
        mRotaryCache.saveTargetFocusArea(
                currentFocusArea, targetFocusArea, direction, elapsedRealtime);

        // Check the cache to see if a node was focused in the HUN.
        AccessibilityNodeInfo cachedFocusedNode =
                mRotaryCache.getFocusedNode(targetFocusArea, elapsedRealtime);
        if (cachedFocusedNode != null) {
            Utils.recycleNode(targetFocusArea);
            Utils.recycleNode(currentFocusArea);
            return cachedFocusedNode;
        }

        // Choose the best candidate target node. The HUN may overlap the source node, in which
        // case the geometric search will fail. The fallback is to use the first focusable node.
        List<AccessibilityNodeInfo> candidateNodes = new ArrayList<>();
        addFocusDescendants(targetFocusArea, candidateNodes);
        AccessibilityNodeInfo bestCandidate =
                chooseBestNudgeCandidate(sourceNode, candidateNodes, direction);
        if (bestCandidate == null && !candidateNodes.isEmpty()) {
            bestCandidate = copyNode(candidateNodes.get(0));
        }
        Utils.recycleNodes(candidateNodes);
        Utils.recycleNode(targetFocusArea);
        Utils.recycleNode(currentFocusArea);
        return bestCandidate;
    }

    /**
     * Returns the target focusable for a rotate. The caller is responsible for recycling the node
     * in the result.
     *
     * <p>Limits navigation to focusable views within a scrollable container's viewport, if any.
     *
     * @param sourceNode    the current focus
     * @param direction     rotate direction, must be {@link View#FOCUS_FORWARD} or {@link
     *                      View#FOCUS_BACKWARD}
     * @param rotationCount the number of "ticks" to rotate. Only count nodes that can take focus
     *                      (visible, focusable and enabled). If {@code skipNode} is encountered, it
     *                      isn't counted.
     * @return a FindRotateTargetResult containing a node and a count of the number of times the
     *         search advanced to another node. The node represents a focusable view in the given
     *         {@code direction} from the current focus within the same {@link FocusArea}. If the
     *         first or last view is reached before counting up to {@code rotationCount}, the first
     *         or last view is returned. However, if there are no views that can take focus in the
     *         given {@code direction}, {@code null} is returned.
     */
    @Nullable
    FindRotateTargetResult findRotateTarget(
            @NonNull AccessibilityNodeInfo sourceNode, int direction, int rotationCount) {
        int advancedCount = 0;
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        AccessibilityNodeInfo candidate = copyNode(sourceNode);
        AccessibilityNodeInfo target = null;
        while (advancedCount < rotationCount) {
            AccessibilityNodeInfo nextCandidate = candidate.focusSearch(direction);
            AccessibilityNodeInfo candidateFocusArea =
                    nextCandidate == null ? null : getAncestorFocusArea(nextCandidate);

            // Only advance to nextCandidate if:
            // 1. it's in the same focus area,
            // 2. and it isn't a FocusParkingView (this is to prevent wrap-around when there is only
            //    one focus area in the window, including when the root node is treated as a focus
            //    area),
            // 3. and nextCandidate is different from candidate (if sourceNode is the first
            //    focusable node in the window, searching backward will return sourceNode itself).
            if (nextCandidate != null && currentFocusArea.equals(candidateFocusArea)
                    && !Utils.isFocusParkingView(nextCandidate)
                    && !nextCandidate.equals(candidate)) {
                // We need to skip nextTargetNode if:
                // 1. it can't perform focus action (focusSearch() may return a node with zero
                //    width and height),
                // 2. or it is a scrollable container with descendants that can take focus. We skip
                //    the container because we want to focus on its element directly. We don't skip
                //    a scrollable container without descendants that can take focus because we want
                //    to focus on it, thus we can scroll it when the rotary controller is rotated.
                if (!Utils.canPerformFocus(nextCandidate)
                        || (Utils.isScrollableContainer(nextCandidate)
                        && Utils.descendantCanTakeFocus(nextCandidate))) {
                    Utils.recycleNode(candidate);
                    Utils.recycleNode(candidateFocusArea);
                    candidate = nextCandidate;
                    continue;
                }
                // If we're navigating through a scrolling view that can scroll in the specified
                // direction and the next view is off-screen, don't advance to it. (We'll scroll
                // the remaining count instead.)
                Rect nextTargetBounds = getBoundsInScreen(nextCandidate);
                AccessibilityNodeInfo scrollableContainer = findScrollableContainer(candidate);
                AccessibilityNodeInfo.AccessibilityAction scrollAction =
                        direction == View.FOCUS_FORWARD
                                ? ACTION_SCROLL_FORWARD
                                : ACTION_SCROLL_BACKWARD;
                if (scrollableContainer != null
                        && scrollableContainer.getActionList().contains(scrollAction)) {
                    Rect scrollBounds = getBoundsInScreen(scrollableContainer);
                    boolean intersects = nextTargetBounds.intersect(scrollBounds);
                    if (!intersects) {
                        Utils.recycleNode(nextCandidate);
                        Utils.recycleNode(candidateFocusArea);
                        break;
                    }
                }
                Utils.recycleNode(scrollableContainer);

                Utils.recycleNode(candidate);
                Utils.recycleNode(candidateFocusArea);
                candidate = nextCandidate;
                Utils.recycleNode(target);
                target = copyNode(candidate);
                advancedCount++;
            } else {
                Utils.recycleNode(nextCandidate);
                Utils.recycleNode(candidateFocusArea);
                break;
            }
        }
        currentFocusArea.recycle();
        candidate.recycle();
        if (sourceNode.equals(target)) {
            L.e("Wrap-around on the same node");
            target.recycle();
            return null;
        }
        return target == null ? null : new FindRotateTargetResult(target, advancedCount);
    }

    /**
     * Searches the {@code rootNode} and its descendants in depth-first order, and returns the first
     * focus descendant (a node inside a focus area that can take focus) if any, or returns null if
     * not found. The caller is responsible for recycling the result.
     */
    AccessibilityNodeInfo findFirstFocusDescendant(@NonNull AccessibilityNodeInfo rootNode) {
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
    void setNodeCopier(@NonNull NodeCopier nodeCopier) {
        mNodeCopier = nodeCopier;
        mTreeTraverser.setNodeCopier(nodeCopier);
        mRotaryCache.setNodeCopier(nodeCopier);
    }

    /**
     * Searches all the nodes in the {@code window}, and returns the node representing a {@link
     * FocusParkingView}, if any, or returns null if not found. The caller is responsible for
     * recycling the result.
     */
    AccessibilityNodeInfo findFocusParkingView(@NonNull AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) {
            L.e("No root node in " + window);
            return null;
        }
        AccessibilityNodeInfo focusParkingView = findFocusParkingView(root);
        root.recycle();
        return focusParkingView;
    }

    /**
     * Searches the {@code node} and its descendants in depth-first order, and returns the node
     * representing a {@link FocusParkingView}, if any, or returns null if not found. The caller is
     * responsible for recycling the result.
     */
    private AccessibilityNodeInfo findFocusParkingView(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node, /* skipPredicate= */ Utils::isFocusArea,
                /* targetPredicate= */ Utils::isFocusParkingView);
    }

    /**
     * Searches the {@code rootNode} and its descendants in depth-first order for the first focus
     * area, and returns the first node that can take focus in tab order from the focus area.
     * The return value could be a node inside or outside the first focus area, or null if not
     * found. The caller is responsible for recycling result.
     */
    private AccessibilityNodeInfo findFirstFocus(@NonNull AccessibilityNodeInfo rootNode) {
        AccessibilityNodeInfo focusArea = findFirstFocusArea(rootNode);
        if (focusArea == null) {
            L.w("No FocusArea in the tree");
            // rootNode is an implicit focus area if no explicit FocusAreas are specified.
            focusArea = copyNode(rootNode);
        }

        AccessibilityNodeInfo targetNode = focusArea.focusSearch(View.FOCUS_FORWARD);
        AccessibilityNodeInfo firstTarget = copyNode(targetNode);
        // focusSearch() searches in the active window, which has at least one FocusParkingView. We
        // need to skip it.
        while (targetNode != null && Utils.isFocusParkingView(targetNode)) {
            L.d("Found FocusParkingView, continue focusSearch() ...");
            AccessibilityNodeInfo nextTargetNode = targetNode.focusSearch(View.FOCUS_FORWARD);
            targetNode.recycle();
            targetNode = nextTargetNode;

            // If we found the same FocusParkingView again, it means all the focusable views in
            // current window are FocusParkingViews, so we should just return null.
            if (firstTarget.equals(targetNode)) {
                L.w("Stop focusSearch() because there is no view to take focus except "
                        + "FocusParkingViews");
                Utils.recycleNode(targetNode);
                targetNode = null;
                break;
            }
        }
        Utils.recycleNode(firstTarget);
        focusArea.recycle();
        return targetNode;
    }

    /**
     * Searches the given {@code node} and its descendants in depth-first order, and returns the
     * first {@link FocusArea}, or returns null if not found. The caller is responsible for
     * recycling the result.
     */
    private AccessibilityNodeInfo findFirstFocusArea(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node, Utils::isFocusArea);
    }

    /**
     * Searches the given {@code node} and its descendants in depth-first order, and returns the
     * first node that can take focus, or returns null if not found. The caller is responsible for
     * recycling result.
     */
    private AccessibilityNodeInfo findDepthFirstFocus(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node, Utils::canTakeFocus);
    }

    /**
     * Returns the target focus area for a nudge in the given {@code direction} from the current
     * focus, or null if not found. Checks the cache first. If nothing is found in the cache,
     * returns the best nudge target from among all the candidate focus areas. The caller is
     * responsible for updating the cache and recycling the result.
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
        if (cachedTargetFocusArea != null && Utils.canHaveFocus(cachedTargetFocusArea)) {
            return cachedTargetFocusArea;
        }
        Utils.recycleNode(cachedTargetFocusArea);

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

        // Exclude focus areas that have no descendants to take focus, because once we found a best
        // candidate focus area, we don't dig into other ones. If it has no descendants to take
        // focus, the nudge will fail.
        removeEmptyFocusAreas(candidateFocusAreas);

        // Choose the best candidate as our target focus area.
        AccessibilityNodeInfo targetFocusArea =
                chooseBestNudgeCandidate(focusedNode, candidateFocusAreas, direction);
        Utils.recycleNodes(candidateFocusAreas);
        return targetFocusArea;
    }

    private static void removeEmptyFocusAreas(@NonNull List<AccessibilityNodeInfo> focusAreas) {
        for (Iterator<AccessibilityNodeInfo> iterator = focusAreas.iterator();
                iterator.hasNext(); ) {
            AccessibilityNodeInfo focusArea = iterator.next();
            if (!Utils.canHaveFocus(focusArea)) {
                iterator.remove();
                focusArea.recycle();
            }
        }
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
     * caller is responsible for recycling the result.
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
     * Returns whether the given window is the Heads-up Notification (HUN) window. The HUN window
     * is identified by the left and right edges. The top and bottom vary depending on whether the
     * HUN appears at the top or bottom of the screen and on the height of the notification being
     * displayed so they aren't used.
     */
    boolean isHunWindow(@NonNull AccessibilityWindowInfo window) {
        if (window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) {
            return false;
        }
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        return bounds.left == mHunLeft && bounds.right == mHunRight;
    }

    /**
     * Searches from the given node up through its ancestors to the containing focus area, looking
     * for a node that's marked as horizontally or vertically scrollable. Returns a copy of the
     * first such node or null if none is found. The caller is responsible for recycling the result.
     */
    @Nullable
    AccessibilityNodeInfo findScrollableContainer(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.findNodeOrAncestor(node, /* stopPredicate= */ Utils::isFocusArea,
                /* targetPredicate= */ Utils::isScrollableContainer);
    }

    /**
     * Returns the previous node in Tab order before {@code referenceNode} within
     * {@code containerNode} or null if none. The caller is responsible for recycling the result.
     */
    @Nullable
    static AccessibilityNodeInfo findPreviousFocusableDescendant(
            @NonNull AccessibilityNodeInfo containerNode,
            @NonNull AccessibilityNodeInfo referenceNode) {
        return findFocusableDescendantInDirection(containerNode, referenceNode,
                View.FOCUS_BACKWARD);
    }

    /**
     * Returns the next node after {@code referenceNode} in Tab order within {@code containerNode}
     * or null if none. The caller is responsible for recycling the result.
     */
    @Nullable
    static AccessibilityNodeInfo findNextFocusableDescendant(
            @NonNull AccessibilityNodeInfo containerNode,
            @NonNull AccessibilityNodeInfo referenceNode) {
        return findFocusableDescendantInDirection(containerNode, referenceNode, View.FOCUS_FORWARD);
    }

    /**
     * Returns the previous node before {@code referenceNode} in Tab order or the next node after
     * {@code referenceNode} in Tab order, depending on {@code direction}. The search is limited to
     * descendants of {@code containerNode}. Returns null if there are no focusable descendants in
     * the given direction. The caller is responsible for recycling the result.
     *
     * @param containerNode the node with descendants
     * @param referenceNode a descendant of {@code containerNode} to start from
     * @param direction     {@link View#FOCUS_FORWARD} or {@link View#FOCUS_BACKWARD}
     * @return the node before or after {@code referenceNode} or null if none
     */
    @Nullable
    static AccessibilityNodeInfo findFocusableDescendantInDirection(
            @NonNull AccessibilityNodeInfo containerNode,
            @NonNull AccessibilityNodeInfo referenceNode,
            int direction) {
        AccessibilityNodeInfo targetNode = referenceNode.focusSearch(direction);
        if (targetNode == null
                || targetNode.equals(containerNode)
                || !Utils.canTakeFocus(targetNode)
                || !Utils.isDescendant(containerNode, targetNode)) {
            Utils.recycleNode(targetNode);
            return null;
        }
        if (targetNode.equals(referenceNode)) {
            L.w((direction == View.FOCUS_FORWARD ? "Next" : "Previous")
                    + " node is the same node: " + referenceNode);
            Utils.recycleNode(targetNode);
            return null;
        }
        return targetNode;
    }

    /**
     * Returns the first descendant of {@code node} which can take focus. The nodes are searched in
     * in depth-first order, not including {@code node} itself. If no descendant can take focus,
     * null is returned. The caller is responsible for recycling the result.
     */
    @Nullable
    AccessibilityNodeInfo findFirstFocusableDescendant(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node,
                candidateNode -> candidateNode != node && Utils.canTakeFocus(candidateNode));
    }

    /**
     * Returns the last descendant of {@code node} which can take focus. The nodes are searched in
     * reverse depth-first order, not including {@code node} itself. If no descendant can take
     * focus, null is returned. The caller is responsible for recycling the result.
     */
    @Nullable
    AccessibilityNodeInfo findLastFocusableDescendant(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.reverseDepthFirstSearch(node,
                candidateNode -> candidateNode != node && Utils.canTakeFocus(candidateNode));
    }

    /**
     * Scans descendants of the given {@code rootNode} looking for focus areas and adds them to the
     * given list. It doesn't scan inside focus areas since nested focus areas aren't allowed. The
     * caller is responsible for recycling added nodes.
     *
     * @param rootNode the root to start scanning from
     * @param results  a list of focus areas to add to
     */
    private void addFocusAreas(@NonNull AccessibilityNodeInfo rootNode,
            @NonNull List<AccessibilityNodeInfo> results) {
        mTreeTraverser.depthFirstSelect(rootNode, Utils::isFocusArea, results);
    }

    /**
     * Adds the given {@code node} and all its focus descendants (nodes that can take focus) to the
     * given list. The caller is responsible for recycling added nodes.
     */
    private void addFocusDescendants(@NonNull AccessibilityNodeInfo node,
            @NonNull List<AccessibilityNodeInfo> results) {
        mTreeTraverser.depthFirstSelect(node, Utils::canTakeFocus, results);
    }

    /**
     * Returns a copy of the best candidate from among the given {@code candidates} for a nudge
     * from {@code sourceNode} in the given {@code direction}. Returns null if none of the {@code
     * candidates} are in the given {@code direction}. The caller is responsible for recycling the
     * result.
     *
     * @param candidates could be a list of {@link FocusArea}s, or a list of focusable views
     */
    @Nullable
    private AccessibilityNodeInfo chooseBestNudgeCandidate(
            @NonNull AccessibilityNodeInfo sourceNode,
            @NonNull List<AccessibilityNodeInfo> candidates,
            int direction) {
        if (candidates.isEmpty()) {
            return null;
        }
        Rect sourceBounds = getBoundsInScreen(sourceNode);
        AccessibilityNodeInfo sourceFocusArea = getAncestorFocusArea(sourceNode);
        Rect sourceFocusAreaBounds = getBoundsInScreen(sourceFocusArea);
        sourceFocusArea.recycle();
        AccessibilityNodeInfo bestNode = null;
        Rect bestBounds = new Rect();

        for (AccessibilityNodeInfo candidate : candidates) {
            if (isCandidate(sourceBounds, sourceFocusAreaBounds, candidate, direction)) {
                Rect candidateBounds = getBoundsInScreen(candidate);
                if (bestNode == null || FocusFinder.isBetterCandidate(
                        direction, sourceBounds, candidateBounds, bestBounds)) {
                    bestNode = candidate;
                    bestBounds.set(candidateBounds);
                }
            }
        }
        return copyNode(bestNode);
    }

    /**
     * Returns whether the given {@code node} is a candidate from {@code sourceBounds} to the given
     * {@code direction}.
     * <p>
     * To be a candidate, the node
     * <ul>
     *     <li>must be considered a candidate by {@link FocusFinder#isCandidate} if it represents a
     *         focusable view within a focus area
     *     <li>must be in the {@code direction} of the {@code sourceFocusAreaBounds} and one of its
     *         focusable descendants must be a candidate if it represents a focus area
     * </ul>
     */
    private boolean isCandidate(@NonNull Rect sourceBounds,
            @NonNull Rect sourceFocusAreaBounds,
            @NonNull AccessibilityNodeInfo node,
            int direction) {
        AccessibilityNodeInfo candidate = mTreeTraverser.depthFirstSearch(node,
                /* skipPredicate= */ candidateNode -> {
                    if (Utils.canTakeFocus(candidateNode)) {
                        return false;
                    }
                    // If a node can't take focus, it represents a focus area. If the focus area is
                    // not in the given direction of the source focus area, it's not a candidate,
                    // so we should return true to stop searching.
                    Rect candidateBounds = getBoundsInScreen(candidateNode);
                    return !FocusFinder.isInDirection(
                            sourceFocusAreaBounds, candidateBounds, direction);
                },
                /* targetPredicate= */ candidateNode -> {
                    // If a node can't take focus, it represents a focus area, so we return false to
                    // skip the node and let it search its descendants.
                    if (!Utils.canTakeFocus(candidateNode)) {
                        return false;
                    }
                    // The node represents a focusable view in a focus area, so check the geometry.
                    Rect candidateBounds = getBoundsInScreen(candidateNode);
                    return FocusFinder.isCandidate(sourceBounds, candidateBounds, direction);
                });
        if (candidate == null) {
            return false;
        }
        candidate.recycle();
        return true;
    }

    private AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return mNodeCopier.copy(node);
    }

    /**
     * Finds the closest ancestor focus area of the given {@code node}. If the given {@code node}
     * is a focus area, returns it; if there are no explicitly declared {@link FocusArea}s among the
     * ancestors of this view, returns the root view. The caller is responsible for recycling the
     * result.
     */
    @NonNull
    AccessibilityNodeInfo getAncestorFocusArea(@NonNull AccessibilityNodeInfo node) {
        NodePredicate isFocusAreaOrRoot = candidateNode -> {
            if (Utils.isFocusArea(candidateNode)) {
                // The candidateNode is a focus area.
                return true;
            }
            AccessibilityNodeInfo parent = candidateNode.getParent();
            if (parent == null) {
                // The candidateNode is the root node.
                return true;
            }
            parent.recycle();
            return false;
        };
        AccessibilityNodeInfo result = mTreeTraverser.findNodeOrAncestor(node, isFocusAreaOrRoot);
        if (result == null || !Utils.isFocusArea(result)) {
            L.w("Couldn't find ancestor focus area for given node: " + node);
        }
        return result;
    }

    /** An interface for a lambda that returns an {@link AccessibilityNodeInfo}. */
    interface NodeProvider {
        AccessibilityNodeInfo provideNode();
    }

    /** Result from {@link #findRotateTarget}. */
    static class FindRotateTargetResult {
        @NonNull final AccessibilityNodeInfo node;
        final int advancedCount;

        FindRotateTargetResult(@NonNull AccessibilityNodeInfo node, int advancedCount) {
            this.node = node;
            this.advancedCount = advancedCount;
        }
    }

    @NonNull
    private static Rect getBoundsInScreen(@NonNull AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (Utils.isFocusArea(node)) {
            // The bounds of a FocusArea is its bounds minus its highlight paddings.
            Bundle bundle = node.getExtras();
            bounds.left += bundle.getInt(FOCUS_AREA_HIGHLIGHT_LEFT_PADDING);
            bounds.right -= bundle.getInt(FOCUS_AREA_HIGHLIGHT_RIGHT_PADDING);
            bounds.top += bundle.getInt(FOCUS_AREA_HIGHLIGHT_TOP_PADDING);
            bounds.bottom -= bundle.getInt(FOCUS_AREA_HIGHLIGHT_BOTTOM_PADDING);
        }
        return bounds;
    }
}
