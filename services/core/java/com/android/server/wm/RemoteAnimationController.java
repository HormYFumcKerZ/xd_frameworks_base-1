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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.server.wm.AnimationAdapterProto.REMOTE;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_REMOTE_ANIMATIONS;
import static com.android.server.wm.RemoteAnimationAdapterWrapperProto.TARGET;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.util.FastPrintWriter;
import com.android.server.protolog.ProtoLogImpl;
import com.android.server.protolog.common.ProtoLog;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/**
 * Helper class to run app animations in a remote process.
 */
class RemoteAnimationController implements DeathRecipient {
    private static final String TAG = TAG_WITH_CLASS_NAME
                    ? "RemoteAnimationController" : TAG_WM;
    private static final long TIMEOUT_MS = 2000;

    private final WindowManagerService mService;
    private final RemoteAnimationAdapter mRemoteAnimationAdapter;
    private final ArrayList<RemoteAnimationRecord> mPendingAnimations = new ArrayList<>();
    private final ArrayList<WallpaperAnimationAdapter> mPendingWallpaperAnimations =
            new ArrayList<>();
    private final Rect mTmpRect = new Rect();
    private final Handler mHandler;
    private final Runnable mTimeoutRunnable = () -> cancelAnimation("timeoutRunnable");

    private FinishedCallback mFinishedCallback;
    private boolean mCanceled;
    private boolean mLinkedToDeathOfRunner;

    RemoteAnimationController(WindowManagerService service,
            RemoteAnimationAdapter remoteAnimationAdapter, Handler handler) {
        mService = service;
        mRemoteAnimationAdapter = remoteAnimationAdapter;
        mHandler = handler;
    }

