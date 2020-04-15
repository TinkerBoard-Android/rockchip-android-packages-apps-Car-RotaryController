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

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Utility methods for {@link AccessibilityNodeInfo} and {@link AccessibilityWindowInfo}.
 *
 * Because {@link AccessibilityNodeInfo}s must be recycled, it's important to be consistent about
 * who is responsible for recycling them. For simplicity, it's best to avoid having multiple objects
 * refer to the same instance of {@link AccessibilityNodeInfo}. Instead, each object should keep its
 * own copy which it's responsible for. Methods that return an {@link AccessibilityNodeInfo}
 * generally pass ownership to the caller. Such methods should never return a reference to one of
 * their parameters or the caller will recycle it twice.
 */
class Utils {

    private static final Utils sInstance = new Utils();

    private Utils() {
    }

    static Utils getInstance() {
        return sInstance;
    }

    /**
     * Copies a node. The caller is responsible for recycling result.
     *
     * Note: {@link AccessibilityNodeInfo#obtain(AccessibilityNodeInfo)} doesn't work when passed a
     * mock {@link AccessibilityNodeInfo}, so we make this functions non-static and mock it in
     * tests.
     */
    AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return node == null ? null : AccessibilityNodeInfo.obtain(node);
    }

    /**
     * Recycles a node.
     *
     * Unlike {@link #copyNode(AccessibilityNodeInfo)}, this method is static because we don't need
     * to mock it in tests.
     */
    static void recycleNode(@Nullable AccessibilityNodeInfo node) {
        if (node != null) {
            node.recycle();
        }
    }

    /** Recycles a list of nodes. */
    static void recycleNodes(@Nullable List<AccessibilityNodeInfo> nodes) {
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                recycleNode(node);
            }
        }
    }

    /**
     * Updates the given {@code node} in case the view represented by it is no longer in the view
     * tree. If it's still in the view tree, returns the {@code node}. Otherwise recycles the
     * {@code node} and returns null.
     */
    static AccessibilityNodeInfo refreshNode(@Nullable AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        boolean succeeded = node.refresh();
        if (succeeded) {
            return node;
        }
        L.w("This node is no longer in the view tree: " + node);
        node.recycle();
        return null;
    }

    /** Recycles a window. */
    static void recycleWindow(@Nullable AccessibilityWindowInfo window) {
        if (window != null) {
            window.recycle();
        }
    }

    /** Recycles a list of windows. */
    static void recycleWindows(@Nullable List<AccessibilityWindowInfo> windows) {
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                recycleWindow(window);
            }
        }
    }
}
