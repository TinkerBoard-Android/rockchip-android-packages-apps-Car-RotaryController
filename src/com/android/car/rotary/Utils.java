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

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Utility methods for {@link AccessibilityNodeInfo}.
 *
 * Because {@link AccessibilityNodeInfo}s must be recycled, it's important to be consistent about
 * who is responsible for recycling them. For simplicity, it's best to avoid having multiple objects
 * refer to the same instance of {@link AccessibilityNodeInfo}. Instead, each object should keep its
 * own copy which it's responsible for. Methods that return an {@link AccessibilityNodeInfo}
 * generally pass ownership to the caller. Such methods should never return a reference to one of
 * their parameters or the caller will recycle it twice.
 */
class Utils {

    /** Copies a node. The caller is responsible for recycling result. */
    static AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return node == null ? null : AccessibilityNodeInfo.obtain(node);
    }

    /** Recycles a node. */
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
}
