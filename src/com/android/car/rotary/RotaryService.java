/*
 * Copyright 2020 The Android Open Source Project
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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.car.Car;
import android.car.input.CarInputManager;
import android.car.input.RotaryEvent;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A service that can change focus based on rotary controller rotation and nudges, and perform
 * clicks based on rotary controller center button clicks.
 * <p>
 * As an {@link AccessibilityService}, this service responds to {@link KeyEvent}s (on debug builds
 * only) and {@link AccessibilityEvent}s.
 * <p>
 * On debug builds, {@link KeyEvent}s coming from the keyboard are handled by clicking the view, or
 * moving the focus, sometimes within a window and sometimes between windows.
 * <p>
 * This service listens to two types of {@link AccessibilityEvent}s: {@link
 * AccessibilityEvent#TYPE_VIEW_FOCUSED} and {@link AccessibilityEvent#TYPE_VIEW_CLICKED}. The
 * former is used to keep {@link #mFocusedNode} up to date as the focus changes. The latter is used
 * to detect when the user switches from rotary mode to touch mode and to keep {@link
 * #mLastTouchedNode} up to date.
 * <p>
 * As a {@link CarInputManager.CarInputCaptureCallback}, this service responds to {@link KeyEvent}s
 * and {@link RotaryEvent}s, both of which are coming from the controller.
 * <p>
 * {@link KeyEvent}s are handled by clicking the view, or moving the focus, sometimes within a
 * window and sometimes between windows.
 * <p>
 * {@link RotaryEvent}s are handled by moving the focus within the same {@link FocusArea}.
 * <p>
 * Note: onFoo methods are all called on the main thread so no locks are needed.
 */
