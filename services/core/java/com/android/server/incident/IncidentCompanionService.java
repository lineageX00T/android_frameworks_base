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

package com.android.server.incident;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IIncidentAuthListener;
import android.os.IIncidentCompanion;
import android.os.IncidentManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// TODO: User changes should deny everything that's pending.

/**
 * Helper service for incidentd and dumpstated to provide user feedback
 * and authorization for bug and inicdent reports to be taken.
 */
public class IncidentCompanionService extends SystemService {
    static final String TAG = "IncidentCompanionService";

    private final Handler mHandler = new Handler();
    private final RequestQueue mRequestQueue = new RequestQueue(mHandler);
    private final PackageManager mPackageManager;
    private final AppOpsManager mAppOpsManager;

    //
    // All fields below must be protected by mLock
    //
    private final Object mLock = new Object();
    private final ArrayList<PendingReportRec> mPending = new ArrayList();

    /**
     * The next ID we'll use when we make a PendingReportRec.
     */
    private int mNextPendingId = 1;

    /**
     * One for each authorization that's pending.
     */
    private final class PendingReportRec {
        public int id;
        public String callingPackage;
        public int flags;
        public IIncidentAuthListener listener;
        public long addedRealtime;
        public long addedWalltime;

        /**
         * Construct a PendingReportRec, with an auto-incremented id.
         */
        PendingReportRec(String callingPackage, int flags, IIncidentAuthListener listener) {
            this.id = mNextPendingId++;
            this.callingPackage = callingPackage;
            this.flags = flags;
            this.listener = listener;
            this.addedRealtime = SystemClock.elapsedRealtime();
            this.addedWalltime = System.currentTimeMillis();
        }

        /**
         * Get the Uri that contains the flattened data.
         */
        Uri getUri() {
            return (new Uri.Builder())
                    .scheme(IncidentManager.URI_SCHEME)
                    .authority(IncidentManager.URI_AUTHORITY)
                    .path(IncidentManager.URI_PATH)
                    .appendQueryParameter(IncidentManager.URI_PARAM_ID, Integer.toString(id))
                    .appendQueryParameter(IncidentManager.URI_PARAM_CALLING_PACKAGE, callingPackage)
                    .appendQueryParameter(IncidentManager.URI_PARAM_FLAGS, Integer.toString(flags))
                    .appendQueryParameter(IncidentManager.URI_PARAM_TIMESTAMP,
                            Long.toString(addedWalltime))
                    .build();
        }
    }

    /**
     * Implementation of the IIncidentCompanion binder interface.
     */
    private final class BinderService extends IIncidentCompanion.Stub {
        /**
         * ONEWAY binder call to initiate authorizing the report.  The actual logic is posted
         * to mRequestQueue, and may happen later.  The security checks need to happen here.
         */
        @Override
        public void authorizeReport(int callingUid, final String callingPackage, final int flags,
                final IIncidentAuthListener listener) {
            enforceRequestAuthorizationPermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                // Starting the system server is complicated, and rather than try to
                // have a complicated lifecycle that we share with dumpstated and incidentd,
                // we will accept the request, and then display it whenever it becomes possible to.
                mRequestQueue.enqueue(listener.asBinder(), true, () -> {
                    authorizeReportImpl(callingUid, callingPackage, flags, listener);
                });
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * ONEWAY binder call to cancel the inbound authorization request.
         * <p>
         * This is a oneway call, and so is authorizeReport, so the
         * caller's ordering is preserved.  The other calls on this object are synchronous, so
         * their ordering is not guaranteed with respect to these calls.  So the implementation
         * sends out extra broadcasts to allow for eventual consistency.
         */
        public void cancelAuthorization(final IIncidentAuthListener listener) {
            enforceRequestAuthorizationPermission();

            // Caller can cancel if they don't want it anymore, and mRequestQueue elides
            // authorize/cancel pairs.
            final long ident = Binder.clearCallingIdentity();
            try {
                mRequestQueue.enqueue(listener.asBinder(), false, () -> {
                    cancelReportImpl(listener);
                });
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS binder call to get the list of reports that are pending confirmation
         * by the user.
         */
        @Override
        public List<String> getPendingReports() {
            enforceAuthorizePermission();

            synchronized (mLock) {
                final int size = mPending.size();
                final ArrayList<String> result = new ArrayList(size);
                for (int i = 0; i < size; i++) {
                    result.add(mPending.get(i).getUri().toString());
                }
                return result;
            }
        }

        /**
         * ONEWAY binder call to mark a report as approved.
         */
        @Override
        public void approveReport(String uri) {
            enforceAuthorizePermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                final PendingReportRec rec;
                synchronized (mLock) {
                    rec = findAndRemovePendingReportRecLocked(uri);
                    if (rec == null) {
                        Log.e(TAG, "confirmApproved: Couldn't find record for uri: " + uri);
                        return;
                    }
                }

                // Re-do the broadcast, so whoever is listening knows the list changed,
                // in case another one was added in the meantime.
                sendBroadcast();

                Log.i(TAG, "Approved report: " + uri);
                try {
                    rec.listener.onReportApproved();
                } catch (RemoteException ex) {
                    Log.w(TAG, "Failed calling back for approval for: " + uri, ex);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * ONEWAY binder call to mark a report as NOT approved.
         */
        @Override
        public void denyReport(String uri) {
            enforceAuthorizePermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                final PendingReportRec rec;
                synchronized (mLock) {
                    rec = findAndRemovePendingReportRecLocked(uri);
                    if (rec == null) {
                        Log.e(TAG, "confirmDenied: Couldn't find record for uri: " + uri);
                        return;
                    }
                }

                // Re-do the broadcast, so whoever is listening knows the list changed,
                // in case another one was added in the meantime.
                sendBroadcast();

                Log.i(TAG, "Denied report: " + uri);
                try {
                    rec.listener.onReportDenied();
                } catch (RemoteException ex) {
                    Log.w(TAG, "Failed calling back for denial for: " + uri, ex);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Implementation of adb shell dumpsys debugreportcompanion.
         */
        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, writer)) {
                return;
            }
            if (args.length == 0) {
                // Standard text dumpsys
                final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                synchronized (mLock) {
                    final int size = mPending.size();
                    writer.println("mPending: (" + size + ")");
                    for (int i = 0; i < size; i++) {
                        final PendingReportRec entry = mPending.get(i);
                        writer.println(String.format("  %11d %s: %s", entry.addedRealtime,
                                    df.format(new Date(entry.addedWalltime)),
                                    entry.getUri().toString()));
                    }
                }
            }
        }

        private void enforceRequestAuthorizationPermission() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.REQUEST_INCIDENT_REPORT_APPROVAL, null);
        }

        private void enforceAuthorizePermission() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.APPROVE_INCIDENT_REPORTS, null);
        }

    }

