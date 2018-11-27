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

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.app.Notification;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.AmbientPulseManager.OnAmbientChangedListener;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.notification.NotificationData.Entry;
import com.android.systemui.statusbar.notification.row.NotificationInflater.AsyncInflationTask;
import com.android.systemui.statusbar.notification.row.NotificationInflater.InflationFlag;
import com.android.systemui.statusbar.phone.NotificationGroupManager.NotificationGroup;
import com.android.systemui.statusbar.phone.NotificationGroupManager.OnGroupChangeListener;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;

/**
 * A helper class dealing with the alert interactions between {@link NotificationGroupManager},
 * {@link HeadsUpManager}, {@link AmbientPulseManager}. In particular, this class deals with keeping
 * the correct notification in a group alerting based off the group suppression.
 */
public class NotificationGroupAlertTransferHelper implements OnGroupChangeListener,
        OnHeadsUpChangedListener, OnAmbientChangedListener, StateListener {

    private static final long ALERT_TRANSFER_TIMEOUT = 300;

    /**
     * The list of entries containing group alert metadata for each group. Keyed by group key.
     */
    private final ArrayMap<String, GroupAlertEntry> mGroupAlertEntries = new ArrayMap<>();

    /**
     * The list of entries currently inflating that should alert after inflation. Keyed by
     * notification key.
     */
    private final ArrayMap<String, PendingAlertInfo> mPendingAlerts = new ArrayMap<>();

    private HeadsUpManager mHeadsUpManager;
    private final AmbientPulseManager mAmbientPulseManager =
            Dependency.get(AmbientPulseManager.class);
    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);

    // TODO(b/119637830): It would be good if GroupManager already had all pending notifications as
    // normal children (i.e. add notifications to GroupManager before inflation) so that we don't
    // have to have this dependency. We'd also have to worry less about the suppression not being up
    // to date.
    /**
     * Notifications that are currently inflating for the first time. Used to remove an incorrectly
     * alerting notification faster.
     */
    private HashMap<String, Entry> mPendingNotifications;

    private boolean mIsDozing;

    public NotificationGroupAlertTransferHelper() {
        Dependency.get(StatusBarStateController.class).addListener(this);
    }

    /**
     * Whether or not a notification has transferred its alert state to the notification and
     * the notification should alert after inflating.
     *
     * @param entry notification to check
     * @return true if the entry was transferred to and should inflate + alert
     */
    public boolean isAlertTransferPending(@NonNull Entry entry) {
        PendingAlertInfo alertInfo = mPendingAlerts.get(entry.key);
        return alertInfo != null && alertInfo.isStillValid();
    }

    /**
     * Removes any alerts pending on this entry. Note that this will not stop any inflation tasks
     * started by a transfer, so this should only be used as clean-up for when inflation is stopped
     * and the pending alert no longer needs to happen.
     *
     * @param key notification key that may have info that needs to be cleaned up
     */
    public void cleanUpPendingAlertInfo(@NonNull String key) {
        mPendingAlerts.remove(key);
    }

    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
    }

    public void setPendingEntries(HashMap<String, Entry> pendingNotifications) {
        mPendingNotifications = pendingNotifications;
    }

    @Override
    public void onStateChanged(int newState) {}

    @Override
    public void onDozingChanged(boolean isDozing) {
        if (mIsDozing != isDozing) {
            for (GroupAlertEntry groupAlertEntry : mGroupAlertEntries.values()) {
                groupAlertEntry.mLastAlertTransferTime = 0;
                groupAlertEntry.mAlertSummaryOnNextAddition = false;
            }
        }
        mIsDozing = isDozing;
    }

    @Override
    public void onGroupCreated(NotificationGroup group, String groupKey) {
        mGroupAlertEntries.put(groupKey, new GroupAlertEntry(group));
    }

    @Override
    public void onGroupRemoved(NotificationGroup group, String groupKey) {
        mGroupAlertEntries.remove(groupKey);
    }

    @Override
    public void onGroupSuppressionChanged(NotificationGroup group, boolean suppressed) {
        AlertingNotificationManager alertManager = getActiveAlertManager();
        if (suppressed) {
            if (alertManager.isAlerting(group.summary.key)) {
                handleSuppressedSummaryAlerted(group.summary, alertManager);
            }
        } else {
            // Group summary can be null if we are no longer suppressed because the summary was
            // removed. In that case, we don't need to alert the summary.
            if (group.summary == null) {
                return;
            }
            GroupAlertEntry groupAlertEntry = mGroupAlertEntries.get(mGroupManager.getGroupKey(
                    group.summary.notification));
            // Group is no longer suppressed. We should check if we need to transfer the alert
            // back to the summary now that it's no longer suppressed.
            if (groupAlertEntry.mAlertSummaryOnNextAddition) {
                if (!alertManager.isAlerting(group.summary.key)) {
                    alertNotificationWhenPossible(group.summary, alertManager);
                }
                groupAlertEntry.mAlertSummaryOnNextAddition = false;
            } else {
                checkShouldTransferBack(groupAlertEntry);
            }
        }
    }

    @Override
    public void onAmbientStateChanged(Entry entry, boolean isAmbient) {
        onAlertStateChanged(entry, isAmbient, mAmbientPulseManager);
    }

    @Override
    public void onHeadsUpStateChanged(Entry entry, boolean isHeadsUp) {
        onAlertStateChanged(entry, isHeadsUp, mHeadsUpManager);
    }

    private void onAlertStateChanged(Entry entry, boolean isAlerting,
            AlertingNotificationManager alertManager) {
        if (isAlerting && mGroupManager.isSummaryOfSuppressedGroup(entry.notification)) {
            handleSuppressedSummaryAlerted(entry, alertManager);
        }
    }

    /**
     * Called when the entry's reinflation has finished. If there is an alert pending, we then
     * show the alert.
     *
     * @param entry entry whose inflation has finished
     */
    public void onInflationFinished(@NonNull Entry entry) {
        PendingAlertInfo alertInfo = mPendingAlerts.remove(entry.key);
        if (alertInfo != null) {
            if (alertInfo.isStillValid()) {
                alertNotificationWhenPossible(entry, getActiveAlertManager());
            } else {
                // The transfer is no longer valid. Free the content.
                entry.row.freeContentViewWhenSafe(alertInfo.mAlertManager.getContentFlag());
            }
        }
    }

    /**
     * Called when a new notification has been posted but is not inflated yet. We use this to see
     * as early as we can if we need to abort a transfer.
     *
     * @param entry entry that has been added
     */
    public void onPendingEntryAdded(@NonNull Entry entry) {
        String groupKey = mGroupManager.getGroupKey(entry.notification);
        GroupAlertEntry groupAlertEntry = mGroupAlertEntries.get(groupKey);
        if (groupAlertEntry != null) {
            checkShouldTransferBack(groupAlertEntry);
        }
    }

    /**
     * Gets the number of new notifications pending inflation that will be added to the group
     * but currently aren't and should not alert.
     *
     * @param group group to check
     * @return the number of new notifications that will be added to the group
     */
    private int getPendingChildrenNotAlerting(@NonNull NotificationGroup group) {
        if (mPendingNotifications == null) {
            return 0;
        }
        int number = 0;
        Collection<Entry> values = mPendingNotifications.values();
        for (Entry entry : values) {
            if (isPendingNotificationInGroup(entry, group) && onlySummaryAlerts(entry)) {
                number++;
            }
        }
        return number;
    }

    /**
     * Checks if the pending inflations will add children to this group.
     *
     * @param group group to check
     * @return true if a pending notification will add to this group
     */
    private boolean pendingInflationsWillAddChildren(@NonNull NotificationGroup group) {
        if (mPendingNotifications == null) {
            return false;
        }
        Collection<Entry> values = mPendingNotifications.values();
        for (Entry entry : values) {
            if (isPendingNotificationInGroup(entry, group)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a new pending notification will be added to the group.
     *
     * @param entry pending notification
     * @param group group to check
     * @return true if the notification will add to the group, false o/w
     */
    private boolean isPendingNotificationInGroup(@NonNull Entry entry,
            @NonNull NotificationGroup group) {
        String groupKey = mGroupManager.getGroupKey(group.summary.notification);
        return mGroupManager.isGroupChild(entry.notification)
                && Objects.equals(mGroupManager.getGroupKey(entry.notification), groupKey)
                && !group.children.containsKey(entry.key);
    }

    /**
     * Handles the scenario where a summary that has been suppressed is alerted.  A suppressed
     * summary should for all intents and purposes be invisible to the user and as a result should
     * not alert.  When this is the case, it is our responsibility to pass the alert to the
     * appropriate child which will be the representative notification alerting for the group.
     *
     * @param summary the summary that is suppressed and alerting
     * @param alertManager the alert manager that manages the alerting summary
     */
    private void handleSuppressedSummaryAlerted(@NonNull Entry summary,
            @NonNull AlertingNotificationManager alertManager) {
        StatusBarNotification sbn = summary.notification;
        GroupAlertEntry groupAlertEntry =
                mGroupAlertEntries.get(mGroupManager.getGroupKey(sbn));
        if (!mGroupManager.isSummaryOfSuppressedGroup(summary.notification)
                || !alertManager.isAlerting(sbn.getKey())
                || groupAlertEntry == null) {
            return;
        }

        if (pendingInflationsWillAddChildren(groupAlertEntry.mGroup)) {
            // New children will actually be added to this group, let's not transfer the alert.
            return;
        }

        Entry child = mGroupManager.getLogicalChildren(summary.notification).iterator().next();
        if (child != null) {
            if (child.row.keepInParent()
                    || child.row.isRemoved()
                    || child.row.isDismissed()) {
                // The notification is actually already removed. No need to alert it.
                return;
            }
            if (!alertManager.isAlerting(child.key) && onlySummaryAlerts(summary)) {
                groupAlertEntry.mLastAlertTransferTime = SystemClock.elapsedRealtime();
            }
            transferAlertState(summary, child, alertManager);
        }
    }

    /**
     * Transfers the alert state one entry to another. We remove the alert from the first entry
     * immediately to have the incorrect one up as short as possible. The second should alert
     * when possible.
     *
     * @param fromEntry entry to transfer alert from
     * @param toEntry entry to transfer to
     * @param alertManager alert manager for the alert type
     */
    private void transferAlertState(@NonNull Entry fromEntry, @NonNull Entry toEntry,
            @NonNull AlertingNotificationManager alertManager) {
        alertManager.removeNotification(fromEntry.key, true /* releaseImmediately */);
        alertNotificationWhenPossible(toEntry, alertManager);
    }

    /**
     * Determines if we need to transfer the alert back to the summary from the child and does
     * so if needed.
     *
     * This can happen since notification groups are not delivered as a whole unit and it is
     * possible we erroneously transfer the alert from the summary to the child even though
     * more children are coming. Thus, if a child is added within a certain timeframe after we
     * transfer, we back out and alert the summary again.
     *
     * @param groupAlertEntry group alert entry to check
     */
    private void checkShouldTransferBack(@NonNull GroupAlertEntry groupAlertEntry) {
        if (SystemClock.elapsedRealtime() - groupAlertEntry.mLastAlertTransferTime
                < ALERT_TRANSFER_TIMEOUT) {
            Entry summary = groupAlertEntry.mGroup.summary;
            AlertingNotificationManager alertManager = getActiveAlertManager();

            if (!onlySummaryAlerts(summary)) {
                return;
            }
            ArrayList<Entry> children = mGroupManager.getLogicalChildren(summary.notification);
            int numChildren = children.size();
            int numPendingChildren = getPendingChildrenNotAlerting(groupAlertEntry.mGroup);
            numChildren += numPendingChildren;
            if (numChildren <= 1) {
                return;
            }
            boolean releasedChild = false;
            for (int i = 0; i < children.size(); i++) {
                Entry entry = children.get(i);
                if (onlySummaryAlerts(entry) && alertManager.isAlerting(entry.key)) {
                    releasedChild = true;
                    alertManager.removeNotification(entry.key, true /* releaseImmediately */);
                }
                if (mPendingAlerts.containsKey(entry.key)) {
                    // This is the child that would've been removed if it was inflated.
                    releasedChild = true;
                    mPendingAlerts.get(entry.key).mAbortOnInflation = true;
                }
            }
            if (releasedChild && !alertManager.isAlerting(summary.key)) {
                boolean notifyImmediately = (numChildren - numPendingChildren) > 1;
                if (notifyImmediately) {
                    alertNotificationWhenPossible(summary, alertManager);
                } else {
                    // Should wait until the pending child inflates before alerting.
                    groupAlertEntry.mAlertSummaryOnNextAddition = true;
                }
                groupAlertEntry.mLastAlertTransferTime = 0;
            }
        }
    }

    /**
     * Tries to alert the notification. If its content view is not inflated, we inflate and continue
     * when the entry finishes inflating the view.
     *
     * @param entry entry to show
     * @param alertManager alert manager for the alert type
     */
    private void alertNotificationWhenPossible(@NonNull Entry entry,
            @NonNull AlertingNotificationManager alertManager) {
        @InflationFlag int contentFlag = alertManager.getContentFlag();
        if (!entry.row.isInflationFlagSet(contentFlag)) {
            mPendingAlerts.put(entry.key, new PendingAlertInfo(entry, alertManager));
            entry.row.updateInflationFlag(contentFlag, true /* shouldInflate */);
            entry.row.inflateViews();
            return;
        }
        if (alertManager.isAlerting(entry.key)) {
            alertManager.updateNotification(entry.key, true /* alert */);
        } else {
            alertManager.showNotification(entry);
        }
    }

    private AlertingNotificationManager getActiveAlertManager() {
        return mIsDozing ? mAmbientPulseManager : mHeadsUpManager;
    }

    private boolean onlySummaryAlerts(Entry entry) {
        return entry.notification.getNotification().getGroupAlertBehavior()
                == Notification.GROUP_ALERT_SUMMARY;
    }

    /**
     * Information about a pending alert used to determine if the alert is still needed when
     * inflation completes.
     */
    private class PendingAlertInfo {
        /**
         * The alert manager when the transfer is initiated.
         */
        final AlertingNotificationManager mAlertManager;

        /**
         * The original notification when the transfer is initiated. This is used to determine if
         * the transfer is still valid if the notification is updated.
         */
        final StatusBarNotification mOriginalNotification;
        final Entry mEntry;

        /**
         * The notification is still pending inflation but we've decided that we no longer need
         * the content view (e.g. suppression might have changed and we decided we need to transfer
         * back). However, there is no way to abort just this inflation if other inflation requests
         * have started (see {@link AsyncInflationTask#supersedeTask(InflationTask)}). So instead
         * we just flag it as aborted and free when it's inflated.
         */
        boolean mAbortOnInflation;

        PendingAlertInfo(Entry entry, AlertingNotificationManager alertManager) {
            mOriginalNotification = entry.notification;
            mEntry = entry;
            mAlertManager = alertManager;
        }

        /**
         * Whether or not the pending alert is still valid and should still alert after inflation.
         *
         * @return true if the pending alert should still occur, false o/w
         */
        private boolean isStillValid() {
            if (mAbortOnInflation) {
                // Notification is aborted due to the transfer being explicitly cancelled
                return false;
            }
            if (mAlertManager != getActiveAlertManager()) {
                // Alert manager has changed
                return false;
            }
            if (mEntry.notification.getGroupKey() != mOriginalNotification.getGroupKey()) {
                // Groups have changed
                return false;
            }
            if (mEntry.notification.getNotification().isGroupSummary()
                    != mOriginalNotification.getNotification().isGroupSummary()) {
                // Notification has changed from group summary to not or vice versa
                return false;
            }
            return true;
        }
    }

    /**
     * Contains alert metadata for the notification group used to determine when/how the alert
     * should be transferred.
     */
    private static class GroupAlertEntry {
        /**
         * The time when the last alert transfer from summary to child happened.
         */
        long mLastAlertTransferTime;
        boolean mAlertSummaryOnNextAddition;
        final NotificationGroup mGroup;

        GroupAlertEntry(NotificationGroup group) {
            this.mGroup = group;
        }
    }
}