public class RotaryService extends AccessibilityService implements
        CarInputManager.CarInputCaptureCallback {

    @NonNull
    private static Utils sUtils = Utils.getInstance();

    private Navigator mNavigator;

    /** Input types to capture. */
    private final int[] mInputTypes = new int[]{
            // Capture controller rotation.
            CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION,
            // Capture controller center button clicks.
            CarInputManager.INPUT_TYPE_DPAD_KEYS,
            // Capture controller nudges.
            CarInputManager.INPUT_TYPE_SYSTEM_NAVIGATE_KEYS};

    /**
     * Time interval in milliseconds to decide whether we should accelerate the rotation by 3 times
     * for a rotate event.
     */
    private int mRotationAcceleration3xMs;

    /**
     * Time interval in milliseconds to decide whether we should accelerate the rotation by 2 times
     * for a rotate event.
     */
    private int mRotationAcceleration2xMs;

    /** Whether to clear focus area history when the user rotates the controller. */
    private boolean mClearFocusAreaHistoryWhenRotating;

    /** The currently focused node, if any. */
    private AccessibilityNodeInfo mFocusedNode = null;

    /**
     * The last clicked node by touching the screen, if any were clicked since we last navigated.
     */
    private AccessibilityNodeInfo mLastTouchedNode = null;

    /**
     * Whether this service generated a regular or long click which it has yet to receive an
     * accessibility event for.
     */
    private boolean mRotaryClicked;

    /** Whether we're in rotary mode (vs touch mode). */
    private boolean mInRotaryMode;

    /** The {@link SystemClock#uptimeMillis} when the last rotary rotation event occurred. */
    private long mLastRotateEventTime;

    /**
     * The repeat count of {@link KeyEvent#KEYCODE_NAVIGATE_IN}. Use to prevent processing a click
     * when the center button is released after a long press.
     */
    private int mClickRepeatCount;

    private static final Map<Integer, Integer> TEST_TO_REAL_KEYCODE_MAP;

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT);
        map.put(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT);
        map.put(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
        map.put(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
        map.put(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_DPAD_CENTER);

        TEST_TO_REAL_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    private Car mCar;
    private CarInputManager mCarInputManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mRotationAcceleration3xMs =
                getResources().getInteger(R.integer.rotation_acceleration_3x_ms);
        mRotationAcceleration2xMs =
                getResources().getInteger(R.integer.rotation_acceleration_2x_ms);

        mClearFocusAreaHistoryWhenRotating =
                getResources().getBoolean(R.bool.clear_focus_area_history_when_rotating);

        @RotaryCache.CacheType int focusHistoryCacheType =
                getResources().getInteger(R.integer.focus_history_cache_type);
        int focusHistoryCacheSize =
                getResources().getInteger(R.integer.focus_history_cache_size);
        int focusHistoryExpirationTimeMs =
                getResources().getInteger(R.integer.focus_history_expiration_time_ms);

        @RotaryCache.CacheType int focusAreaHistoryCacheType =
                getResources().getInteger(R.integer.focus_area_history_cache_type);
        int focusAreaHistoryCacheSize =
                getResources().getInteger(R.integer.focus_area_history_cache_size);
        int focusAreaHistoryExpirationTimeMs =
                getResources().getInteger(R.integer.focus_area_history_expiration_time_ms);

        mNavigator = new Navigator(
                focusHistoryCacheType,
                focusHistoryCacheSize,
                focusHistoryExpirationTimeMs,
                focusAreaHistoryCacheType,
                focusAreaHistoryCacheSize,
                focusAreaHistoryExpirationTimeMs);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        mCar = Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    mCar = car;
                    if (ready) {
                        mCarInputManager =
                                (CarInputManager) mCar.getCarManager(Car.CAR_INPUT_SERVICE);
                        mCarInputManager.requestInputEventCapture(this,
                                CarInputManager.TARGET_DISPLAY_TYPE_MAIN,
                                mInputTypes,
                                CarInputManager.CAPTURE_REQ_FLAGS_ALLOW_DELAYED_GRANT);
                    }
                });

        if (Build.IS_DEBUGGABLE) {
            AccessibilityServiceInfo serviceInfo = getServiceInfo();
            // Filter testing KeyEvents from a keyboard.
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(serviceInfo);
        }
    }

    @Override
    public void onInterrupt() {
        L.v("onInterrupt()");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: {
                if (mInRotaryMode) {
                    // A view was focused. We ignore focus changes in touch mode. We don't use
                    // TYPE_VIEW_FOCUSED to keep mLastTouchedNode up to date because most views
                    // can't be focused in touch mode. In rotary mode, we use TYPE_VIEW_FOCUSED
                    // events to keep mFocusedNode up to date and to clear the focus when moving
                    // between windows.
                    AccessibilityNodeInfo sourceNode = event.getSource();
                    if (sourceNode != null && !sourceNode.equals(mFocusedNode)) {
                        // Android doesn't clear focus automatically when focus is set in another
                        // window.
                        // TODO(b/152244654): remove this workaround once it's fixed.
                        maybeClearFocusInCurrentWindow(sourceNode);
                        setFocusedNode(sourceNode);
                    }
                    Utils.recycleNode(sourceNode);
                }
                break;
            }
            case AccessibilityEvent.TYPE_VIEW_CLICKED: {
                // A view was clicked. If we triggered the click via performAction(ACTION_CLICK),
                // we ignore it. Otherwise, we assume the user touched the screen. In this case, we
                // exit rotary mode if necessary, update mLastTouchedNode, and clear the focus if
                // the user touched a view in a different window.
                if (mRotaryClicked) {
                    mRotaryClicked = false;
                } else {
                    // Enter touch mode once the user touches the screen.
                    mInRotaryMode = false;
                    AccessibilityNodeInfo sourceNode = event.getSource();
                    if (sourceNode != null) {
                        if (!sourceNode.equals(mLastTouchedNode)) {
                            setLastTouchedNode(sourceNode);
                        }
                        // Explicitly clear focus when user uses touch in another window.
                        maybeClearFocusInCurrentWindow(sourceNode);
                    }
                    Utils.recycleNode(sourceNode);
                }
                break;
            }
            default:
                // Do nothing.
        }
    }

    /**
     * Callback of {@link AccessibilityService}. It allows us to observe testing {@link KeyEvent}s
     * from keyboard, including keys "C" and "V" to emulate controller rotation, keys "J" "L" "I"
     * "K" to emulate controller nudges, and key "Comma" to emulate center button clicks.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (Build.IS_DEBUGGABLE && handleKeyEvent(event)) {
            return true;
        }
        return super.onKeyEvent(event);
    }

    /**
     * Callback of {@link CarInputManager.CarInputCaptureCallback}. It allows us to capture {@link
     * KeyEvent}s generated by a navigation controller, such as controller nudge and controller
     * click events.
     */
    @Override
    public void onKeyEvents(int targetDisplayId, List<KeyEvent> events) {
        if (!isValidDisplayId(targetDisplayId)) {
            return;
        }
        for (KeyEvent event : events) {
            handleKeyEvent(event);
        }
    }

    /**
     * Callback of {@link CarInputManager.CarInputCaptureCallback}. It allows us to capture {@link
     * RotaryEvent}s generated by a navigation controller.
     */
    @Override
    public void onRotaryEvents(int targetDisplayId, List<RotaryEvent> events) {
        if (!isValidDisplayId(targetDisplayId)) {
            return;
        }
        for (RotaryEvent rotaryEvent : events) {
            handleRotaryEvent(rotaryEvent);
        }
    }

    @Override
    public void onCaptureStateChanged(int targetDisplayId,
            @android.annotation.NonNull @CarInputManager.InputTypeEnum int[] activeInputTypes) {
        // Do nothing.
    }

    private boolean isValidDisplayId(int displayId) {
        if (displayId == CarInputManager.TARGET_DISPLAY_TYPE_MAIN) {
            return true;
        }
        L.e("RotaryService shouldn't capture events from display ID " + displayId);
        return false;
    }

    /** Handles key events. Returns whether the key event was handled. */
    private boolean handleKeyEvent(KeyEvent event) {
        int action = event.getAction();
        switch (action) {
            case KeyEvent.ACTION_DOWN: {
                return handleKeyDownEvent(event);
            }
            case KeyEvent.ACTION_UP: {
                int keyCode = event.getKeyCode();
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    if (mClickRepeatCount == 0) {
                        // TODO: handleClickEvent();
                    }
                }
                break;
            }
            default:
                // Do nothing.
        }
        return false;
    }

    /** Handles key down events. Returns whether the key event was handled. */
    private boolean handleKeyDownEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (Build.IS_DEBUGGABLE) {
            Integer mappingKeyCode = TEST_TO_REAL_KEYCODE_MAP.get(keyCode);
            if (mappingKeyCode != null) {
                keyCode = mappingKeyCode;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_C:
                handleRotateEvent(View.FOCUS_BACKWARD, event.getRepeatCount(),
                        event.getEventTime());
                return true;
            case KeyEvent.KEYCODE_V:
                handleRotateEvent(View.FOCUS_FORWARD, event.getRepeatCount(), event.getEventTime());
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
                handleNudgeEvent(View.FOCUS_LEFT);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
                handleNudgeEvent(View.FOCUS_RIGHT);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
                handleNudgeEvent(View.FOCUS_UP);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
                handleNudgeEvent(View.FOCUS_DOWN);
                return true;
            case KeyEvent.KEYCODE_NAVIGATE_IN:
                mClickRepeatCount = event.getRepeatCount();
                if (mClickRepeatCount == 1) {
                    // TODO: handleLongClickEvent();
                }
                return true;
            default:
                // Do nothing
                return false;
        }
    }

    private void handleNudgeEvent(int direction) {
        if (initFocus()) {
            return;
        }
        // TODO(b/152438801): sometimes getWindows() takes 10s after boot.
        List<AccessibilityWindowInfo> windows = getWindows();
        AccessibilityNodeInfo targetNode =
                mNavigator.findNudgeTarget(windows, mFocusedNode, direction);
        Utils.recycleWindows(windows);
        if (targetNode == null) {
            L.w("Failed to find nudge target");
            return;
        }

        // Android doesn't clear focus automatically when focus is set in another window.
        // TODO(b/152244654): remove this workaround once it's fixed.
        maybeClearFocusInCurrentWindow(targetNode);

        performFocusAction(targetNode);
        Utils.recycleNode(targetNode);
    }

    private void handleRotaryEvent(RotaryEvent rotaryEvent) {
        if (rotaryEvent.getInputType() != CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION) {
            return;
        }
        int direction = rotaryEvent.isClockwise() ? View.FOCUS_FORWARD : View.FOCUS_BACKWARD;
        int count = rotaryEvent.getNumberOfClicks();
        // TODO(b/153195148): Use the first eventTime for now. We'll need to improve it later.
        long eventTime = rotaryEvent.getUptimeMillisForClick(0);
        handleRotateEvent(direction, count, eventTime);
    }

    private void handleRotateEvent(int direction, int count, long eventTime) {
        if (mClearFocusAreaHistoryWhenRotating) {
            mNavigator.clearFocusAreaHistory();
        }

        if (initFocus()) {
            return;
        }
        int rotationCount = getRotateAcceleration(count, eventTime);
        AccessibilityNodeInfo targetNode =
                mNavigator.findRotateTarget(mFocusedNode, direction, rotationCount);
        if (targetNode == null) {
            L.w("Failed to find rotate target");
            return;
        }
        performFocusAction(targetNode);
        Utils.recycleNode(targetNode);
    }

    /**
     * Updates {@link #mFocusedNode} and {@link #mLastTouchedNode} in case the {@link View}s
     * represented by them are no longer in the view tree.
     */
    private void refreshSavedNodes() {
        mFocusedNode = Utils.refreshNode(mFocusedNode);
        mLastTouchedNode = Utils.refreshNode(mLastTouchedNode);
    }

    /**
     * Initializes the current focus if it's null.
     * This method should be called when receiving an event from a rotary controller. If the current
     * focus is not null, it will do nothing. Otherwise, it'll consume the event. Firstly, it tries
     * to focus the view last touched by the user. If that view doesn't exist, or the focus action
     * failed, it will try to focus the first focus descendant in the currently active window.
     *
     * @return whether the event was consumed by this method
     */
    private boolean initFocus() {
        refreshSavedNodes();
        mInRotaryMode = true;
        if (mFocusedNode != null) {
            return false;
        }
        if (mLastTouchedNode != null) {
            if (focusLastTouchedNode()) {
                return true;
            }
        }
        focusFirstFocusDescendant();
        return true;
    }

    /** Clears the current focus if {@code targetNode} is in a different window. */
    private void maybeClearFocusInCurrentWindow(@NonNull AccessibilityNodeInfo targetNode) {
        if (mFocusedNode == null || !mFocusedNode.isFocused()
                || mFocusedNode.getWindowId() == targetNode.getWindowId()) {
            return;
        }
        boolean result = mFocusedNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
        if (result) {
            setFocusedNode(null);
        } else {
            L.w("Failed to perform ACTION_CLEAR_FOCUS");
        }
    }

    /**
     * Focuses the last touched node, if any.
     *
     * @return {@code true} if {@link #mLastTouchedNode} isn't {@code null} and it was
     *         successfully focused
     */
    private boolean focusLastTouchedNode() {
        boolean lastTouchedNodeFocused = false;
        if (mLastTouchedNode != null) {
            lastTouchedNodeFocused = performFocusAction(mLastTouchedNode);
            setLastTouchedNode(null);
        }
        return lastTouchedNodeFocused;
    }

    /**
     * Focuses the first focus descendant (a node inside a focus area that can take focus) in the
     * currently active window, if any.
     */
    private void focusFirstFocusDescendant() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            L.e("rootNode of active window is null");
            return;
        }
        AccessibilityNodeInfo targetNode = Navigator.findFirstFocusDescendant(rootNode);
        rootNode.recycle();
        if (targetNode == null) {
            L.w("Failed to find the first focus descendant");
            return;
        }
        performFocusAction(targetNode);
        targetNode.recycle();
    }


    /**
     * Sets {@link #mFocusedNode} to a copy of the given node, and clears {@link #mLastTouchedNode}.
     */
    private void setFocusedNode(@Nullable AccessibilityNodeInfo focusedNode) {
        setFocusedNodeInternal(focusedNode);
        if (mFocusedNode != null && mLastTouchedNode != null) {
            setLastTouchedNodeInternal(null);
        }
    }

    private void setFocusedNodeInternal(@Nullable AccessibilityNodeInfo focusedNode) {
        Utils.recycleNode(mFocusedNode);
        mFocusedNode = copyNode(focusedNode);

        // Cache the focused node by focus area.
        if (mFocusedNode != null) {
            mNavigator.saveFocusedNode(mFocusedNode);
        }
    }

    /**
     * Sets {@link #mLastTouchedNode} to a copy of the given node, and clears {@link #mFocusedNode}.
     */
    private void setLastTouchedNode(@Nullable AccessibilityNodeInfo lastTouchedNode) {
        setLastTouchedNodeInternal(lastTouchedNode);
        if (mLastTouchedNode != null && mFocusedNode != null) {
            setFocusedNodeInternal(null);
        }
    }

    private void setLastTouchedNodeInternal(@Nullable AccessibilityNodeInfo lastTouchedNode) {
        Utils.recycleNode(mLastTouchedNode);
        mLastTouchedNode = copyNode(lastTouchedNode);
    }

    /**
     * Performs {@link AccessibilityNodeInfo#ACTION_FOCUS} on the given {@code targetNode}.
     *
     * @return true if {@code targetNode} was focused already or became focused after performing
     *         {@link AccessibilityNodeInfo#ACTION_FOCUS}
     */
    private boolean performFocusAction(@NonNull AccessibilityNodeInfo targetNode) {
        if (targetNode.equals(mFocusedNode)) {
            return true;
        }
        if (targetNode.isFocused()) {
            L.w("targetNode is already focused.");
            setFocusedNode(targetNode);
            return true;
        }
        boolean result = targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (result) {
            setFocusedNode(targetNode);
        } else {
            L.w("Failed to perform ACTION_FOCUS on node " + targetNode);
        }
        return result;
    }

    /**
     * Returns the number of "ticks" to rotate for a single rotate event with the given detent
     * {@code count} at the given time. Uses and updates {@link #mLastRotateEventTime}. The result
     * will be one, two, or three times the given detent {@code count} depending on the interval
     * between the current event and the previous event and the detent {@code count}.
     *
     * @param count     the number of detents the user rotated
     * @param eventTime the {@link SystemClock#uptimeMillis} when the event occurred
     * @return the number of "ticks" to rotate
     */
    private int getRotateAcceleration(int count, long eventTime) {
        // count is 0 when testing key "C" or "V" is pressed.
        if (count <= 0) {
            count = 1;
        }
        int result = count;
        // TODO(b/153195148): This method can be improved once we've plumbed through the VHAL
        //  changes. We'll get timestamps for each detent.
        long delta = (eventTime - mLastRotateEventTime) / count;  // Assume constant speed.
        if (delta <= mRotationAcceleration3xMs) {
            result = count * 3;
        } else if (delta <= mRotationAcceleration2xMs) {
            result = count * 2;
        }
        mLastRotateEventTime = eventTime;
        return result;
    }

    private static AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return sUtils.copyNode(node);
    }
}