    /**
     * Construct new IncidentCompanionService with the context.
     */
    public IncidentCompanionService(Context context) {
        super(context);
        mPackageManager = context.getPackageManager();
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
    }

    /**
     * Initialize the service.  It is still not safe to do UI until
     * onBootPhase(SystemService.PHASE_BOOT_COMPLETED).
     */
    @Override
    public void onStart() {
        publishBinderService(Context.INCIDENT_COMPANION_SERVICE, new BinderService());
    }

    /**
     * Handle the boot process... Starts everything running once the system is
     * up enough for us to do UI.
     */
    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        switch (phase) {
            case SystemService.PHASE_BOOT_COMPLETED:
                // Release the enqueued work.
                mRequestQueue.start();
                break;
        }
    }

    /**
     * Start the confirmation process.
     */
    private void authorizeReportImpl(int callingUid, final String callingPackage, int flags,
            final IIncidentAuthListener listener) {
        // Enforce that the calling package pertains to the callingUid.
        if (!isPackageInUid(callingUid, callingPackage)) {
            Log.w(TAG, "Calling uid " + callingUid + " doesn't match package "
                    + callingPackage);
            denyReportBeforeAddingRec(listener, callingPackage);
            return;
        }

        // Find the primary user of this device.
        final int primaryUser = getAndValidateUser();
        if (primaryUser == UserHandle.USER_NULL) {
            denyReportBeforeAddingRec(listener, callingPackage);
            return;
        }

        // Find the approver app (hint: it's PermissionController).
        final ComponentName receiver = getApproverComponent(primaryUser);
        if (receiver == null) {
            // We couldn't find an approver... so deny the request here and now, before we
            // do anything else.
            denyReportBeforeAddingRec(listener, callingPackage);
            return;
        }

        // Save the record for when the PermissionController comes back to authorize it.
        PendingReportRec rec = null;
        synchronized (mLock) {
            rec = new PendingReportRec(callingPackage, flags, listener);
            mPending.add(rec);
        }

        try {
            listener.asBinder().linkToDeath(() -> {
                Log.i(TAG, "Got death notification listener=" + listener);
                cancelReportImpl(listener, receiver, primaryUser);
            }, 0);
        } catch (RemoteException ex) {
            Log.e(TAG, "Remote died while trying to register death listener: " + rec.getUri());
            // First, remove from our list.
            cancelReportImpl(listener, receiver, primaryUser);
        }

        // Go tell Permission controller to start asking the user.
        sendBroadcast(receiver, primaryUser);
    }

    /**
     * Cancel a pending report request (because of an explicit call to cancel)
     */
    private void cancelReportImpl(IIncidentAuthListener listener) {
        final int primaryUser = getAndValidateUser();
        final ComponentName receiver = getApproverComponent(primaryUser);
        if (primaryUser != UserHandle.USER_NULL && receiver != null) {
            cancelReportImpl(listener, receiver, primaryUser);
        }
    }

    /**
     * Cancel a pending report request (either because of an explicit call to cancel
     * by the calling app, or because of a binder death).
     */
    private void cancelReportImpl(IIncidentAuthListener listener, ComponentName receiver,
            int primaryUser) {
        // First, remove from our list.
        synchronized (mLock) {
            removePendingReportRecLocked(listener);
        }
        // Second, call back to PermissionController to say it's canceled.
        sendBroadcast(receiver, primaryUser);
    }

    /**
     * Send an extra copy of the broadcast, to tell them that the list has changed
     * because of an addition or removal.  This function is less aggressive than
     * authorizeReportImpl in logging about failures, because this is for use in
     * cleanup cases to keep the apps' list in sync with ours.
     */
    private void sendBroadcast() {
        final int primaryUser = getAndValidateUser();
        if (primaryUser == UserHandle.USER_NULL) {
            return;
        }
        final ComponentName receiver = getApproverComponent(primaryUser);
        if (receiver == null) {
            return;
        }
        sendBroadcast(receiver, primaryUser);
    }

    /**
     * Send the confirmation broadcast.
     */
    private void sendBroadcast(ComponentName receiver, int primaryUser) {
        final Intent intent = new Intent(Intent.ACTION_PENDING_INCIDENT_REPORTS_CHANGED);
        intent.setComponent(receiver);

        // Send it to the primary user.
        getContext().sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(primaryUser),
                android.Manifest.permission.APPROVE_INCIDENT_REPORTS);
    }

    /**
     * Remove a PendingReportRec keyed by uri, and return it.
     */
    private PendingReportRec findAndRemovePendingReportRecLocked(String uriString) {
        final Uri uri = Uri.parse(uriString);
        final int id;
        try {
            final String idStr = uri.getQueryParameter(IncidentManager.URI_PARAM_ID);
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException ex) {
            Log.w(TAG, "Can't parse id from: " + uriString);
            return null;
        }
        final int size = mPending.size();
        for (int i = 0; i < size; i++) {
            final PendingReportRec rec = mPending.get(i);
            if (rec.id == id) {
                mPending.remove(i);
                return rec;
            }
        }
        return null;
    }

    /**
     * Remove a PendingReportRec keyed by listener.
     */
    private void removePendingReportRecLocked(IIncidentAuthListener listener) {
        final int size = mPending.size();
        for (int i = 0; i < size; i++) {
            final PendingReportRec rec = mPending.get(i);
            if (rec.listener.asBinder() == listener.asBinder()) {
                Log.i(TAG, "  ...Removed PendingReportRec index=" + i + ": " + rec.getUri());
                mPending.remove(i);
            }
        }
    }

    /**
     * Just call listener.deny() (wrapping the RemoteException), without try to
     * add it to the list.
     */
    private void denyReportBeforeAddingRec(IIncidentAuthListener listener, String pkg) {
        try {
            listener.onReportDenied();
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed calling back for denial for " + pkg, ex);
        }
    }

    /**
     * Check whether the current user is the primary user, and return the user id if they are.
     * Returns UserHandle.USER_NULL if not valid.
     */
    private int getAndValidateUser() {
        // Current user
        UserInfo currentUser;
        try {
            currentUser = ActivityManager.getService().getCurrentUser();
        } catch (RemoteException ex) {
            // We're already inside the system process.
            throw new RuntimeException(ex);
        }

        // Primary user
        final UserManager um = UserManager.get(getContext());
        final UserInfo primaryUser = um.getPrimaryUser();

        // Check that we're using the right user.
        if (currentUser == null) {
            Log.w(TAG, "No current user.  Nobody to approve the report."
                    + " The report will be denied.");
            return UserHandle.USER_NULL;
        }
        if (primaryUser == null) {
            Log.w(TAG, "No primary user.  Nobody to approve the report."
                    + " The report will be denied.");
            return UserHandle.USER_NULL;
        }
        if (primaryUser.id != currentUser.id) {
            Log.w(TAG, "Only the primary user can approve bugreports, but they are not"
                    + " the current user. The report will be denied.");
            return UserHandle.USER_NULL;
        }

        return primaryUser.id;
    }

    /**
     * Return the ComponentName of the BroadcastReceiver that will approve reports.
     * The system must have zero or one of these installed.  We only look on the
     * system partition.  When the broadcast happens, the component will also need
     * have the APPROVE_INCIDENT_REPORTS permission.
     */
    private ComponentName getApproverComponent(int userId) {
        // Find the one true BroadcastReceiver
        final Intent intent = new Intent(Intent.ACTION_PENDING_INCIDENT_REPORTS_CHANGED);
        final List<ResolveInfo> matches = mPackageManager.queryBroadcastReceiversAsUser(intent,
                PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().getComponentName();
        } else {
            Log.w(TAG, "Didn't find exactly one BroadcastReceiver to handle "
                    + Intent.ACTION_PENDING_INCIDENT_REPORTS_CHANGED
                    + ". The report will be denied. size="
                    + matches.size() + ": matches=" + matches);
            return null;
        }
    }

    /**
     * Return whether the package is one of the packages installed for the uid.
     */
    private boolean isPackageInUid(int uid, String packageName) {
        try {
            mAppOpsManager.checkPackage(uid, packageName);
            return true;
        } catch (SecurityException ex) {
            return false;
        }
    }
}
