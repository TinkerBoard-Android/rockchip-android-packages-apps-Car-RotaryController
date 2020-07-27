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

import static android.view.accessibility.AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;

import static com.android.car.rotary.Utils.FOCUS_AREA_CLASS_NAME;
import static com.android.car.rotary.Utils.FOCUS_PARKING_VIEW_CLASS_NAME;
import static com.android.car.ui.utils.RotaryConstants.ROTARY_VERTICALLY_SCROLLABLE;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A builder which builds a mock {@link AccessibilityNodeInfo}. Unlike real nodes, mock nodes don't
 * need to be recycled.
 */
class NodeBuilder {

    /**
     * A list of mock nodes created via NodeBuilder. This list is used for searching for a
     * node's child nodes.
     */
    @NonNull
    private final List<AccessibilityNodeInfo> mNodeList;

    /** The window to which this node belongs. */
    @Nullable
    private AccessibilityWindowInfo mWindow;
    /** The window ID to which this node belongs. */
    private int mWindowId = UNDEFINED_WINDOW_ID;
    /** The parent of this node. */
    @Nullable
    private AccessibilityNodeInfo mParent;
    /** The class this node comes from. */
    @Nullable
    private String mClassName;
    /** The node bounds in screen coordinates. */
    @Nullable
    private Rect mBoundsInScreen;
    /** Whether this node is focusable. */
    private boolean mFocusable = true;
    /** Whether this node is visible to the user. */
    private boolean mVisibleToUser = true;
    /** Whether this node is enabled. */
    private boolean mEnabled = true;
    /** Whether the view represented by this node is still in the view tree. */
    private boolean mInViewTree = true;
    /** The content description for this node. */
    @Nullable
    private String mContentDescription;
    /** The action list for this node. */
    @NonNull
    private List<AccessibilityNodeInfo.AccessibilityAction> mActionList = new ArrayList<>();

    NodeBuilder(@NonNull List<AccessibilityNodeInfo> nodeList) {
        mNodeList = nodeList;
    }

    AccessibilityNodeInfo build() {
        // Make a copy of the current NodeBuilder.
        NodeBuilder builder = copy();
        // Clear the state of the current NodeBuilder so that it can be reused.
        clear();

        AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);

        when(node.getWindow()).thenReturn(builder.mWindow);
        when(node.getWindowId()).thenReturn(builder.mWindowId);
        when(node.getParent()).thenReturn(builder.mParent);

        if (builder.mParent != null) {
            // Mock AccessibilityNodeInfo#getChildCount().
            doAnswer(invocation -> {
                int childCount = 0;
                for (AccessibilityNodeInfo candidate : builder.mNodeList) {
                    if (builder.mParent.equals(candidate.getParent())) {
                        childCount++;
                    }
                }
                return childCount;
            }).when(builder.mParent).getChildCount();

            // Mock AccessibilityNodeInfo#getChild(int).
            doAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                int index = (int) args[0];
                for (AccessibilityNodeInfo candidate : builder.mNodeList) {
                    if (builder.mParent.equals(candidate.getParent())) {
                        if (index == 0) {
                            return candidate;
                        } else {
                            index--;
                        }
                    }
                }
                return null;
            }).when(builder.mParent).getChild(any(Integer.class));
        }

        when(node.getClassName()).thenReturn(builder.mClassName);

        if (builder.mBoundsInScreen != null) {
            // Mock AccessibilityNodeInfo#getBoundsInScreen(Rect).
            doAnswer(invocation -> {
                Object[] args = invocation.getArguments();
                ((Rect) args[0]).set(builder.mBoundsInScreen);
                return null;
            }).when(node).getBoundsInScreen(any(Rect.class));
        }

        when(node.isFocusable()).thenReturn(builder.mFocusable);
        when(node.isVisibleToUser()).thenReturn(builder.mVisibleToUser);
        when(node.isEnabled()).thenReturn(builder.mEnabled);
        when(node.refresh()).thenReturn(builder.mInViewTree);
        when(node.getContentDescription()).thenReturn(builder.mContentDescription);
        when(node.getActionList()).thenReturn(builder.mActionList);

        builder.mNodeList.add(node);
        return node;
    }

    NodeBuilder setWindow(@Nullable AccessibilityWindowInfo window) {
        mWindow = window;
        return this;
    }

    NodeBuilder setWindowId(int windowId) {
        mWindowId = windowId;
        return this;
    }

    NodeBuilder setParent(@Nullable AccessibilityNodeInfo parent) {
        mParent = parent;
        return this;
    }

    NodeBuilder setClassName(@Nullable String className) {
        mClassName = className;
        return this;
    }

    NodeBuilder setBoundsInScreen(@Nullable Rect boundsInScreen) {
        mBoundsInScreen = boundsInScreen;
        return this;
    }

    NodeBuilder setFocusable(boolean focusable) {
        mFocusable = focusable;
        return this;
    }

    NodeBuilder setVisibleToUser(boolean visibleToUser) {
        mVisibleToUser = visibleToUser;
        return this;
    }

    NodeBuilder setEnabled(boolean enabled) {
        mEnabled = enabled;
        return this;
    }

    NodeBuilder setInViewTree(boolean inViewTree) {
        mInViewTree = inViewTree;
        return this;
    }

    NodeBuilder setContentDescription(@Nullable String contentDescription) {
        mContentDescription = contentDescription;
        return this;
    }

    NodeBuilder setActions(AccessibilityNodeInfo.AccessibilityAction... actions) {
        mActionList = Arrays.asList(actions);
        return this;
    }

    NodeBuilder setFocusArea() {
        return setClassName(FOCUS_AREA_CLASS_NAME).setFocusable(false);
    }

    NodeBuilder setFpv() {
        return setClassName(FOCUS_PARKING_VIEW_CLASS_NAME);
    }

    NodeBuilder setScrollableContainer() {
        return setContentDescription(ROTARY_VERTICALLY_SCROLLABLE);
    }

    /** Creates a NodeBuilder with the same states. */
    private NodeBuilder copy() {
        NodeBuilder copy = new NodeBuilder(this.mNodeList);
        copy.mWindow = mWindow;
        copy.mWindowId = mWindowId;
        copy.mParent = mParent;
        copy.mClassName = mClassName;
        copy.mBoundsInScreen = mBoundsInScreen;
        copy.mFocusable = mFocusable;
        copy.mVisibleToUser = mVisibleToUser;
        copy.mEnabled = mEnabled;
        copy.mInViewTree = mInViewTree;
        copy.mContentDescription = mContentDescription;
        copy.mActionList = mActionList;
        return copy;
    }

    /** Clears all the states but {@link #mNodeList}. */
    private void clear() {
        mWindow = null;
        mWindowId = UNDEFINED_WINDOW_ID;
        mParent = null;
        mClassName = null;
        mBoundsInScreen = null;
        mFocusable = true;
        mVisibleToUser = true;
        mEnabled = true;
        mInViewTree = true;
        mContentDescription = null;
        mActionList = new ArrayList<>();
    }
}
