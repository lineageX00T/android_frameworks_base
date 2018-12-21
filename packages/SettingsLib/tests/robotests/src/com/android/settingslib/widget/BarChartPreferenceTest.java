/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class BarChartPreferenceTest {

    private Context mContext;
    private View mBarChartView;
    private Drawable mIcon;
    private BarView mBarView1;
    private BarView mBarView2;
    private BarView mBarView3;
    private BarView mBarView4;
    private PreferenceViewHolder mHolder;
    private BarChartPreference mPreference;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mBarChartView = View.inflate(mContext, R.layout.settings_bar_chart, null /* parent */);
        mHolder = PreferenceViewHolder.createInstanceForTests(mBarChartView);
        mPreference = new BarChartPreference(mContext, null /* attrs */);
        mPreference.setBarChartTitle(R.string.debug_app);
        mPreference.setBarChartDetails(R.string.debug_app);


        mIcon = mContext.getDrawable(R.drawable.ic_menu);
        mBarView1 = (BarView) mBarChartView.findViewById(R.id.bar_view1);
        mBarView2 = (BarView) mBarChartView.findViewById(R.id.bar_view2);
        mBarView3 = (BarView) mBarChartView.findViewById(R.id.bar_view3);
        mBarView4 = (BarView) mBarChartView.findViewById(R.id.bar_view4);
    }

    @Test
    public void setBarChartTitleRes_setTitleRes_showInBarChartTitle() {
        final TextView titleView = (TextView) mBarChartView.findViewById(R.id.bar_chart_title);

        mPreference.setBarChartTitle(R.string.debug_app);
        mPreference.onBindViewHolder(mHolder);

        assertThat(titleView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(titleView.getText()).isEqualTo(mContext.getText(R.string.debug_app));
    }

    @Test
    public void setBarChartDetailsRes_setDetailsRes_showInBarChartDetails() {
        final TextView detailsView = (TextView) mBarChartView.findViewById(R.id.bar_chart_details);

        mPreference.setBarChartDetails(R.string.debug_app);
        mPreference.onBindViewHolder(mHolder);

        assertThat(detailsView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(detailsView.getText()).isEqualTo(mContext.getText(R.string.debug_app));
    }

    @Test
    public void setBarChartDetailsClickListener_setClickListener_detailsViewAttachClickListener() {
        final TextView detailsView = (TextView) mBarChartView.findViewById(R.id.bar_chart_details);

        mPreference.setBarChartDetailsClickListener(v -> {
        });
        mPreference.onBindViewHolder(mHolder);

        assertThat(detailsView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(detailsView.hasOnClickListeners()).isTrue();
    }

    @Test
    public void setAllBarViewsInfo_setOneBarViewInfo_showOneBarView() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app)
        };

        mPreference.setAllBarViewsInfo(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("10");

        assertThat(mBarView2.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBarView3.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBarView4.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setAllBarViewsInfo_setTwoBarViewsInfo_showTwoBarViews() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 20 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app)
        };

        mPreference.setAllBarViewsInfo(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("20");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("10");

        assertThat(mBarView3.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBarView4.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setAllBarViewsInfo_setThreeBarViewsInfo_showThreeBarViews() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 20 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 5 /* barNumber */, R.string.debug_app)
        };

        mPreference.setAllBarViewsInfo(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("20");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("10");
        assertThat(mBarView3.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView3.getTitle()).isEqualTo("5");

        assertThat(mBarView4.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setAllBarViewsInfo_setFourBarViewsInfo_showFourBarViews() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 20 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 5 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 2 /* barNumber */, R.string.debug_app),
        };

        mPreference.setAllBarViewsInfo(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("20");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("10");
        assertThat(mBarView3.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView3.getTitle()).isEqualTo("5");
        assertThat(mBarView4.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView4.getTitle()).isEqualTo("2");
    }

    @Test
    public void setAllBarViewsInfo_setFourBarViewsInfo_barViewWasSortedInDescending() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 30 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 50 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 5 /* barNumber */, R.string.debug_app),
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
        };

        mPreference.setAllBarViewsInfo(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getTitle()).isEqualTo("50");
        assertThat(mBarView2.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView2.getTitle()).isEqualTo("30");
        assertThat(mBarView3.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView3.getTitle()).isEqualTo("10");
        assertThat(mBarView4.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView4.getTitle()).isEqualTo("5");
    }

    @Test
    public void setAllBarViewsInfo_setValidSummaryRes_barViewShouldShowSummary() {
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{
                new BarViewInfo(mIcon, 10 /* barNumber */, R.string.debug_app),
        };

        mPreference.setAllBarViewsInfo(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.getSummary()).isEqualTo(mContext.getText(R.string.debug_app));
    }

    @Test
    public void setAllBarViewsInfo_setClickListenerForBarView_barViewAttachClickListener() {
        final BarViewInfo viewInfo = new BarViewInfo(mIcon, 30 /* barNumber */, R.string.debug_app);
        viewInfo.setClickListener(v -> {
        });
        final BarViewInfo[] barViewsInfo = new BarViewInfo[]{viewInfo};

        mPreference.setAllBarViewsInfo(barViewsInfo);
        mPreference.onBindViewHolder(mHolder);

        assertThat(mBarView1.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mBarView1.hasOnClickListeners()).isTrue();
    }
}