    /**
     * Creates an animation record for each individual {@link AppWindowToken}.
     *
     * @param appWindowToken The app to animate.
     * @param position The position app bounds, in screen coordinates.
     * @param stackBounds The stack bounds of the app relative to position.
     * @param startBounds The stack bounds before the transition, in screen coordinates
     * @return The record representing animation(s) to run on the app.
     */
    RemoteAnimationRecord createRemoteAnimationRecord(AppWindowToken appWindowToken,
            Point position, Rect stackBounds, Rect startBounds) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "createAnimationAdapter(): token=%s",
                appWindowToken);
        final RemoteAnimationRecord adapters =
                new RemoteAnimationRecord(appWindowToken, position, stackBounds, startBounds);
        mPendingAnimations.add(adapters);
        return adapters;
    }

    /**
     * Called when the transition is ready to be started, and all leashes have been set up.
     */
    void goodToGo() {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "goodToGo()");
        if (mPendingAnimations.isEmpty() || mCanceled) {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS,
                    "goodToGo(): Animation finished already, canceled=%s mPendingAnimations=%d",
                    mCanceled, mPendingAnimations.size());
            onAnimationFinished();
            return;
        }

        // Scale the timeout with the animator scale the controlling app is using.
        mHandler.postDelayed(mTimeoutRunnable,
                (long) (TIMEOUT_MS * mService.getCurrentAnimatorScale()));
        mFinishedCallback = new FinishedCallback(this);

        // Create the app targets
        final RemoteAnimationTarget[] appTargets = createAppAnimations();
        if (appTargets.length == 0) {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "goodToGo(): No apps to animate");
            onAnimationFinished();
            return;
        }

        // Create the remote wallpaper animation targets (if any)
        final RemoteAnimationTarget[] wallpaperTargets = createWallpaperAnimations();
        mService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            try {
                linkToDeathOfRunner();
                mRemoteAnimationAdapter.getRunner().onAnimationStart(appTargets, wallpaperTargets,
                        mFinishedCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to start remote animation", e);
                onAnimationFinished();
            }
            if (ProtoLogImpl.isEnabled(WM_DEBUG_REMOTE_ANIMATIONS)) {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "startAnimation(): Notify animation start:");
                writeStartDebugStatement();
            }
        });
        setRunningRemoteAnimation(true);
    }

    void cancelAnimation(String reason) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "cancelAnimation(): reason=%s", reason);
        synchronized (mService.getWindowManagerLock()) {
            if (mCanceled) {
                return;
            }
            mCanceled = true;
        }
        onAnimationFinished();
        invokeAnimationCancelled();
    }

    private void writeStartDebugStatement() {
        ProtoLog.i(WM_DEBUG_REMOTE_ANIMATIONS, "Starting remote animation");
        final StringWriter sw = new StringWriter();
        final FastPrintWriter pw = new FastPrintWriter(sw);
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            mPendingAnimations.get(i).mAdapter.dump(pw, "");
        }
        pw.close();
        ProtoLog.i(WM_DEBUG_REMOTE_ANIMATIONS, "%s", sw.toString());
    }

    private RemoteAnimationTarget[] createAppAnimations() {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "createAppAnimations()");
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final RemoteAnimationRecord wrappers = mPendingAnimations.get(i);
            final RemoteAnimationTarget target = wrappers.createRemoteAnimationTarget();
            if (target != null) {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\tAdd token=%s", wrappers.mAppWindowToken);
                targets.add(target);
            } else {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\tRemove token=%s",
                        wrappers.mAppWindowToken);

                // We can't really start an animation but we still need to make sure to finish the
                // pending animation that was started by SurfaceAnimator
                if (wrappers.mAdapter != null
                        && wrappers.mAdapter.mCapturedFinishCallback != null) {
                    wrappers.mAdapter.mCapturedFinishCallback
                            .onAnimationFinished(wrappers.mAdapter);
                }
                if (wrappers.mThumbnailAdapter != null
                        && wrappers.mThumbnailAdapter.mCapturedFinishCallback != null) {
                    wrappers.mThumbnailAdapter.mCapturedFinishCallback
                            .onAnimationFinished(wrappers.mThumbnailAdapter);
                }
                mPendingAnimations.remove(i);
            }
        }
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    private RemoteAnimationTarget[] createWallpaperAnimations() {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "createWallpaperAnimations()");
        return WallpaperAnimationAdapter.startWallpaperAnimations(mService,
                mRemoteAnimationAdapter.getDuration(),
                mRemoteAnimationAdapter.getStatusBarTransitionDelay(),
                adapter -> {
                    synchronized (mService.mGlobalLock) {
                        // If the wallpaper animation is canceled, continue with the app animation
                        mPendingWallpaperAnimations.remove(adapter);
                    }
                }, mPendingWallpaperAnimations);
    }

    private void onAnimationFinished() {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "onAnimationFinished(): mPendingAnimations=%d",
                mPendingAnimations.size());
        mHandler.removeCallbacks(mTimeoutRunnable);
        synchronized (mService.mGlobalLock) {
            unlinkToDeathOfRunner();
            releaseFinishedCallback();
            mService.openSurfaceTransaction();
            try {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS,
                        "onAnimationFinished(): Notify animation finished:");
                for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                    final RemoteAnimationRecord adapters = mPendingAnimations.get(i);
                    if (adapters.mAdapter != null) {
                        adapters.mAdapter.mCapturedFinishCallback
                                .onAnimationFinished(adapters.mAdapter);
                    }
                    if (adapters.mThumbnailAdapter != null) {
                        adapters.mThumbnailAdapter.mCapturedFinishCallback
                                .onAnimationFinished(adapters.mThumbnailAdapter);
                    }
                    mPendingAnimations.remove(i);
                    ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\tapp=%s", adapters.mAppWindowToken);
                }

                for (int i = mPendingWallpaperAnimations.size() - 1; i >= 0; i--) {
                    final WallpaperAnimationAdapter adapter = mPendingWallpaperAnimations.get(i);
                    adapter.getLeashFinishedCallback().onAnimationFinished(adapter);
                    mPendingWallpaperAnimations.remove(i);
                    ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\twallpaper=%s", adapter.getToken());
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to finish remote animation", e);
                throw e;
            } finally {
                mService.closeSurfaceTransaction("RemoteAnimationController#finished");
            }
        }
        setRunningRemoteAnimation(false);
        ProtoLog.i(WM_DEBUG_REMOTE_ANIMATIONS, "Finishing remote animation");
    }

    private void invokeAnimationCancelled() {
        try {
            mRemoteAnimationAdapter.getRunner().onAnimationCancelled();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to notify cancel", e);
        }
    }

    private void releaseFinishedCallback() {
        if (mFinishedCallback != null) {
            mFinishedCallback.release();
            mFinishedCallback = null;
        }
    }

    private void setRunningRemoteAnimation(boolean running) {
        final int pid = mRemoteAnimationAdapter.getCallingPid();
        final int uid = mRemoteAnimationAdapter.getCallingUid();
        if (pid == 0) {
            throw new RuntimeException("Calling pid of remote animation was null");
        }
        final WindowProcessController wpc = mService.mAtmService.getProcessController(pid, uid);
        if (wpc == null) {
            Slog.w(TAG, "Unable to find process with pid=" + pid + " uid=" + uid);
            return;
        }
        wpc.setRunningRemoteAnimation(running);
    }

    private void linkToDeathOfRunner() throws RemoteException {
        if (!mLinkedToDeathOfRunner) {
            mRemoteAnimationAdapter.getRunner().asBinder().linkToDeath(this, 0);
            mLinkedToDeathOfRunner = true;
        }
    }

    private void unlinkToDeathOfRunner() {
        if (mLinkedToDeathOfRunner) {
            mRemoteAnimationAdapter.getRunner().asBinder().unlinkToDeath(this, 0);
            mLinkedToDeathOfRunner = false;
        }
    }

    @Override
    public void binderDied() {
        cancelAnimation("binderDied");
    }

    private static final class FinishedCallback extends IRemoteAnimationFinishedCallback.Stub {

        RemoteAnimationController mOuter;

        FinishedCallback(RemoteAnimationController outer) {
            mOuter = outer;
        }

        @Override
        public void onAnimationFinished() throws RemoteException {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "app-onAnimationFinished(): mOuter=%s", mOuter);
            final long token = Binder.clearCallingIdentity();
            try {
                if (mOuter != null) {
                    mOuter.onAnimationFinished();

                    // In case the client holds on to the finish callback, make sure we don't leak
                    // RemoteAnimationController which in turn would leak the runner on the client.
                    mOuter = null;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Marks this callback as not be used anymore by releasing the reference to the outer class
         * to prevent memory leak.
         */
        void release() {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "app-release(): mOuter=%s", mOuter);
            mOuter = null;
        }
    };

    /**
     * Contains information about a remote-animation for one AppWindowToken. This keeps track of,
     * potentially, multiple animating surfaces (AdapterWrappers) associated with one
     * Window/Transition. For example, a change transition has an adapter controller for the
     * main window and an adapter controlling the start-state snapshot.
     * <p>
     * This can be thought of as a bridge between the information that the remote animator sees (via
     * {@link RemoteAnimationTarget}) and what the server sees (the
     * {@link RemoteAnimationAdapterWrapper}(s) interfacing with the moving surfaces).
     */
    public class RemoteAnimationRecord {
        RemoteAnimationAdapterWrapper mAdapter;
        RemoteAnimationAdapterWrapper mThumbnailAdapter = null;
        RemoteAnimationTarget mTarget;
        final AppWindowToken mAppWindowToken;
        final Rect mStartBounds;

        RemoteAnimationRecord(AppWindowToken appWindowToken, Point endPos, Rect endBounds,
                Rect startBounds) {
            mAppWindowToken = appWindowToken;
            mAdapter = new RemoteAnimationAdapterWrapper(this, endPos, endBounds);
            if (startBounds != null) {
                mStartBounds = new Rect(startBounds);
                mTmpRect.set(startBounds);
                mTmpRect.offsetTo(0, 0);
                if (mRemoteAnimationAdapter.getChangeNeedsSnapshot()) {
                    mThumbnailAdapter =
                            new RemoteAnimationAdapterWrapper(this, new Point(0, 0), mTmpRect);
                }
            } else {
                mStartBounds = null;
            }
        }

        RemoteAnimationTarget createRemoteAnimationTarget() {
            final Task task = mAppWindowToken.getTask();
            final WindowState mainWindow = mAppWindowToken.findMainWindow();
            if (task == null || mainWindow == null || mAdapter == null
                    || mAdapter.mCapturedFinishCallback == null
                    || mAdapter.mCapturedLeash == null) {
                return null;
            }
            final Rect insets = new Rect();
            mainWindow.getContentInsets(insets);
            InsetUtils.addInsets(insets, mAppWindowToken.getLetterboxInsets());
            mTarget = new RemoteAnimationTarget(task.mTaskId, getMode(),
                    mAdapter.mCapturedLeash, !mAppWindowToken.fillsParent(),
                    mainWindow.mWinAnimator.mLastClipRect, insets,
                    mAppWindowToken.getPrefixOrderIndex(), mAdapter.mPosition,
                    mAdapter.mStackBounds, task.getWindowConfiguration(), false /*isNotInRecents*/,
                    mThumbnailAdapter != null ? mThumbnailAdapter.mCapturedLeash : null,
                    mStartBounds);
            return mTarget;
        }

        private int getMode() {
            final DisplayContent dc = mAppWindowToken.getDisplayContent();
            if (dc.mOpeningApps.contains(mAppWindowToken)) {
                return RemoteAnimationTarget.MODE_OPENING;
            } else if (dc.mChangingApps.contains(mAppWindowToken)) {
                return RemoteAnimationTarget.MODE_CHANGING;
            } else {
                return RemoteAnimationTarget.MODE_CLOSING;
            }
        }
    }

    private class RemoteAnimationAdapterWrapper implements AnimationAdapter {
        private final RemoteAnimationRecord mRecord;
        SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;
        private final Point mPosition = new Point();
        private final Rect mStackBounds = new Rect();

        RemoteAnimationAdapterWrapper(RemoteAnimationRecord record, Point position,
                Rect stackBounds) {
            mRecord = record;
            mPosition.set(position.x, position.y);
            mStackBounds.set(stackBounds);
        }

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                OnAnimationFinishedCallback finishCallback) {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "startAnimation");

            // Restore z-layering, position and stack crop until client has a chance to modify it.
            t.setLayer(animationLeash, mRecord.mAppWindowToken.getPrefixOrderIndex());
            if (mRecord.mStartBounds != null) {
                t.setPosition(animationLeash, mRecord.mStartBounds.left, mRecord.mStartBounds.top);
                t.setWindowCrop(animationLeash, mRecord.mStartBounds.width(),
                        mRecord.mStartBounds.height());
            } else {
                t.setPosition(animationLeash, mPosition.x, mPosition.y);
                t.setWindowCrop(animationLeash, mStackBounds.width(), mStackBounds.height());
            }
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            if (mRecord.mAdapter == this) {
                mRecord.mAdapter = null;
            } else {
                mRecord.mThumbnailAdapter = null;
            }
            if (mRecord.mAdapter == null && mRecord.mThumbnailAdapter == null) {
                mPendingAnimations.remove(mRecord);
            }
            if (mPendingAnimations.isEmpty()) {
                cancelAnimation("allAppAnimationsCanceled");
            }
        }

        @Override
        public long getDurationHint() {
            return mRemoteAnimationAdapter.getDuration();
        }

        @Override
        public long getStatusBarTransitionsStartTime() {
            return SystemClock.uptimeMillis()
                    + mRemoteAnimationAdapter.getStatusBarTransitionDelay();
        }

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("token="); pw.println(mRecord.mAppWindowToken);
            if (mRecord.mTarget != null) {
                pw.print(prefix); pw.println("Target:");
                mRecord.mTarget.dump(pw, prefix + "  ");
            } else {
                pw.print(prefix); pw.println("Target: null");
            }
        }

        @Override
        public void writeToProto(ProtoOutputStream proto) {
            final long token = proto.start(REMOTE);
            if (mRecord.mTarget != null) {
                mRecord.mTarget.writeToProto(proto, TARGET);
            }
            proto.end(token);
        }
    }
}
