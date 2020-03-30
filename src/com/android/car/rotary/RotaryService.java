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

import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * An accessibility service that can change focus based on rotary controller rotation and nudges.
 *
 * This service responds to {@link KeyEvent}s and {@link AccessibilityEvent}s. {@link KeyEvent}s
 * coming from the rotary controller are handled by moving the focus, sometimes within a window and
 * sometimes between windows. This service listens to two types of {@link AccessibilityEvent}s:
 * {@link AccessibilityEvent#TYPE_VIEW_FOCUSED} and {@link AccessibilityEvent#TYPE_VIEW_CLICKED}.
 * The former is used to keep {@link #mFocusedNode} up to date as the focus changes. The latter is
 * used to detect when the user switches from rotary mode to touch mode and to keep {@link
 * #mLastTouchedNode} up to date.
 *
 * Note: onFoo methods are all called on the main thread so no locks are needed.
 */
public class RotaryService extends RotaryServiceBase {
    private static final String TAG = "RotaryService";
    private static final boolean DEBUG = false;

    @NonNull
    private static Utils sUtils = Utils.getInstance();

    private final NavigationHelper mNavigationHelper = new NavigationHelper();

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
        map.put(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS);
        map.put(KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_NAVIGATE_NEXT);
        map.put(KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT);
        map.put(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT);
        map.put(KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP);
        map.put(KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN);
        map.put(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_NAVIGATE_IN);

        TEST_TO_REAL_KEYCODE_MAP = Collections.unmodifiableMap(map);
    }

    @Override
    public void onCreate() {
        mRotationAcceleration3xMs =
                getResources().getInteger(R.integer.rotation_acceleration_3x_ms);
        mRotationAcceleration2xMs =
                getResources().getInteger(R.integer.rotation_acceleration_2x_ms);
    }

    @Override
    public void onInterrupt() {
        logv("onInterrupt()");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        super.onAccessibilityEvent(event);

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
                        // TODO: remove this workaround once it's fixed.
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

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int action = event.getAction();
        switch (action) {
            case KeyEvent.ACTION_DOWN: {
                if (handleKeyDownEvent(event)) {
                    return true;
                }
                break;
            }
            case KeyEvent.ACTION_UP: {
                int keyCode = event.getKeyCode();
                if (keyCode == KeyEvent.KEYCODE_NAVIGATE_IN) {
                    if (mClickRepeatCount == 0) {
                        // TODO: handleClickEvent();
                    }
                }
                break;
            }
            default:
                // Do nothing.
        }
        return super.onKeyEvent(event);
    }

    /** Handles key down events. Returns whether the key event was handled. */
    private boolean handleKeyDownEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        // TODO(b/152630987): enable this on userdebug builds.
        if (DEBUG) {
            Integer mappingKeyCode = TEST_TO_REAL_KEYCODE_MAP.get(keyCode);
            if (mappingKeyCode != null) {
                keyCode = mappingKeyCode;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                handleRotateEvent(View.FOCUS_BACKWARD, event.getRepeatCount(),
                        event.getEventTime());
                return true;
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                handleRotateEvent(View.FOCUS_FORWARD, event.getRepeatCount(), event.getEventTime());
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
                // TODO: handleNudgeEvent(View.FOCUS_LEFT);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
                // TODO: handleNudgeEvent(View.FOCUS_RIGHT);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
                // TODO: handleNudgeEvent(View.FOCUS_UP);
                return true;
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
                // TODO: handleNudgeEvent(View.FOCUS_DOWN);
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

    private void handleRotateEvent(int direction, int count, long eventTime) {
        // TODO(b/151349253): Clear the focus area nudge history when the user rotates.
        // mNavigationHelper.clearFocusAreaHistory();

        if (initFocus()) {
            return;
        }
        int rotationCount = getRotateAcceleration(count, eventTime);
        AccessibilityNodeInfo targetNode =
                mNavigationHelper.findRotateTarget(mFocusedNode, direction, rotationCount);
        if (targetNode == null) {
            logw("Failed to find rotate target");
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
     * focus is not null, if will do nothing. Otherwise, it'll consume the event. Firstly, it tries
     * to focus the view last touched by the user. If that view doesn't exist, or the focus action
     * failed, it will try to focus the first focusable view in the currently active window.
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
        focusFirstFocusable();
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
            logw("Failed to perform ACTION_CLEAR_FOCUS");
        }
    }

    /**
     * Focuses the last touched node, if any.
     *
     * @return {@code true} if {@code mLastTouchedNode} isn't {@code null} and it was
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

    /** Focuses the first focusable node in the current window, if any. */
    private void focusFirstFocusable() {
        // TODO: implement NavigationHelper.findFirstFocusable().
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
            logw("targetNode is already focused.");
            setFocusedNode(targetNode);
            return true;
        }
        boolean result = targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (result) {
            setFocusedNode(targetNode);
        } else {
            logw("Failed to perform ACTION_FOCUS on node " + targetNode);
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
        // TODO(depstein@): This method can be improved once we've plumbed through the VHAL
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

    private static void logw(String str) {
        if (DEBUG) {
            Log.w(TAG, str);
        }
    }

    private static void logv(String str) {
        if (DEBUG) {
            Log.d(TAG, str);
        }
    }
}
