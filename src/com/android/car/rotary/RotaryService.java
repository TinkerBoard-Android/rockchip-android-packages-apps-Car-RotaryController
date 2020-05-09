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
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.ui.utils.DirectManipulationHelper;

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
 * {@link RotaryEvent}s are handled by moving the focus within the same {@link
 * com.android.car.ui.FocusArea}.
 * <p>
 * Note: onFoo methods are all called on the main thread so no locks are needed.
 */
public class RotaryService extends AccessibilityService implements
        CarInputManager.CarInputCaptureCallback {

    /*
     * Whether to treat the application window as system window for direct manipulation mode. Set it
     * to {@code true} for testing only.
     */
    private static final boolean TREAT_APP_WINDOW_AS_SYSTEM_WINDOW = false;

    @NonNull
    private NodeCopier mNodeCopier = new NodeCopier();

    /**
     * A {@link Rect}. Though it's a member variable, it's meant to be used as a local variable to
     * reduce allocation and improve performance.
     */
    private final Rect mRect = new Rect();

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

    /** Whether we're in direct manipulation mode. */
    private boolean mInDirectManipulationMode;

    /** The {@link SystemClock#uptimeMillis} when the last rotary rotation event occurred. */
    private long mLastRotateEventTime;

    /**
     * The repeat count of {@link KeyEvent#KEYCODE_DPAD_CENTER}. Use to prevent processing a center
     * button click when the center button is released after a long press.
     */
    private int mCenterButtonRepeatCount;

    private static final Map<Integer, Integer> TEST_TO_REAL_KEYCODE_MAP;

    private static final Map<Integer, Integer> DIRECTION_TO_KEYCODE_MAP;

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT);
        map.put(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT);
        map.put(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
        map.put(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
        map.put(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_DPAD_CENTER);
        map.put(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK);

        TEST_TO_REAL_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    static {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(View.FOCUS_UP, KeyEvent.KEYCODE_DPAD_UP);
        map.put(View.FOCUS_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
        map.put(View.FOCUS_LEFT, KeyEvent.KEYCODE_DPAD_LEFT);
        map.put(View.FOCUS_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT);

        DIRECTION_TO_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    private Car mCar;
    private CarInputManager mCarInputManager;
    private InputManager mInputManager;
    private DirectManipulationHelper mDirectManipulationHelper;

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

        mDirectManipulationHelper = new DirectManipulationHelper(this);
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

        mInputManager = getSystemService(InputManager.class);
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
                    if (sourceNode != null && !sourceNode.equals(mFocusedNode)
                            && !Utils.isFocusParkingView(sourceNode)) {
                        // Android doesn't clear focus automatically when focus is set in another
                        // window.
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
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                updateDirectManipulationMode(event, true);
                break;
            }
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                updateDirectManipulationMode(event, false);
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
        if (Build.IS_DEBUGGABLE) {
            return handleKeyEvent(event);
        }
        return false;
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

    private static boolean isValidDisplayId(int displayId) {
        if (displayId == CarInputManager.TARGET_DISPLAY_TYPE_MAIN) {
            return true;
        }
        L.e("RotaryService shouldn't capture events from display ID " + displayId);
        return false;
    }

    /**
     * Handles key events. Returns whether the key event was consumed. To avoid invalid event stream
     * getting through to the application, if a key down event is consumed, the corresponding key up
     * event must be consumed too, and vice versa.
     */
    private boolean handleKeyEvent(KeyEvent event) {
        int action = event.getAction();
        boolean isActionDown = action == KeyEvent.ACTION_DOWN;
        int keyCode = getKeyCode(event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_C:
                if (isActionDown) {
                    handleRotateEvent(/* clockwise= */ false, event.getRepeatCount(),
                            event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_V:
                if (isActionDown) {
                    handleRotateEvent(/* clockwise= */ true, event.getRepeatCount(),
                            event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
                handleNudgeEvent(View.FOCUS_LEFT, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
                handleNudgeEvent(View.FOCUS_RIGHT, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
                handleNudgeEvent(View.FOCUS_UP, action);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
                handleNudgeEvent(View.FOCUS_DOWN, action);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (isActionDown) {
                    mCenterButtonRepeatCount = event.getRepeatCount();
                }
                if (mCenterButtonRepeatCount == 0) {
                    handleCenterButtonEvent(action);
                } else if (mCenterButtonRepeatCount == 1) {
                    // TODO: handleLongClickEvent(action);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (mInDirectManipulationMode) {
                    handleBackButtonEvent(action);
                    return true;
                }
                return false;
            default:
                // Do nothing
        }
        return false;
    }

    private static int getKeyCode(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (Build.IS_DEBUGGABLE) {
            Integer mappingKeyCode = TEST_TO_REAL_KEYCODE_MAP.get(keyCode);
            if (mappingKeyCode != null) {
                keyCode = mappingKeyCode;
            }
        }
        return keyCode;
    }

    /** Handles controller center button event. */
    private void handleCenterButtonEvent(int action) {
        if (!isValidAction(action)) {
            return;
        }
        if (initFocus()) {
            return;
        }
        // Case 1: the focus is in application window, inject KeyEvent.KEYCODE_DPAD_CENTER event and
        // the application will handle it.
        if (isInApplicationWindow(mFocusedNode)) {
            injectKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, action);
            return;
        }
        // We're done with ACTION_DOWN event.
        if (action == KeyEvent.ACTION_DOWN) {
            return;
        }

        // Case 2: the focus is not in application window (e.g., in system window) and the focused
        // node supports direct manipulation, enter direct manipulation mode.
        if (mDirectManipulationHelper.supportDirectManipulation(mFocusedNode)) {
            if (!mInDirectManipulationMode) {
                mInDirectManipulationMode = true;
                L.d("Enter direct manipulation mode because focused node is clicked.");
            }
            return;
        }

        // Case 3: the focus is not in application window and the focused node doesn't support
        // direct manipulation, perform click on the focused node.
        boolean result = mFocusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!result) {
            L.w("Failed to perform ACTION_CLICK on " + mFocusedNode);
        }
        mRotaryClicked = true;
    }

    private void handleNudgeEvent(int direction, int action) {
        if (!isValidAction(action)) {
            return;
        }
        if (initFocus()) {
            return;
        }

        // If the focused node is in direct manipulation mode, manipulate it directly.
        if (mInDirectManipulationMode) {
            if (isInApplicationWindow(mFocusedNode)) {
                injectKeyEventForDirection(direction, action);
            } else {
                // Ignore nudge events if the focus is not in application window.
                L.d("Focused node is in window type " + mFocusedNode.getWindow().getType());
            }
            return;
        }

        // We're done with ACTION_UP event.
        if (action == KeyEvent.ACTION_UP) {
            return;
        }

        // If the focused node is not in direct manipulation mode, move the focus.
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
        maybeClearFocusInCurrentWindow(targetNode);

        performFocusAction(targetNode);
        Utils.recycleNode(targetNode);
    }

    private void handleRotaryEvent(RotaryEvent rotaryEvent) {
        if (rotaryEvent.getInputType() != CarInputManager.INPUT_TYPE_ROTARY_NAVIGATION) {
            return;
        }
        boolean clockwise = rotaryEvent.isClockwise();
        int count = rotaryEvent.getNumberOfClicks();
        // TODO(b/153195148): Use the first eventTime for now. We'll need to improve it later.
        long eventTime = rotaryEvent.getUptimeMillisForClick(0);
        handleRotateEvent(clockwise, count, eventTime);
    }

    private void handleRotateEvent(boolean clockwise, int count, long eventTime) {
        if (mClearFocusAreaHistoryWhenRotating) {
            mNavigator.clearFocusAreaHistory();
        }
        if (initFocus()) {
            return;
        }

        int rotationCount = getRotateAcceleration(count, eventTime);

        // If the focused node is in direct manipulation mode, manipulate it directly.
        if (mInDirectManipulationMode) {
            if (isInApplicationWindow(mFocusedNode)) {
                mFocusedNode.getBoundsInScreen(mRect);
                injectMotionEvent(clockwise, rotationCount, mRect.centerX(), mRect.centerY());
            } else {
                performScrollAction(mFocusedNode, clockwise);
            }
            return;
        }

        // If the focused node is not in direct manipulation mode, move the focus.
        int direction = clockwise ? View.FOCUS_FORWARD : View.FOCUS_BACKWARD;
        AccessibilityNodeInfo targetNode =
                mNavigator.findRotateTarget(mFocusedNode, direction, rotationCount);
        if (targetNode == null) {
            L.w("Failed to find rotate target");
            return;
        }
        performFocusAction(targetNode);
        Utils.recycleNode(targetNode);
    }

    /** Handles Back button event. */
    private void handleBackButtonEvent(int action) {
        if (!isValidAction(action)) {
            return;
        }

        // If the focus is in application window, inject Back button event and the application will
        // handle it. If the focus is not in application window, exit direct manipulation mode on
        // key up.
        if (isInApplicationWindow(mFocusedNode)) {
            injectKeyEvent(KeyEvent.KEYCODE_BACK, action);
        } else if (action == KeyEvent.ACTION_UP) {
            L.d("Exit direct manipulation mode on back button event");
            mInDirectManipulationMode = false;
        }
    }

    private static boolean isValidAction(int action) {
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            L.w("Invalid action " + action);
            return false;
        }
        return true;
    }

    /** Performs scroll action on the given {@code targetNode} if it supports scroll action. */
    private static void performScrollAction(@NonNull AccessibilityNodeInfo targetNode,
            boolean clockwise) {
        // TODO(b/155823126): Add config to let OEMs determine the mapping.
        int actionToPerform = clockwise
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        int supportedActions = targetNode.getActions();
        if ((actionToPerform & supportedActions) == 0) {
            L.w("Node " + targetNode + " doesn't support action " + actionToPerform);
            return;
        }
        boolean result = targetNode.performAction(actionToPerform);
        if (!result) {
            L.w("Failed to perform action " + actionToPerform + " on " + targetNode);
        }
    }

    /** Returns whether the given {@code node} is in application window. */
    private static boolean isInApplicationWindow(@NonNull AccessibilityNodeInfo node) {
        if (TREAT_APP_WINDOW_AS_SYSTEM_WINDOW) {
            return false;
        }
        AccessibilityWindowInfo window = node.getWindow();
        boolean result = window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION;
        Utils.recycleWindow(window);
        return result;
    }

    private void updateDirectManipulationMode(AccessibilityEvent event, boolean enable) {
        if (!mInRotaryMode || !mDirectManipulationHelper.isDirectManipulation(event)) {
            return;
        }
        AccessibilityNodeInfo sourceNode = event.getSource();
        if (sourceNode != null && sourceNode.equals(mFocusedNode)) {
            if (mInDirectManipulationMode != enable) {
                // Toggle direct manipulation mode upon app's request.
                mInDirectManipulationMode = enable;
                L.d((enable ? "Enter" : "Exit") + " direct manipulation mode upon app's request");
            }
        }
        Utils.recycleNode(sourceNode);
    }

    private void injectMotionEvent(boolean clockwise, int rotationCount, float x, float y) {
        // TODO(b/155823126): Add config to let OEMs determine the mapping.
        int indents = clockwise ? rotationCount : -rotationCount;
        long upTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0; // Any integer value but -1 (INVALID_POINTER_ID) is fine.
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].setAxisValue(MotionEvent.AXIS_SCROLL, indents);
        MotionEvent motionEvent = MotionEvent.obtain(/* downTime= */ upTime,
                /* eventTime= */ upTime,
                MotionEvent.ACTION_SCROLL,
                /* pointerCount= */ 1,
                properties,
                coords,
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision= */ 1.0f,
                /* yPrecision= */ 1.0f,
                /* deviceId= */ 0,
                /* edgeFlags= */ 0,
                /* source= */ 0,
                /* flags= */ 0);

        mInputManager.injectInputEvent(motionEvent,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private boolean injectKeyEventForDirection(int direction, int action) {
        Integer keyCode = DIRECTION_TO_KEYCODE_MAP.get(direction);
        if (keyCode == null) {
            throw new IllegalArgumentException("direction must be one of "
                    + "{FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, FOCUS_RIGHT}.");
        }
        return injectKeyEvent(keyCode, action);
    }

    private boolean injectKeyEvent(int keyCode, int action) {
        long upTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(
                /* downTime= */ upTime, /* eventTime= */ upTime, action, keyCode, /* repeat= */ 0);
        return mInputManager.injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
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

    /**
     * Clears the current rotary focus if {@code targetFocus} is in a different window.
     * If we really clear focus in the current window, Android will re-focus a view in the current
     * window automatically, resulting in the current window and the target window being focused
     * simultaneously. To avoid that we don't really clear the focus. Instead, we "park" the focus
     * on a FocusParkingView in the current window. FocusParkingView is transparent no matter
     * whether it's focused or not, so it's invisible to the user.
     */
    private void maybeClearFocusInCurrentWindow(@NonNull AccessibilityNodeInfo targetFocus) {
        if (mFocusedNode == null || !mFocusedNode.isFocused()
                || mFocusedNode.getWindowId() == targetFocus.getWindowId()) {
            return;
        }

        AccessibilityWindowInfo window = mFocusedNode.getWindow();
        if (window == null) {
            L.e("Failed to get window of " + mFocusedNode);
            return;
        }
        AccessibilityNodeInfo focusParkingView = mNavigator.findFocusParkingView(window);
        window.recycle();
        if (focusParkingView == null) {
            L.e("No FocusParkingView in " + window);
            return;
        }

        boolean result = focusParkingView.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (result) {
            setFocusedNode(null);
        } else {
            L.w("Failed to perform ACTION_FOCUS on " + focusParkingView);
        }

        focusParkingView.recycle();
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
            if (mLastTouchedNode != null) {
                setLastTouchedNode(null);
            }
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
        AccessibilityNodeInfo targetNode = mNavigator.findFirstFocusDescendant(rootNode);
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
        if ((mFocusedNode == null && focusedNode == null) ||
                (mFocusedNode != null && mFocusedNode.equals(focusedNode))) {
            L.d("Don't reset mFocusedNode since it stays the same: " + mFocusedNode);
            return;
        }
        if (mInDirectManipulationMode) {
            // Toggle off direct manipulation mode since the focus has changed.
            mInDirectManipulationMode = false;
            L.d("Exit direct manipulation mode since the focus has changed");
        }

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
        if ((mLastTouchedNode == null && lastTouchedNode == null) ||
                (mLastTouchedNode != null && mLastTouchedNode.equals(lastTouchedNode))) {
            L.d("Don't reset mLastTouchedNode since it stays the same: " + mLastTouchedNode);
        }

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
            L.w("targetNode is already focused: " + targetNode);
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

    private AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return mNodeCopier.copy(node);
    }
}
