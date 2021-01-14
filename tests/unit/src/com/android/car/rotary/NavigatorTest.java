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

import android.app.Activity;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import androidx.annotation.LayoutRes;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.car.rotary.Navigator.FindRotateTargetResult;
import com.android.car.rotary.ui.TestRecyclerViewAdapter;

import com.google.common.collect.Lists;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NavigatorTest {

    private static UiAutomation sUiAutomoation;

    private ActivityTestRule<NavigatorTestActivity> mActivityRule;
    private Intent mIntent;
    private Rect mHunWindowBounds;
    private Navigator mNavigator;
    private AccessibilityNodeInfo mWindowRoot;

    @BeforeClass
    public static void oneTimeSetup() {
        sUiAutomoation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @Before
    public void setUp() {
        mActivityRule = new ActivityTestRule<>(NavigatorTestActivity.class);
        mIntent = new Intent();
        mIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        mHunWindowBounds = new Rect(50, 10, 950, 200);
        // The values of displayWidth and displayHeight don't affect the test, so just use 0.
        mNavigator = new Navigator(/* displayWidth= */ 0, /* displayHeight= */ 0,
                mHunWindowBounds.left, mHunWindowBounds.right,/* showHunOnBottom= */ false);
    }

    @After
    public void tearDown() {
        mActivityRule.finishActivity();
        Utils.recycleNode(mWindowRoot);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *              root
     *               |
     *           focusArea
     *          /    |    \
     *        /      |     \
     *    button1 button2 button3
     * </pre>
     */
    @Test
    public void testFindRotateTarget() {
        initActivity(R.layout.navigator_find_rotate_target_test_activity);

        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo button2 = createNode("button2");
        AccessibilityNodeInfo button3 = createNode("button3");

        int direction = View.FOCUS_FORWARD;

        // Rotate once, the focus should move from button1 to button2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isEqualTo(button2);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(target.node);

        // Rotate twice, the focus should move from button1 to button3.
        target = mNavigator.findRotateTarget(button1, direction, 2);
        assertThat(target.node).isEqualTo(button3);
        assertThat(target.advancedCount).isEqualTo(2);

        Utils.recycleNodes(button1, button2, button3, target.node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                   /      \
     *      focusParkingView   focusArea
     *                           /    \
     *                          /      \
     *                       button1  button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround() {
        initActivity(R.layout.navigator_find_rotate_target_no_wrap_test_1_activity);

        AccessibilityNodeInfo button2 = createNode("button2");

        int direction = View.FOCUS_FORWARD;

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();

        Utils.recycleNode(button2);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                   /      \
     *           focusArea  genericFocusParkingView
     *            /    \
     *           /      \
     *       button1  button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAroundWithGenericFpv() {
        initActivity(R.layout.navigator_find_rotate_target_no_wrap_test_1_generic_fpv_activity);

        AccessibilityNodeInfo button2 = createNode("button2");

        int direction = View.FOCUS_FORWARD;

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();

        Utils.recycleNode(button2);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *      focusParkingView   button1   button2
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround2() {
        initActivity(R.layout.navigator_find_rotate_target_no_wrap_test_2_activity);

        AccessibilityNodeInfo button2 = createNode("button2");

        int direction = View.FOCUS_FORWARD;

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();

        Utils.recycleNode(button2);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *              button1   button2  genericFocusParkingView
     * </pre>
     */
    @Test
    public void testFindRotateTargetNoWrapAround2WithGenericFpv() {
        initActivity(R.layout.navigator_find_rotate_target_no_wrap_test_2_generic_fpv_activity);

        AccessibilityNodeInfo button2 = createNode("button2");

        int direction = View.FOCUS_FORWARD;

        // Rotate at the end of focus area, no wrap-around should happen.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button2, direction, 1);
        assertThat(target).isNull();

        Utils.recycleNode(button2);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *      ============ focus area ============
     *      =                                  =
     *      =  *****     recycler view    **** =
     *      =  *                             * =
     *      =  *  ........ text 1   ........ * =
     *      =  *  .        visible         . * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  *  ........ text 2   ........ * =
     *      =  *  .        visible         . * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  *  ........ text 3   ........ * =
     *      =  *  .        offscreen ....... * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  ******************************* =
     *      =                                  =
     *      ============ focus area ============
     * </pre>
     */
    @Test
    public void testFindRotateTargetDoesNotSkipOffscreenNode() {
        initActivity(
                R.layout.navigator_find_rotate_target_does_not_skip_offscreen_node_test_activity);

        Activity activity = mActivityRule.getActivity();
        RecyclerView recyclerView = activity.findViewById(R.id.scrollable);
        recyclerView.post(() -> {
            TestRecyclerViewAdapter adapter = new TestRecyclerViewAdapter(activity);
            adapter.setItemsFocusable(true);
            recyclerView.setAdapter(adapter);
            adapter.setItems(Lists.newArrayList("Test Item 1", "Test Item 2", "Test Item 3"));
            adapter.notifyDataSetChanged();
        });

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        AccessibilityNodeInfo text1 = createNodeByText("Test Item 1");
        AccessibilityNodeInfo text2 = createNodeByText("Test Item 2");

        int direction = View.FOCUS_FORWARD;

        FindRotateTargetResult target = mNavigator.findRotateTarget(text1, direction, 1);
        assertThat(target.node).isEqualTo(text2);
        Utils.recycleNode(target.node);

        AccessibilityNodeInfo text3 = createNodeByText("Test Item 3");
        assertThat(text3).isNull();

        target = mNavigator.findRotateTarget(text2, direction, 1);
        // Need to query for text3 after the rotation, so that it is visible on the screen for the
        // instrumentation to pick it up.
        text3 = createNodeByText("Test Item 3");
        assertThat(target.node).isEqualTo(text3);
        Utils.recycleNodes(text1, text2, text3, target.node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *              root
     *               |
     *           focusArea
     *          /    |    \
     *        /      |     \
     *    button1 button2 button3
     * </pre>
     * where {@code button2} is not focusable.
     */
    @Test
    public void testFindRotateTargetSkipNodeThatCannotPerformFocus() {
        initActivity(R.layout.navigator_find_rotate_target_test_activity);

        Activity activity = mActivityRule.getActivity();
        View rootView = activity.findViewById(R.id.root);
        rootView.post(() -> {
            Button button2 = activity.findViewById(R.id.button2);
            button2.setFocusable(false);
        });

        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo button3 = createNode("button3");

        int direction = View.FOCUS_FORWARD;

        // Rotate from button1, it should skip the empty view.
        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isEqualTo(button3);

        Utils.recycleNodes(button1, button3, target.node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *              button1   scrollable  button2
     *                       recyclerView
     *                            |
     *                      non-focusable
     * </pre>
     */
    @Test
    public void testFindRotateTargetReturnScrollableContainer() {
        initActivity(R.layout.navigator_find_rotate_target_scrollable_container_test_activity);

        Activity activity = mActivityRule.getActivity();
        RecyclerView recyclerView = activity.findViewById(R.id.scrollable);
        recyclerView.post(() -> {
            TestRecyclerViewAdapter adapter = new TestRecyclerViewAdapter(activity);
            recyclerView.setAdapter(adapter);
            adapter.setItems(Collections.singletonList("Test Item 1"));
            adapter.notifyDataSetChanged();
        });

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        AccessibilityNodeInfo windowRoot = sUiAutomoation.getRootInActiveWindow();
        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo scrollable = createNode("scrollable");

        int direction = View.FOCUS_FORWARD;

        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isEqualTo(scrollable);

        Utils.recycleNodes(button1, scrollable, target.node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                          root
     *                         /  |  \
     *                       /    |    \
     *                     /      |      \
     *                   /        |        \
     *             button1  non-scrollable  button2
     *                       recyclerView
     * </pre>
     */
    @Test
    public void testFindRotateTargetSkipScrollableContainer() {
        initActivity(
                R.layout.navigator_find_rotate_target_skip_scrollable_container_test_1_activity);

        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo button2 = createNode("button2");

        int direction = View.FOCUS_FORWARD;

        FindRotateTargetResult target = mNavigator.findRotateTarget(button1, direction, 1);
        assertThat(target.node).isEqualTo(button2);

        Utils.recycleNodes(button1, button2, target.node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *                     root
     *                    /    \
     *                  /       \
     *    focusParkingView    scrollable
     *                        container
     *                           /    \
     *                          /      \
     *                  focusable1    focusable2
     * </pre>
     */
    @Test
    public void testFindRotateTargetSkipScrollableContainer2() {
        initActivity(
                R.layout.navigator_find_rotate_target_skip_scrollable_container_test_2_activity);

        Activity activity = mActivityRule.getActivity();
        RecyclerView recyclerView = activity.findViewById(R.id.scrollable);
        recyclerView.post(() -> {
            TestRecyclerViewAdapter adapter = new TestRecyclerViewAdapter(activity);
            adapter.setItemsFocusable(true);
            recyclerView.setAdapter(adapter);
            adapter.setItems(Lists.newArrayList("Test Item 1", "Test Item 2"));
            adapter.notifyDataSetChanged();
        });

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        AccessibilityNodeInfo focusable1 = createNodeByText("Test Item 1");
        AccessibilityNodeInfo focusable2 = createNodeByText("Test Item 2");

        int direction = View.FOCUS_BACKWARD;

        FindRotateTargetResult target = mNavigator.findRotateTarget(focusable2, direction, 2);
        assertThat(target.node).isEqualTo(focusable1);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(focusable1, focusable2, target.node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following node tree:
     * <pre>
     *             node
     * </pre>
     */
    @Test
    public void testFindRotateTargetWithOneNode() {
        initActivity(
                R.layout.navigator_find_rotate_target_one_node_test_activity);

        AccessibilityNodeInfo node = createNode("node");

        int direction = View.FOCUS_BACKWARD;

        FindRotateTargetResult target = mNavigator.findRotateTarget(node, direction, 1);
        assertThat(target).isNull();

        Utils.recycleNode(node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following layout:
     * <pre>
     *      ============ focus area 1 ==========
     *      =                                  =
     *      =  ***** scrollable container **** =
     *      =  *                             * =
     *      =  *  ........ text 1   ........ * =
     *      =  *  .                        . * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  *  ........ text 2   ........ * =
     *      =  *  .                        . * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  ******************************* =
     *      =                                  =
     *      ============ focus area 1 ==========
     *      ============ focus area 2 ==========
     *      =     ........ text 3   ........   =
     *      =     .                        .   =
     *      =     ..........................   =
     *      ============ focus area 2 ==========
     * </pre>
     */
    @Test
    public void testFindRotateTargetInScrollableContainer1() {
        initActivity(
                R.layout.navigator_find_rotate_target_in_scrollable_container_test_1_activity);

        Activity activity = mActivityRule.getActivity();
        RecyclerView recyclerView = activity.findViewById(R.id.scrollable);
        recyclerView.post(() -> {
            TestRecyclerViewAdapter adapter = new TestRecyclerViewAdapter(activity);
            adapter.setItemsFocusable(true);
            recyclerView.setAdapter(adapter);
            adapter.setItems(Lists.newArrayList("Test Item 1", "Test Item 2"));
            adapter.notifyDataSetChanged();
        });

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        AccessibilityNodeInfo text1 = createNodeByText("Test Item 1");
        AccessibilityNodeInfo text2 = createNodeByText("Test Item 2");

        int direction = View.FOCUS_FORWARD;

        // Rotate once, the focus should move from text1 to text2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(text1, direction, 1);
        assertThat(target.node).isEqualTo(text2);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(target.node);

        // Rotate twice, the focus should move from text1 to text2 since text3 is not a
        // descendant of the scrollable container.
        target = mNavigator.findRotateTarget(text1, direction, 2);
        assertThat(target.node).isEqualTo(text2);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(target.node);

        // Rotate three times should do the same.
        target = mNavigator.findRotateTarget(text1, direction, 3);
        assertThat(target.node).isEqualTo(text2);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(text1, text2, target.node);
    }

    /**
     * Tests {@link Navigator#findRotateTarget} in the following layout:
     * <pre>
     *      ============ focus area ============
     *      =                                  =
     *      =  ***** scrollable container **** =
     *      =  *                             * =
     *      =  *  ........ text 1   ........ * =
     *      =  *  .        visible         . * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  *  ........ text 2   ........ * =
     *      =  *  .        visible         . * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  *  ........ text 3   ........ * =
     *      =  *  .        not visible     . * =
     *      =  *  .......................... * =
     *      =  *                             * =
     *      =  ******************************* =
     *      =                                  =
     *      ============ focus area ============
     * </pre>
     * where {@code text 3} is off the screen.
     */
    @Test
    public void testFindRotateTargetInScrollableContainer2() {
        initActivity(
                R.layout.navigator_find_rotate_target_in_scrollable_container_test_2_activity);

        Activity activity = mActivityRule.getActivity();
        RecyclerView recyclerView = activity.findViewById(R.id.scrollable);
        recyclerView.post(() -> {
            TestRecyclerViewAdapter adapter = new TestRecyclerViewAdapter(activity);
            adapter.setItemsFocusable(true);
            recyclerView.setAdapter(adapter);
            adapter.setItems(Lists.newArrayList("Test Item 1", "Test Item 2", "Test Item 3"));
            adapter.notifyDataSetChanged();
        });

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        AccessibilityNodeInfo text1 = createNodeByText("Test Item 1");
        AccessibilityNodeInfo text2 = createNodeByText("Test Item 2");

        int direction = View.FOCUS_FORWARD;

        // Rotate once, the focus should move from text1 to text2.
        FindRotateTargetResult target = mNavigator.findRotateTarget(text1, direction, 1);
        assertThat(target.node).isEqualTo(text2);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(target.node);

        // Rotate twice, the focus should move from text1 to text2 since text3 is off the
        // screen.
        target = mNavigator.findRotateTarget(text1, direction, 2);
        assertThat(target.node).isEqualTo(text2);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(target.node);

        // Rotate three times should do the same.
        target = mNavigator.findRotateTarget(text1, direction, 3);
        assertThat(target.node).isEqualTo(text2);
        assertThat(target.advancedCount).isEqualTo(1);

        Utils.recycleNodes(text1, text2, target.node);
    }

    /**
     * Tests {@link Navigator#findScrollableContainer} in the following node tree:
     * <pre>
     *                root
     *                 |
     *                 |
     *             focusArea
     *              /     \
     *            /         \
     *        scrolling    button2
     *        container
     *           |
     *           |
     *       container
     *           |
     *           |
     *        button1
     * </pre>
     */
    @Test
    public void testFindScrollableContainer() {
        initActivity(R.layout.navigator_find_scrollable_container_test_activity);

        AccessibilityNodeInfo scrollableContainer = createNode("scrollableContainer");
        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo button2 = createNode("button2");

        AccessibilityNodeInfo target = mNavigator.findScrollableContainer(button1);
        assertThat(target).isEqualTo(scrollableContainer);

        Utils.recycleNodes(target);

        target = mNavigator.findScrollableContainer(button2);
        assertThat(target).isNull();

        Utils.recycleNodes(scrollableContainer, button1, button2);
    }

    /**
     * Tests {@link Navigator#findFocusableDescendantInDirection} going
     * {@link View#FOCUS_BACKWARD} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     */
    @Test
    public void testFindFocusableVisibleDescendantInDirectionBackward() {
        initActivity(R.layout.navigator_find_focusable_descendant_test_activity);

        AccessibilityNodeInfo container1 = createNode("container1");
        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo button2 = createNode("button2");
        AccessibilityNodeInfo container2 = createNode("container2");
        AccessibilityNodeInfo button3 = createNode("button3");
        AccessibilityNodeInfo button4 = createNode("button4");

        int direction = View.FOCUS_BACKWARD;

        AccessibilityNodeInfo target = mNavigator.findFocusableDescendantInDirection(container2,
                button4, direction);
        assertThat(target).isEqualTo(button3);

        Utils.recycleNode(target);

        target = mNavigator.findFocusableDescendantInDirection(container2, button3, direction);
        assertThat(target).isNull();

        Utils.recycleNode(target);

        target = mNavigator.findFocusableDescendantInDirection(container1, button2, direction);
        assertThat(target).isEqualTo(button1);

        Utils.recycleNode(target);

        target = mNavigator.findFocusableDescendantInDirection(container1, button1, direction);
        assertThat(target).isNull();

        Utils.recycleNodes(container1, button1, button2, container2, button3, button4, target);
    }

    /**
     * Tests {@link Navigator#findFocusableDescendantInDirection} going
     * {@link View#FOCUS_FORWARD} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     */
    @Test
    public void testFindFocusableVisibleDescendantInDirectionForward() {
        initActivity(R.layout.navigator_find_focusable_descendant_test_activity);

        AccessibilityNodeInfo container1 = createNode("container1");
        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo button2 = createNode("button2");
        AccessibilityNodeInfo container2 = createNode("container2");
        AccessibilityNodeInfo button3 = createNode("button3");
        AccessibilityNodeInfo button4 = createNode("button4");

        int direction = View.FOCUS_FORWARD;

        AccessibilityNodeInfo target = mNavigator.findFocusableDescendantInDirection(container1,
                button1, direction);
        assertThat(target).isEqualTo(button2);

        Utils.recycleNodes(target);

        target = mNavigator.findFocusableDescendantInDirection(container1, button2, direction);
        assertThat(target).isNull();

        Utils.recycleNodes(target);

        target = mNavigator.findFocusableDescendantInDirection(container2, button3, direction);
        assertThat(target).isEqualTo(button4);

        Utils.recycleNodes(target);

        target = mNavigator.findFocusableDescendantInDirection(container2, button4, direction);
        assertThat(target).isNull();

        Utils.recycleNodes(container1, button1, button2, container2, button3, button4, target);
    }

    /**
     * Tests {@link Navigator#findNextFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                      |
     *                      |
     *                  container
     *               /    /   \   \
     *            /      /     \      \
     *     button1  button2  button3  button4
     * </pre>
     * where {@code button3} and {@code button4} have empty bounds.
     */
    @Test
    public void testFindNextFocusableDescendantWithEmptyBounds() {
        initActivity(R.layout.navigator_find_focusable_descendant_empty_bounds_test_activity);

        AccessibilityNodeInfo container = createNode("container");
        AccessibilityNodeInfo button1 = createNode("button1");
        AccessibilityNodeInfo button2 = createNode("button2");
        AccessibilityNodeInfo button3 = createNode("button3");
        AccessibilityNodeInfo button4 = createNode("button4");

        int direction = View.FOCUS_FORWARD;

        AccessibilityNodeInfo target =
                mNavigator.findFocusableDescendantInDirection(container, button1, direction);
        assertThat(target).isEqualTo(button2);

        Utils.recycleNodes(target);

        target = mNavigator.findFocusableDescendantInDirection(container, button2, direction);
        assertThat(target).isEqualTo(button1);

        Utils.recycleNodes(target);

        target = mNavigator.findFocusableDescendantInDirection(container, button3, direction);
        assertThat(target).isEqualTo(button1);

        Utils.recycleNodes(target);

        // Wrap around since there is no Focus Parking View present.
        target = mNavigator.findFocusableDescendantInDirection(container, button4, direction);
        assertThat(target).isEqualTo(button1);

        Utils.recycleNodes(container, button1, button2, button3, button4, target);
    }

    /**
     * Tests {@link Navigator#findFirstFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     * where {@code button1} and {@code button2} are disabled.
     */
    @Test
    public void testFindFirstFocusableDescendant() {
        initActivity(R.layout.navigator_find_focusable_descendant_test_activity);

        Activity activity = mActivityRule.getActivity();
        View rootView = activity.findViewById(R.id.root);
        rootView.post(() -> {
            Button button1View = activity.findViewById(R.id.button1);
            button1View.setEnabled(false);
            Button button2View = activity.findViewById(R.id.button2);
            button2View.setEnabled(false);
        });

        AccessibilityNodeInfo root = createNode("root");
        AccessibilityNodeInfo button3 = createNode("button3");

        AccessibilityNodeInfo target = mNavigator.findFirstFocusableDescendant(root);
        assertThat(target).isEqualTo(button3);
        Utils.recycleNode(target);

        Utils.recycleNodes(root, button3);
    }

    /**
     * Tests {@link Navigator#findLastFocusableDescendant} in the following node tree:
     * <pre>
     *                     root
     *                   /      \
     *                 /          \
     *         container1        container2
     *           /   \             /   \
     *         /       \         /       \
     *     button1   button2  button3  button4
     * </pre>
     * where {@code button3} and {@code button4} are disabled.
     */
    @Test
    public void testFindLastFocusableDescendant() throws Exception {
        initActivity(R.layout.navigator_find_focusable_descendant_test_activity);

        Activity activity = mActivityRule.getActivity();
        View rootView = activity.findViewById(R.id.root);
        rootView.post(() -> {
            Button button3View = activity.findViewById(R.id.button3);
            button3View.setEnabled(false);
            Button button4View = activity.findViewById(R.id.button4);
            button4View.setEnabled(false);
        });

        // TODO: Remove this. Due to synchronization issues, this is required for button4 to be
        //  disabled.
        Thread.sleep(1000);

        AccessibilityNodeInfo root = createNode("root");
        AccessibilityNodeInfo button2 = createNode("button2");

        AccessibilityNodeInfo target = mNavigator.findLastFocusableDescendant(root);
        assertThat(target).isEqualTo(button2);
        Utils.recycleNode(target);

        Utils.recycleNodes(root, button2);
    }

    /**
     * Starts the test activity with the given layout and initializes the root
     * {@link AccessibilityNodeInfo}.
     */
    private void initActivity(@LayoutRes int layoutResId) {
        mIntent.putExtra(NavigatorTestActivity.KEY_LAYOUT_ID, layoutResId);
        mActivityRule.launchActivity(mIntent);

        mWindowRoot = sUiAutomoation.getRootInActiveWindow();
    }

    /**
     * Returns the {@link AccessibilityNodeInfo} related to the provided viewId. Returns null if no
     * such node exists. Callers should ensure {@link #initActivity} has already been called
     * and also recycle the result.
     */
    private AccessibilityNodeInfo createNode(String viewId) {
        String fullViewId = "com.android.car.rotary.tests.unit:id/" + viewId;
        List<AccessibilityNodeInfo> nodes =
                mWindowRoot.findAccessibilityNodeInfosByViewId(fullViewId);
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.get(0);
    }

    /**
     * Returns the {@link AccessibilityNodeInfo} of the view containing the provided text. Returns
     * null if no such node exists. Callers should ensure {@link #initActivity} has already
     * been called and also recycle the result.
     */
    private AccessibilityNodeInfo createNodeByText(String text) {
        List<AccessibilityNodeInfo> nodes = mWindowRoot.findAccessibilityNodeInfosByText(text);
        if (nodes.isEmpty()) {
            return null;
        }
        return nodes.get(0);
    }
}
