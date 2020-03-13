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

import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

    /**
     * The repeat count of {@link KeyEvent#KEYCODE_NAVIGATE_IN}. Use to prevent processing a click
     * when the center button is released after a long press.
     */
    private int mClickRepeatCount;

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
                        setFocusedNode(null);
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
        int keyCode = event.getKeyCode();
        if (action == KeyEvent.ACTION_DOWN) {

            // TODO: remove this once Keun-young's binder interface is ready.
            int repeatCount = event.getRepeatCount();
            if (handleKeyDownEvent(keyCode, repeatCount)) {
                return true;
            }

            if (handleDebugKeyDownEvent(keyCode)) {
                return true;
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_NAVIGATE_IN) {
                if (mClickRepeatCount == 0) {
                    // TODO: handleClickEvent();
                }
            }
        }
        return super.onKeyEvent(event);
    }

    /** Handles key down events. Returns whether the key event was handled. */
    private boolean handleKeyDownEvent(int keyCode, int repeatCount) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                // TODO: handleRotateEvent
                return true;
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                // TODO: handleRotateEvent
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
                mClickRepeatCount = repeatCount;
                if (mClickRepeatCount == 1) {
                    // TODO: handleLongClickEvent();
                }
                return true;
            default:
                // Do nothing
                return false;
        }
    }

    /**
     * Handles debug key down events if in debug mode. Returns whether the key event was handled.
     */
    private boolean handleDebugKeyDownEvent(int keyCode) {
        if (!DEBUG) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_C:
                // TODO: handleRotateEvent
                return true;
            case KeyEvent.KEYCODE_V:
                // TODO: handleRotateEvent
                return true;
            case KeyEvent.KEYCODE_J:
                // TODO: handleNudgeEvent(View.FOCUS_LEFT);
                return true;
            case KeyEvent.KEYCODE_L:
                // TODO: handleNudgeEvent(View.FOCUS_RIGHT);
                return true;
            case KeyEvent.KEYCODE_I:
                // TODO: handleNudgeEvent(View.FOCUS_UP);
                return true;
            case KeyEvent.KEYCODE_K:
                // TODO: handleNudgeEvent(View.FOCUS_DOWN);
                return true;
            case KeyEvent.KEYCODE_COMMA:
                // TODO: handleClickEvent();
                return true;
            default:
                return false;
        }
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

    /** Sets {@link #mFocusedNode} to a copy of the given node. */
    private void setFocusedNode(@Nullable AccessibilityNodeInfo focusedNode) {
        Utils.recycleNode(mFocusedNode);
        mFocusedNode = Utils.copyNode(focusedNode);
    }

    /** Sets {@link #mLastTouchedNode} to a copy of the given node. */
    private void setLastTouchedNode(@Nullable AccessibilityNodeInfo lastTouchedNode) {
        Utils.recycleNode(mLastTouchedNode);
        mLastTouchedNode = Utils.copyNode(lastTouchedNode);
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
