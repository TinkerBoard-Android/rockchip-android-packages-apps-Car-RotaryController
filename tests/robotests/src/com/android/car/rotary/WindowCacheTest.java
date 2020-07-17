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

import static android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION;
import static android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WindowCacheTest {

    private static final int WINDOW_ID_1 = 11;
    private static final int WINDOW_ID_2 = 12;
    private static final int WINDOW_ID_3 = 13;

    private WindowCache mWindowCache;

    @Before
    public void setUp() {
        mWindowCache = new WindowCache();
        mWindowCache.put(WINDOW_ID_1, TYPE_APPLICATION);
        mWindowCache.put(WINDOW_ID_2, TYPE_SYSTEM);
    }

    @Test
    public void testGetWindowType() {
        Integer type = mWindowCache.getWindowType(WINDOW_ID_1);
        assertThat(type).isEqualTo(TYPE_APPLICATION);

        mWindowCache.remove(WINDOW_ID_1);
        type = mWindowCache.getWindowType(WINDOW_ID_1);
        assertThat(type).isNull();

        type = mWindowCache.getWindowType(WINDOW_ID_3);
        assertThat(type).isNull();
    }

    @Test
    public void testGetMostRecentWindowId() {
        Integer id = mWindowCache.getMostRecentWindowId();
        assertThat(id).isEqualTo(WINDOW_ID_2);

        mWindowCache.remove(id);
        id = mWindowCache.getMostRecentWindowId();
        assertThat(id).isEqualTo(WINDOW_ID_1);

        mWindowCache.remove(id);
        id = mWindowCache.getMostRecentWindowId();
        assertThat(id).isNull();
    }
}
