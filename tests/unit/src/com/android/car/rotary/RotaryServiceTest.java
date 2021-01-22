/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.testng.AssertJUnit.assertNull;

import android.app.Activity;
import android.app.UiAutomation;
import android.car.input.CarInputManager;
import android.car.input.RotaryEvent;
import android.content.Intent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;

import androidx.annotation.LayoutRes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.car.ui.FocusParkingView;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class RotaryServiceTest {

    private static UiAutomation sUiAutomoation;

    private final List<AccessibilityNodeInfo> mNodes = new ArrayList<>();

    private AccessibilityNodeInfo mWindowRoot;
    private ActivityTestRule<NavigatorTestActivity> mActivityRule;
    private Intent mIntent;

    private @Spy
    RotaryService mRotaryService;

    @BeforeClass
    public static void setUpClass() {
        sUiAutomoation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @Before
    public void setUp() {
        mActivityRule = new ActivityTestRule<>(NavigatorTestActivity.class);
        mIntent = new Intent();
        mIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);

        MockitoAnnotations.initMocks(this);
        Navigator navigator = new Navigator(/* displayWidth= */ 0, /* displayHeight= */ 0,
                /* hunLeft= */ 0, /* hunRight= */ 0, /* showHunOnBottom= */ false);
        mRotaryService.setNavigator(navigator);
    }

    @After
    public void tearDown() {
        mActivityRule.finishActivity();
        Utils.recycleNode(mWindowRoot);
        Utils.recycleNodes(mNodes);
    }

    /**
     * Tests {@link RotaryService#initFocus()} in the following view tree:
     * <pre>
     *                      root
     *                     /    \
     *                    /      \
     *       focusParkingView   focusArea
     *                        /     |     \
     *                       /      |       \
     *               button1  defaultFocus  button3
     *                         (focused)
     * </pre>
     * and {@link RotaryService#mFocusedNode} is already set to defaultFocus.
     */
    @Test
    public void testInitFocus_alreadyInitialized() {
        initActivity(R.layout.rotary_service_test_1_activity);

        AccessibilityWindowInfo window = new WindowBuilder()
                .setRoot(mWindowRoot)
                .setBoundsInScreen(mWindowRoot.getBoundsInScreen())
                .build();
        List<AccessibilityWindowInfo> windows = Collections.singletonList(window);
        when(mRotaryService.getWindows()).thenReturn(windows);

        AccessibilityNodeInfo defaultFocusNode = createNode("defaultFocus");
        assertThat(defaultFocusNode.isFocused()).isTrue();
        mRotaryService.setFocusedNode(defaultFocusNode);
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(defaultFocusNode);

        boolean consumed = mRotaryService.initFocus();
        assertThat(consumed).isFalse();
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(defaultFocusNode);
    }

    /**
     * Tests {@link RotaryService#initFocus()} in the following view tree:
     * <pre>
     *                      root
     *                     /    \
     *                    /      \
     *       focusParkingView   focusArea
     *                        /     |     \
     *                       /      |       \
     *               button1  defaultFocus  button3
     *                                      (focused)
     * </pre>
     * and {@link RotaryService#mFocusedNode} is not initialized.
     */
    @Test
    public void testInitFocus_focusOnAlreadyFocusedView() {
        initActivity(R.layout.rotary_service_test_1_activity);

        AccessibilityWindowInfo window = new WindowBuilder()
                .setRoot(mWindowRoot)
                .setBoundsInScreen(mWindowRoot.getBoundsInScreen())
                .build();
        List<AccessibilityWindowInfo> windows = Collections.singletonList(window);
        when(mRotaryService.getWindows()).thenReturn(windows);

        Activity activity = mActivityRule.getActivity();
        Button button3 = activity.findViewById(R.id.button3);
        button3.post(() -> button3.requestFocus());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertThat(button3.isFocused()).isTrue();
        assertNull(mRotaryService.getFocusedNode());

        boolean consumed = mRotaryService.initFocus();
        AccessibilityNodeInfo button3Node = createNode("button3");
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(button3Node);
        assertThat(consumed).isFalse();
    }

    /**
     * Tests {@link RotaryService#onRotaryEvents} in the following view tree:
     * <pre>
     *                      root
     *                     /    \
     *                    /      \
     *       focusParkingView   focusArea
     *          (focused)      /     |     \
     *                       /      |       \
     *               button1  defaultFocus  button3
     * </pre>
     * and {@link RotaryService#mFocusedNode} is null.
     */
    @Test
    public void testInitFocus_focusOnDefaultFocusView() {
        initActivity(R.layout.rotary_service_test_1_activity);

        AccessibilityWindowInfo window = new WindowBuilder()
                .setRoot(mWindowRoot)
                .setBoundsInScreen(mWindowRoot.getBoundsInScreen())
                .build();
        List<AccessibilityWindowInfo> windows = Collections.singletonList(window);
        when(mRotaryService.getWindows()).thenReturn(windows);
        when(mRotaryService.getRootInActiveWindow())
                .thenReturn(MockNodeCopierProvider.get().copy(mWindowRoot));

        // Move focus to the FocusParkingView.
        Activity activity = mActivityRule.getActivity();
        FocusParkingView fpv = activity.findViewById(R.id.focusParkingView);
        fpv.setShouldRestoreFocus(false);
        fpv.post(() -> fpv.requestFocus());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertThat(fpv.isFocused()).isTrue();
        assertNull(mRotaryService.getFocusedNode());

        boolean consumed = mRotaryService.initFocus();
        AccessibilityNodeInfo defaultFocusNode = createNode("defaultFocus");
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(defaultFocusNode);
        assertThat(consumed).isTrue();
    }

    /**
     * Tests {@link RotaryService#onRotaryEvents} in the following view tree:
     * <pre>
     *                      root
     *                     /    \
     *                    /      \
     *       focusParkingView   focusArea
     *          (focused)      /     |     \
     *                       /      |       \
     *               button1  defaultFocus  button3
     *                         (disabled)  (last touched)
     * </pre>
     * and {@link RotaryService#mFocusedNode} is null.
     */
    @Test
    public void testInitFocus_focusOnLastTouchedView() {
        initActivity(R.layout.rotary_service_test_1_activity);

        AccessibilityWindowInfo window = new WindowBuilder()
                .setRoot(mWindowRoot)
                .setBoundsInScreen(mWindowRoot.getBoundsInScreen())
                .build();
        List<AccessibilityWindowInfo> windows = Collections.singletonList(window);
        when(mRotaryService.getWindows()).thenReturn(windows);
        when(mRotaryService.getRootInActiveWindow())
                .thenReturn(MockNodeCopierProvider.get().copy(mWindowRoot));

        // The user touches button3. In reality it should enter touch mode therefore no view will
        // be focused. To emulate this case, this test just moves focus to the FocusParkingView
        // and sets last touched node to button3.
        Activity activity = mActivityRule.getActivity();
        FocusParkingView fpv = activity.findViewById(R.id.focusParkingView);
        fpv.setShouldRestoreFocus(false);
        fpv.post(fpv::requestFocus);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertThat(fpv.isFocused()).isTrue();
        AccessibilityNodeInfo button3Node = createNode("button3");
        mRotaryService.setLastTouchedNode(button3Node);
        assertNull(mRotaryService.getFocusedNode());

        boolean consumed = mRotaryService.initFocus();
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(button3Node);
        assertThat(consumed).isTrue();
    }

    /**
     * Tests {@link RotaryService#onRotaryEvents} in the following view tree:
     * <pre>
     *                      root
     *                     /    \
     *                    /      \
     *       focusParkingView   focusArea
     *          (focused)      /     |     \
     *                       /      |       \
     *               button1  defaultFocus  button3
     *                         (disabled)
     * </pre>
     * and {@link RotaryService#mFocusedNode} is null.
     */
    @Test
    public void testInitFocus_focusOnFirstFocusableView() {
        initActivity(R.layout.rotary_service_test_1_activity);

        AccessibilityWindowInfo window = new WindowBuilder()
                .setRoot(mWindowRoot)
                .setBoundsInScreen(mWindowRoot.getBoundsInScreen())
                .build();
        List<AccessibilityWindowInfo> windows = Collections.singletonList(window);
        when(mRotaryService.getWindows()).thenReturn(windows);
        when(mRotaryService.getRootInActiveWindow())
                .thenReturn(MockNodeCopierProvider.get().copy(mWindowRoot));

        // Move focus to the FocusParkingView and disable the default focus view.
        Activity activity = mActivityRule.getActivity();
        FocusParkingView fpv = activity.findViewById(R.id.focusParkingView);
        Button defaultFocus = activity.findViewById(R.id.defaultFocus);
        fpv.setShouldRestoreFocus(false);
        fpv.post(() -> {
            fpv.requestFocus();
            defaultFocus.setEnabled(false);

        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertThat(fpv.isFocused()).isTrue();
        assertThat(defaultFocus.isEnabled()).isFalse();
        assertNull(mRotaryService.getFocusedNode());

        boolean consumed = mRotaryService.initFocus();
        AccessibilityNodeInfo button1Node = createNode("button1");
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(button1Node);
        assertThat(consumed).isTrue();
    }

    /**
     * Tests {@link RotaryService#onRotaryEvents} in the following view tree:
     * <pre>
     *                      root
     *                     /    \
     *                    /      \
     *       focusParkingView   focusArea
     *          (focused)      /     |     \
     *                       /      |       \
     *               button1  defaultFocus  button3
     * </pre>
     */
    @Test
    public void testOnRotaryEvents_withoutFocusedView() {
        initActivity(R.layout.rotary_service_test_1_activity);

        AccessibilityWindowInfo window = new WindowBuilder()
                .setRoot(mWindowRoot)
                .setBoundsInScreen(mWindowRoot.getBoundsInScreen())
                .build();
        List<AccessibilityWindowInfo> windows = Collections.singletonList(window);
        when(mRotaryService.getWindows()).thenReturn(windows);
        when(mRotaryService.getRootInActiveWindow())
                .thenReturn(MockNodeCopierProvider.get().copy(mWindowRoot));

        // Move focus to the FocusParkingView.
        Activity activity = mActivityRule.getActivity();
        FocusParkingView fpv = activity.findViewById(R.id.focusParkingView);
        fpv.setShouldRestoreFocus(false);
        fpv.post(() -> fpv.requestFocus());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertThat(fpv.isFocused()).isTrue();
        assertNull(mRotaryService.getFocusedNode());

        // Since there is no non-FocusParkingView focused, rotating the controller should
        // initialize the focus.

        int inputType = CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION;
        boolean clockwise = true;
        long[] timestamps = new long[]{0};
        RotaryEvent rotaryEvent = new RotaryEvent(inputType, clockwise, timestamps);
        List<RotaryEvent> events = Collections.singletonList(rotaryEvent);

        int validDisplayId = CarInputManager.TARGET_DISPLAY_TYPE_MAIN;
        mRotaryService.onRotaryEvents(validDisplayId, events);

        AccessibilityNodeInfo defaultFocusNode = createNode("defaultFocus");
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(defaultFocusNode);
    }

    /**
     * Tests {@link RotaryService#onRotaryEvents} in the following view tree:
     * <pre>
     *                      root
     *                     /    \
     *                    /      \
     *       focusParkingView   focusArea
     *                        /     |     \
     *                       /      |       \
     *               button1  defaultFocus  button3
     *                          (focused)
     * </pre>
     */
    @Test
    public void testOnRotaryEvents_withFocusedView() {
        initActivity(R.layout.rotary_service_test_1_activity);

        AccessibilityWindowInfo window = new WindowBuilder()
                .setRoot(mWindowRoot)
                .setBoundsInScreen(mWindowRoot.getBoundsInScreen())
                .build();
        List<AccessibilityWindowInfo> windows = Collections.singletonList(window);
        when(mRotaryService.getWindows()).thenReturn(windows);
        doAnswer(invocation -> 1)
                .when(mRotaryService).getRotateAcceleration(any(Integer.class), any(Long.class));

        AccessibilityNodeInfo defaultFocusNode = createNode("defaultFocus");
        assertThat(defaultFocusNode.isFocused()).isTrue();
        mRotaryService.setFocusedNode(defaultFocusNode);
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(defaultFocusNode);

        // Since RotaryService#mFocusedNode is already initialized, rotating the controller
        // clockwise should move the focus from defaultFocus to button3.

        int inputType = CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION;
        boolean clockwise = true;
        long[] timestamps = new long[]{0};
        RotaryEvent rotaryEvent = new RotaryEvent(inputType, clockwise, timestamps);
        List<RotaryEvent> events = Collections.singletonList(rotaryEvent);

        int validDisplayId = CarInputManager.TARGET_DISPLAY_TYPE_MAIN;
        mRotaryService.onRotaryEvents(validDisplayId, events);

        AccessibilityNodeInfo button3Node = createNode("button3");
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(button3Node);

        // Rotating the controller clockwise again should do nothing because button3 is the last
        // child of its ancestor FocusArea and the ancestor FocusArea doesn't support wrap-around.
        mRotaryService.onRotaryEvents(validDisplayId, events);
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(button3Node);

        // Rotating the controller counterclockwise should move focus to defaultFocus.
        clockwise = false;
        rotaryEvent = new RotaryEvent(inputType, clockwise, timestamps);
        events = Collections.singletonList(rotaryEvent);
        mRotaryService.onRotaryEvents(validDisplayId, events);
        assertThat(mRotaryService.getFocusedNode()).isEqualTo(defaultFocusNode);
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
     * Returns the {@link AccessibilityNodeInfo} related to the provided {@code viewId}. Returns
     * null if no such node exists. Callers should ensure {@link #initActivity} has already been
     * called. Caller shouldn't recycle the result because it will be recycled in {@link #tearDown}.
     */
    private AccessibilityNodeInfo createNode(String viewId) {
        String fullViewId = "com.android.car.rotary.tests.unit:id/" + viewId;
        List<AccessibilityNodeInfo> nodes =
                mWindowRoot.findAccessibilityNodeInfosByViewId(fullViewId);
        if (nodes.isEmpty()) {
            return null;
        }
        mNodes.addAll(nodes);
        return nodes.get(0);
    }
}
