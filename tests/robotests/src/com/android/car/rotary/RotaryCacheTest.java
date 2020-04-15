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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class RotaryCacheTest {
    private static final int FOCUS_CACHE_SIZE = 10;
    private static final int FOCUS_AREA_CACHE_SIZE = 5;
    private static final int CACHE_TIME_OUT_MS = 10000;

    private Context mContext;
    private RotaryCache mRotaryCache;

    private List<AccessibilityNodeInfo> mNodes;
    private AccessibilityNodeInfo mFocusArea;
    private AccessibilityNodeInfo mTargetFocusArea;
    private AccessibilityNodeInfo mFocusedNode;

    private long mValidTime;
    private long mExpiredTime;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mRotaryCache = new RotaryCache(
                /* focusHistoryCacheType= */ RotaryCache.CACHE_TYPE_EXPIRED_AFTER_SOME_TIME,
                /* focusHistoryCacheSize= */ FOCUS_CACHE_SIZE,
                /* focusHistoryExpirationTimeMs= */ CACHE_TIME_OUT_MS,
                /* focusAreaHistoryCacheType= */ RotaryCache.CACHE_TYPE_EXPIRED_AFTER_SOME_TIME,
                /* focusAreaHistoryCacheSize= */ FOCUS_AREA_CACHE_SIZE,
                /* focusAreaHistoryExpirationTimeMs= */ CACHE_TIME_OUT_MS);

        mNodes = new ArrayList<>();
        mFocusArea = createNode();
        mTargetFocusArea = createNode();
        mFocusedNode = createNode();

        mValidTime = CACHE_TIME_OUT_MS - 1;
        mExpiredTime = CACHE_TIME_OUT_MS + 1;
    }

    @Test
    public void testCreateNode() {
        AccessibilityNodeInfo node1 = createNode();
        AccessibilityNodeInfo node2 = createNode();

        assertThat(node1).isNotEqualTo(node2);
    }

    @Test
    public void testClearFocusAreaHistoryCache() {
        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, View.FOCUS_UP, 0);
        assertThat(mRotaryCache.isFocusAreaHistoryCacheEmpty()).isFalse();

        mRotaryCache.clearFocusAreaHistory();
        assertThat(mRotaryCache.isFocusAreaHistoryCacheEmpty()).isTrue();
    }

    @Test
    public void testGetFocusedNodeInTheCache() {
        // Save a focused node.
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // In the cache.
        AccessibilityNodeInfo node = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(node).isEqualTo(mFocusedNode);

        // Recycle the node since it's created by Utils.copyNode().
        node.recycle();
    }

    @Test
    public void testGetFocusedNodeNotInTheCache() {
        // Not in the cache.
        AccessibilityNodeInfo node = mRotaryCache.getFocusedNode(mTargetFocusArea, mValidTime);
        assertThat(node).isNull();
    }

    @Test
    public void testGetFocusedNodeExpiredCache() {
        // Save a focused node.
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // Expired cache.
        AccessibilityNodeInfo node = mRotaryCache.getFocusedNode(mFocusArea, mExpiredTime);
        assertThat(node).isNull();
    }

    /** Saves so many nodes that the cache overflows and the previously saved node is kicked off. */
    @Test
    public void testFocusCacheOverflow() {
        // Save a focused node (mFocusedNode).
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // Save FOCUS_CACHE_SIZE nodes to make the cache overflow.
        for (int i = 0; i < FOCUS_CACHE_SIZE; i++) {
            saveFocusHistory();
        }

        // mFocusedNode should have been kicked off.
        AccessibilityNodeInfo savedNode = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(savedNode).isNull();
    }

    @Test
    public void testFocusCacheNotOverflow() {
        // Save a focused node (mFocusedNode).
        mRotaryCache.saveFocusedNode(mFocusArea, mFocusedNode, 0);

        // Save (FOCUS_CACHE_SIZE - 1) nodes so that the cache is just full.
        for (int i = 0; i < FOCUS_CACHE_SIZE - 1; i++) {
            saveFocusHistory();
        }

        // mFocusedNode should still be in the cache.
        AccessibilityNodeInfo savedNode = mRotaryCache.getFocusedNode(mFocusArea, mValidTime);
        assertThat(savedNode).isEqualTo(mFocusedNode);

        savedNode.recycle();
    }

    @Test
    public void testGetTargetFocusAreaInTheCache() {
        int direction = View.FOCUS_LEFT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // In the cache.
        AccessibilityNodeInfo node =
                mRotaryCache.getTargetFocusArea(mTargetFocusArea, oppositeDirection, mValidTime);
        assertThat(node).isEqualTo(mFocusArea);

        node.recycle();
    }

    @Test
    public void testGetTargetFocusAreaNotInTheCache() {
        int direction = View.FOCUS_LEFT;

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Not in the cache because the direction doesn't match.
        AccessibilityNodeInfo node = mRotaryCache.getTargetFocusArea(mTargetFocusArea, direction,
                mValidTime);
        assertThat(node).isNull();
    }

    @Test
    public void testGetTargetFocusAreaExpiredCache() {
        int direction = View.FOCUS_LEFT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Expired cache.
        AccessibilityNodeInfo node = mRotaryCache.getTargetFocusArea(mTargetFocusArea,
                oppositeDirection, mExpiredTime);
        assertThat(node).isNull();
    }

    /**
     * Saves so many focus areas that the cache overflows and the previously saved focus area is
     * kicked off.
     */
    @Test
    public void testFocusAreaCacheOverflow() {
        int direction = View.FOCUS_RIGHT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Save FOCUS_AREA_CACHE_SIZE focus areas to make the cache overflow.
        for (int i = 0; i < FOCUS_AREA_CACHE_SIZE; i++) {
            saveFocusAreaHistory();
        }

        // Previously saved focus area should have been kicked off.
        AccessibilityNodeInfo savedFocusArea =
                mRotaryCache.getTargetFocusArea(mTargetFocusArea, oppositeDirection, mValidTime);
        assertThat(savedFocusArea).isNull();
    }

    @Test
    public void testFocusAreaCacheNotOverflow() {
        int direction = View.FOCUS_RIGHT;
        int oppositeDirection = RotaryCache.getOppositeDirection(direction);

        // Save a focus area.
        mRotaryCache.saveTargetFocusArea(mFocusArea, mTargetFocusArea, direction, 0);

        // Save (FOCUS_AREA_CACHE_SIZE - 1) focus areas so that the cache is just full.
        for (int i = 0; i < FOCUS_AREA_CACHE_SIZE - 1; i++) {
            saveFocusAreaHistory();
        }

        // Previously saved focus area should still be in the cache.
        AccessibilityNodeInfo savedFocusArea =
                mRotaryCache.getTargetFocusArea(mTargetFocusArea, oppositeDirection, mValidTime);
        assertThat(savedFocusArea).isEqualTo(mFocusArea);

        savedFocusArea.recycle();
    }

    @After
    public void tearDown() {
        Utils.recycleNodes(mNodes);
    }

    /** Creates a FocusHistory and saves it in the cache. */
    private void saveFocusHistory() {
        AccessibilityNodeInfo focusArea = createNode();
        AccessibilityNodeInfo node = createNode();
        mRotaryCache.saveFocusedNode(focusArea, node, 0);
    }

    /** Creates a FocusAreaHistory and saves it in the cache. */
    private void saveFocusAreaHistory() {
        AccessibilityNodeInfo focusArea = createNode();
        AccessibilityNodeInfo targetFocusArea = createNode();
        int direction = View.FOCUS_UP; // Any valid direction (up, down, left, or right) is fine.
        mRotaryCache.saveTargetFocusArea(focusArea, targetFocusArea, direction, 0);
    }

    /**
     * Creates an {@link AccessibilityNodeInfo}. The result will be recycled when the test is
     * completed.
     */
    private AccessibilityNodeInfo createNode() {
        View view = new View(mContext);
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        // Save the node for recycling.
        mNodes.add(node);
        return node;
    }
}
