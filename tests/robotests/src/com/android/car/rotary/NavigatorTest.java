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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;

import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusParkingView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class NavigatorTest {
    private static final String FOCUS_AREA_CLASS_NAME = FocusArea.class.getName();
    private static final String FOCUS_PARKING_VIEW_CLASS_NAME = FocusParkingView.class.getName();

    @Mock
    private NodeCopier mNodeCopier;

    private Navigator mNavigator;

    private List<AccessibilityNodeInfo> mNodeList;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mNavigator = new Navigator(
                /* focusHistoryCacheType= */ RotaryCache.CACHE_TYPE_NEVER_EXPIRE,
                /* focusHistoryCacheSize= */ 10,
                /* focusHistoryExpirationTimeMs= */ 0,
                /* focusAreaHistoryCacheType= */ RotaryCache.CACHE_TYPE_NEVER_EXPIRE,
                /* focusAreaHistoryCacheSize= */ 5,
                /* focusAreaHistoryExpirationTimeMs= */ 0);

        // Utils#copyNode() doesn't work when passed a mock node, so we create a mock method
        // which returns the passed node itself rather than a copy. As a result, nodes created by
        // the mock method (such as |target| in testFindRotateTarget()) shouldn't be recycled.
        doAnswer(returnsFirstArg()).when(mNodeCopier).copy(any(AccessibilityNodeInfo.class));

        mNavigator.setNodeCopier(mNodeCopier);

        mNodeList = new ArrayList<>();
    }

    @Test
    public void testSetRootNodeForWindow() {
        AccessibilityWindowInfo window = new WindowBuilder().build();
        AccessibilityNodeInfo root = new NodeBuilder().build();
        setRootNodeForWindow(root, window);

        assertThat(window.getRoot()).isSameAs(root);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     *              root
     *               |
     *           focusArea
     *          /    |    \
     *        /      |     \
     *    button1 button2 button3
     */
    @Test
    public void testFindRotateTarget() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(button3);
        when(button3.focusSearch(direction)).thenReturn(null);

        // Rotate once, the focus should move from button1 to button2.
        AccessibilityNodeInfo target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target).isSameAs(button2);

        // Rotate twice, the focus should move from button1 to button3.
        target = mNavigator.findRotateTarget(button1, direction, 2);
        assertThat(target).isSameAs(button3);

        // Rotate 3 times and exceed the boundary, the focus should stay at the boundary.
        target = mNavigator.findRotateTarget(button1, direction, 3);
        assertThat(target).isSameAs(button3);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     *                     root
     *                    /    \
     *                   /      \
     *      focusParkingView   focusArea
     *                           /    \
     *                          /      \
     *                       button1  button2
     */
    @Test
    public void testFindRotateTargetNoWrapAround() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusParkingView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;
        when(button1.focusSearch(direction)).thenReturn(button2);
        when(button2.focusSearch(direction)).thenReturn(focusParkingView);
        when(focusParkingView.focusSearch(direction)).thenReturn(button1);

        // Rotate at the end of focus area, no wrap-around should happen.
        AccessibilityNodeInfo target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     *
     *    ****************leftWindow**************    **************rightWindow****************
     *    *                                      *    *                                       *
     *    *  ========topLeft focus area========  *    *  ========topRight focus area========  *
     *    *  =                                =  *    *  =                                 =  *
     *    *  =  .............. .............  =  *    *  =  .............                  =  *
     *    *  =  .           .  .           .  =  *    *  =  .           .                  =  *
     *    *  =  . topLeft1  .  .  topLeft2 .  =  *    *  =  . topRight1 .                  =  *
     *    *  =  .           .  .           .  =  *    *  =  .           .                  =  *
     *    *  =  .............. .............  =  *    *  =  .............                  =  *
     *    *  =                                =  *    *  =                                 =  *
     *    *  ==================================  *    *  ===================================  *
     *    *                                      *    *                                       *
     *    *  =======middleLeft focus area======  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  =  .............. .............  =  *    *                                       *
     *    *  =  .           .  .           .  =  *    *                                       *
     *    *  =  .middleLeft1.  .middleLeft2.  =  *    *                                       *
     *    *  =  . disabled  .  . disabled  .  =  *    *                                       *
     *    *  =  .............  .............  =  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  ==================================  *    *                                       *
     *    *                                      *    *                                       *
     *    *  =======bottomLeft focus area======  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  =  .............. .............  =  *    *                                       *
     *    *  =  .           .  .           .  =  *    *                                       *
     *    *  =  .bottomLeft1.  .bottomLeft2.  =  *    *                                       *
     *    *  =  .           .  .           .  =  *    *                                       *
     *    *  =  .............  .............  =  *    *                                       *
     *    *  =                                =  *    *                                       *
     *    *  ==================================  *    *                                       *
     *    *                                      *    *                                       *
     *    ****************************************    *****************************************
     */
    @Test
    public void testFindNudgeTarget() {
        // There are 2 windows. This is the left window.
        Rect leftWindowBounds = new Rect(0, 0, 400, 1200);
        AccessibilityWindowInfo leftWindow = new WindowBuilder()
                .setBoundsInScreen(leftWindowBounds)
                .build();
        // We must specify window and boundsInScreen for each node when finding nudge target.
        AccessibilityNodeInfo leftRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setBoundsInScreen(leftWindowBounds)
                .build();
        setRootNodeForWindow(leftRoot, leftWindow);

        // Left window has 3 vertically aligned focus areas.
        AccessibilityNodeInfo topLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 400, 400))
                .build();
        AccessibilityNodeInfo middleLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 400, 400, 800))
                .build();
        AccessibilityNodeInfo bottomLeft = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 800, 400, 1200))
                .setInViewTree(true)
                .build();

        // Each focus area but middleLeft has 2 horizontally aligned views that can take focus.
        AccessibilityNodeInfo topLeft1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(topLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 0, 200, 400))
                .build();
        AccessibilityNodeInfo topLeft2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(topLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(200, 0, 400, 400))
                .build();
        AccessibilityNodeInfo bottomLeft1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setInViewTree(true)
                .setBoundsInScreen(new Rect(0, 800, 200, 1200))
                .build();
        AccessibilityNodeInfo bottomLeft2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(200, 800, 400, 1200))
                .build();

        // middleLeft focus area has 2 disabled views, so that it will be skipped when nudging.
        AccessibilityNodeInfo middleLeft1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .setInViewTree(true)
                .setBoundsInScreen(new Rect(0, 400, 200, 800))
                .build();
        AccessibilityNodeInfo middleLeft2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(bottomLeft)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(false)
                .setBoundsInScreen(new Rect(200, 400, 400, 800))
                .build();

        // This is the right window.
        Rect rightWindowBounds = new Rect(400, 0, 800, 1200);
        AccessibilityWindowInfo rightWindow = new WindowBuilder()
                .setBoundsInScreen(rightWindowBounds)
                .build();
        AccessibilityNodeInfo rightRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setBoundsInScreen(rightWindowBounds)
                .build();
        setRootNodeForWindow(rightRoot, rightWindow);

        // Right window has 1 focus area.
        AccessibilityNodeInfo topRight = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(400, 0, 800, 400))
                .build();

        // The focus area has 1 view that can take focus.
        AccessibilityNodeInfo topRight1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(topRight)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(400, 0, 600, 400))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(leftWindow);
        windows.add(rightWindow);

        // Nudge within the same window.
        AccessibilityNodeInfo target =
                mNavigator.findNudgeTarget(windows, topLeft1, View.FOCUS_DOWN);
        assertThat(target).isSameAs(bottomLeft1);

        // Reach to the boundary.
        target = mNavigator.findNudgeTarget(windows, topLeft1, View.FOCUS_UP);
        assertThat(target).isNull();

        // Nudge to a different window.
        target = mNavigator.findNudgeTarget(windows, topRight1, View.FOCUS_LEFT);
        assertThat(target).isSameAs(topLeft2);

        // When nudging back, the focus should return to the previously focused node within the
        // previous focus area, rather than the geometrically close node or focus area.

        // Firstly, we need to save the focused node.
        mNavigator.saveFocusedNode(bottomLeft1);
        // Then nudge to right.
        target = mNavigator.findNudgeTarget(windows, bottomLeft1, View.FOCUS_RIGHT);
        assertThat(target).isSameAs(topRight1);
        // Then nudge back.
        target = mNavigator.findNudgeTarget(windows, topRight1, View.FOCUS_LEFT);
        assertThat(target).isSameAs(bottomLeft1);
    }

    /**
     * Tests {@link Navigator#findNudgeTarget} in the following layout:
     *
     *    ****************leftWindow**************    **************rightWindow***************
     *    *                                      *    *                                      *
     *    *  ===left focus area===   parking1    *    *   parking2   ===right focus area===  *
     *    *  =                   =               *    *              =                    =  *
     *    *  =  .............    =               *    *              =  .............     =  *
     *    *  =  .           .    =               *    *              =  .           .     =  *
     *    *  =  .   left    .    =               *    *              =  .   right   .     =  *
     *    *  =  .           .    =               *    *              =  .           .     =  *
     *    *  =  .............    =               *    *              =  .............     =  *
     *    *  =                   =               *    *              =                    =  *
     *    *  =====================               *    *              ======================  *
     *    *                                      *    *                                      *
     *    ****************************************    *****************************************
     */
    @Test
    public void testFindNudgeTargetWithFocusParkingView() {
        // There are 2 windows. This is the left window.
        Rect leftWindowBounds = new Rect(0, 0, 400, 400);
        AccessibilityWindowInfo leftWindow = new WindowBuilder()
                .setBoundsInScreen(leftWindowBounds)
                .build();
        AccessibilityNodeInfo leftRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setBoundsInScreen(leftWindowBounds)
                .build();
        setRootNodeForWindow(leftRoot, leftWindow);

        // Left focus area and its view inside.
        AccessibilityNodeInfo leftFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(0, 0, 300, 400))
                .build();
        AccessibilityNodeInfo left = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(0, 0, 300, 400))
                .build();

        // Left focus parking view.
        AccessibilityNodeInfo parking1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(leftWindow)
                .setParent(leftFocusArea)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(350, 0, 351, 1))
                .build();

        // Right window.
        Rect rightWindowBounds = new Rect(400, 0, 800, 400);
        AccessibilityWindowInfo rightWindow = new WindowBuilder()
                .setBoundsInScreen(rightWindowBounds)
                .build();
        AccessibilityNodeInfo rightRoot = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setBoundsInScreen(rightWindowBounds)
                .build();
        setRootNodeForWindow(rightRoot, rightWindow);

        // Right focus area and its view inside.
        AccessibilityNodeInfo rightFocusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightRoot)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .setBoundsInScreen(new Rect(500, 0, 800, 400))
                .build();
        AccessibilityNodeInfo right = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightFocusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(500, 0, 800, 400))
                .build();

        // Right focus parking view.
        AccessibilityNodeInfo parking2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setWindow(rightWindow)
                .setParent(rightFocusArea)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .setBoundsInScreen(new Rect(450, 0, 451, 1))
                .build();

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(leftWindow);
        windows.add(rightWindow);

        // Nudge from left window to right window.
        AccessibilityNodeInfo target = mNavigator.findNudgeTarget(windows, left, View.FOCUS_RIGHT);
        assertThat(target).isSameAs(right);
    }

    /**
     * Tests {@link Navigator#findFirstFocusDescendant} in the following node tree:
     *                   root
     *                  /    \
     *                /       \
     *          focusArea1  focusArea2
     *           /   \          /   \
     *         /      \        /     \
     *     button1 button2 button3 button4
     */
    @Test
    public void testFindFirstFocusDescendant() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusArea1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();
        AccessibilityNodeInfo focusArea2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea1)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setNodeList(mNodeList)
                .setParent(focusArea2)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;

        // Search forward from the focus area.
        when(focusArea1.focusSearch(direction)).thenReturn(button2);
        AccessibilityNodeInfo target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button2);

        // Fall back to tree traversal.
        when(focusArea1.focusSearch(direction)).thenReturn(null);
        target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button1);
    }

    /**
     * Tests {@link Navigator#findFirstFocusDescendant} in the following node tree:
     *                     root
     *                    /    \
     *                   /      \
     *      focusParkingView   focusArea
     *                           /    \
     *                          /      \
     *                      button1   button2
     */
    @Test
    public void testFindFirstFocusDescendantWithFocusParkingView() {
        AccessibilityNodeInfo root = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo focusParkingView = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_PARKING_VIEW_CLASS_NAME)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo focusArea = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(root)
                .setClassName(FOCUS_AREA_CLASS_NAME)
                .build();

        AccessibilityNodeInfo button1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();
        AccessibilityNodeInfo button2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(focusArea)
                .setFocusable(true)
                .setVisibleToUser(true)
                .setEnabled(true)
                .build();

        int direction = View.FOCUS_FORWARD;

        // Search forward from the focus area.
        when(focusArea.focusSearch(direction)).thenReturn(button2);
        AccessibilityNodeInfo target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button2);

        // Fall back to tree traversal.
        when(focusArea.focusSearch(direction)).thenReturn(null);
        target = mNavigator.findFirstFocusDescendant(root);
        assertThat(target).isSameAs(button1);
    }

    /** Sets the {@code root} node in the {@code window}'s hierarchy. */
    private void setRootNodeForWindow(@NonNull AccessibilityNodeInfo root,
            @NonNull AccessibilityWindowInfo window) {
        when(window.getRoot()).thenReturn(root);
    }
}
