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

import static android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Base class for accessibility services that provide rotary navigation. Subclasses should override
 * {@link #onKeyEvent} to handle rotary events.
 * TODO: remove this class once we no longer need to convert KeyEvent into AccessibilityEvent in
 *  CarInputService.
 */
public abstract class RotaryServiceBase extends AccessibilityService {

    private static final String CAR_INPUT_SERVICE_CLASS_NAME = "com.android.car.CarInputService";

    /**
     * Converts {@link AccessibilityEvent}s back to {@link KeyEvent}s. Subclasses should call the
     * super method when overriding this method.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        if (accessibilityEvent.getEventType() != TYPE_WINDOW_CONTENT_CHANGED
                || !isRotaryEvent(accessibilityEvent)) {
            return;
        }

        int accessibilityEventAction = accessibilityEvent.getAction();
        int keyCode;
        if (accessibilityEventAction == ACTION_PREVIOUS_HTML_ELEMENT) {
            keyCode = KeyEvent.KEYCODE_NAVIGATE_PREVIOUS;
        } else if (accessibilityEventAction == ACTION_NEXT_HTML_ELEMENT) {
            keyCode = KeyEvent.KEYCODE_NAVIGATE_NEXT;
        } else if (accessibilityEventAction == ACTION_CLICK) {
            keyCode = KeyEvent.KEYCODE_NAVIGATE_IN;
        } else if (accessibilityEventAction == ACTION_SCROLL_UP.getId()) {
            keyCode = KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP;
        } else if (accessibilityEventAction == ACTION_SCROLL_DOWN.getId()) {
            keyCode = KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN;
        } else if (accessibilityEventAction == ACTION_SCROLL_LEFT.getId()) {
            keyCode = KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT;
        } else if (accessibilityEventAction == ACTION_SCROLL_RIGHT.getId()) {
            keyCode = KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT;
        } else {
            return;
        }

        long eventTime = accessibilityEvent.getEventTime();
        int keyAction = accessibilityEvent.getWindowChanges();
        int count = accessibilityEvent.getMovementGranularity();
        KeyEvent keyEvent = new KeyEvent(0, eventTime, keyAction, keyCode, count);
        onKeyEvent(keyEvent);
    }

    /**
     * Subclasses should override this method to handle the following events:<ul>
     * <li>{@code KEYCODE_NAVIGATE_PREVIOUS} and {@code KEYCODE_NAVIGATE_NEXT}: The rotary
     * controller rotated counterclockwise or clockwise. The event's repeat count indicates how many
     * detents.
     * <li>{@code KECODE_NAVIGATE_IN}: The button was pressed ({@code ACTION_DOWN}), released
     * ({@code ACTION_UP}), or is being held down ({@code ACTION_DOWN} with repeat count > 0).
     * <li>{@code KEYCODE_SYSTEM_NAVIGATION_UP}, {@code KEYCODE_SYSTEM_NAVIGATION_DOWN},
     * {@code KEYCODE_SYSTEM_NAVIGATION_LEFT}, and {@code KEYCODE_SYSTEM_NAVIGATION_RIGHT}: The
     * rotary control was tilted, nudging in the given direction. The action and repeat count are
     * used as above.
     * </ul>
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        return super.onKeyEvent(event);
    }

    private static boolean isRotaryEvent(AccessibilityEvent event) {
        for (int i = 0; i < event.getRecordCount(); i++) {
            if (CAR_INPUT_SERVICE_CLASS_NAME.contentEquals(event.getRecord(i).getClassName())) {
                return true;
            }
        }
        return false;
    }
}
