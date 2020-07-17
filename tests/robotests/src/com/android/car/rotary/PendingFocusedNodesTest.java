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

import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class PendingFocusedNodesTest {
    private static final long TIMEOUT_MS = 200;
    private static final long UPTIME_MS = 0;

    @Spy
    private PendingFocusedNodes mPendingFocusedNodes;

    private AccessibilityNodeInfo mNode1;
    private AccessibilityNodeInfo mNode2;

    @Before
    public void setUp() {
        mPendingFocusedNodes = spy(new PendingFocusedNodes(TIMEOUT_MS));
        doReturn(UPTIME_MS).when(mPendingFocusedNodes).getUptimeMs();

        NodeCopier nodeCopier = mock(NodeCopier.class);
        doAnswer(returnsFirstArg()).when(nodeCopier).copy(any(AccessibilityNodeInfo.class));
        mPendingFocusedNodes.setNodeCopier(nodeCopier);

        mNode1 = new NodeBuilder().setInViewTree(true).build();
        mNode2 = new NodeBuilder().setInViewTree(true).build();
    }

    @Test
    public void testContains() {
        assertThat(mPendingFocusedNodes.contains(mNode1)).isFalse();

        mPendingFocusedNodes.put(mNode1);
        assertThat(mPendingFocusedNodes.contains(mNode1)).isTrue();
        assertThat(mPendingFocusedNodes.contains(mNode2)).isFalse();
    }

    @Test
    public void testIsEmpty() {
        assertThat(mPendingFocusedNodes.isEmpty()).isTrue();

        mPendingFocusedNodes.put(mNode1);
        assertThat(mPendingFocusedNodes.isEmpty()).isFalse();
    }

    @Test
    public void testRefreshDoesNotRemoveNode() {
        mPendingFocusedNodes.put(mNode1);
        mPendingFocusedNodes.refresh();
        assertThat(mPendingFocusedNodes.contains(mNode1)).isTrue();
    }

    @Test
    public void testRefreshRemovesExpiredNode() {
        mPendingFocusedNodes.put(mNode1);
        when(mPendingFocusedNodes.getUptimeMs()).thenReturn(UPTIME_MS + TIMEOUT_MS + 1);
        mPendingFocusedNodes.refresh();
        assertThat(mPendingFocusedNodes.isEmpty()).isTrue();
    }

    @Test
    public void testRefreshRemovesNodeNotInViewTree() {
        AccessibilityNodeInfo node = new NodeBuilder().setInViewTree(false).build();
        mPendingFocusedNodes.put(node);
        mPendingFocusedNodes.refresh();
        assertThat(mPendingFocusedNodes.isEmpty()).isTrue();
    }

    @Test
    public void testRemoveIf() {
        mPendingFocusedNodes.put(mNode1);
        AccessibilityNodeInfo focusable1 = new NodeBuilder()
                .setFocusable(true)
                .setInViewTree(true)
                .build();
        AccessibilityNodeInfo focusable2 = new NodeBuilder()
                .setFocusable(true)
                .setInViewTree(true)
                .build();
        mPendingFocusedNodes.put(focusable1);
        mPendingFocusedNodes.put(focusable2);

        boolean removed = mPendingFocusedNodes.removeFirstIf(node -> node.isFocusable());
        assertThat(removed).isTrue();
        assertThat(mPendingFocusedNodes.size()).isEqualTo(2);
        assertThat(mPendingFocusedNodes.contains(mNode1)).isTrue();

        removed = mPendingFocusedNodes.removeFirstIf(node -> node.isFocusable());
        assertThat(removed).isTrue();
        assertThat(mPendingFocusedNodes.size()).isEqualTo(1);
        assertThat(mPendingFocusedNodes.contains(focusable1)).isFalse();
        assertThat(mPendingFocusedNodes.contains(focusable2)).isFalse();
        assertThat(mPendingFocusedNodes.contains(mNode1)).isTrue();

        removed = mPendingFocusedNodes.removeFirstIf(node -> node.isEnabled());
        assertThat(removed).isFalse();
        assertThat(mPendingFocusedNodes.contains(mNode1)).isTrue();
    }
}
