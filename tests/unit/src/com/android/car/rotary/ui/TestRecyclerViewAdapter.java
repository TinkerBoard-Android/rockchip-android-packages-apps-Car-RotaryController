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

package com.android.car.rotary.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.android.car.rotary.R;

import java.util.List;

/** Test adapter for recycler view. */
public class TestRecyclerViewAdapter extends RecyclerView.Adapter<TestRecyclerViewViewHolder> {

    private final Context mContext;
    private List<String> mItems;
    private boolean mItemsFocusable;

    public TestRecyclerViewAdapter(Context context) {
        mContext = context;
        mItemsFocusable = false;
    }

    /** Strings to show in recycler view. */
    public void setItems(List<String> items) {
        mItems = items;
    }

    public void setItemsFocusable(boolean itemsFocusable) {
        mItemsFocusable = itemsFocusable;
    }

    @Override
    public TestRecyclerViewViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View rootView = LayoutInflater.from(mContext).inflate(
                R.layout.test_recycler_view_view_holder, parent, false);
        rootView.setFocusable(mItemsFocusable);
        return new TestRecyclerViewViewHolder(rootView);
    }

    @Override
    public void onBindViewHolder(TestRecyclerViewViewHolder holder, int position) {
        holder.bind(mItems.get(position));
        holder.itemView.setFocusable(mItemsFocusable);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }
}
