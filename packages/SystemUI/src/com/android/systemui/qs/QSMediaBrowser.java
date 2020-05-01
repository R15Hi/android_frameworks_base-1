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

package com.android.systemui.qs;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.List;

/**
 * Media browser for managing resumption in QS media controls
 */
public class QSMediaBrowser {

    /** Maximum number of controls to show on boot */
    public static final int MAX_RESUMPTION_CONTROLS = 5;

    /** Delimiter for saved component names */
    public static final String DELIMITER = ":";

    private static final String TAG = "QSMediaBrowser";
    private final Context mContext;
    private final Callback mCallback;
    private MediaBrowser mMediaBrowser;
    private ComponentName mComponentName;

    /**
     * Initialize a new media browser
     * @param context the context
     * @param callback used to report media items found
     * @param componentName Component name of the MediaBrowserService this browser will connect to
     */
    public QSMediaBrowser(Context context, Callback callback, ComponentName componentName) {
        mContext = context;
        mCallback = callback;
        mComponentName = componentName;
    }

    /**
     * Connects to the MediaBrowserService and looks for valid media. If a media item is returned
     * by the service, QSMediaBrowser.Callback#addTrack will be called with its MediaDescription.
     * QSMediaBrowser.Callback#onConnected and QSMediaBrowser.Callback#onError will also be called
     * when the initial connection is successful, or an error occurs. Note that it is possible for
     * the service to connect but for no playable tracks to be found later.
     * QSMediaBrowser#disconnect will be called automatically with this function.
     */
    public void findRecentMedia() {
        Log.d(TAG, "Connecting to " + mComponentName);
        disconnect();
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = new MediaBrowser(mContext,
                mComponentName,
                mConnectionCallback,
                rootHints);
        mMediaBrowser.connect();
    }

    private final MediaBrowser.SubscriptionCallback mSubscriptionCallback =
            new MediaBrowser.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(String parentId,
                List<MediaBrowser.MediaItem> children) {
            if (children.size() == 0) {
                Log.e(TAG, "No children found for " + mComponentName);
                return;
            }
            // We ask apps to return a playable item as the first child when sending
            // a request with EXTRA_RECENT; if they don't, no resume controls
            MediaBrowser.MediaItem child = children.get(0);
            MediaDescription desc = child.getDescription();
            if (child.isPlayable()) {
                mCallback.addTrack(desc, mMediaBrowser.getServiceComponent(), QSMediaBrowser.this);
            } else {
                Log.e(TAG, "Child found but not playable for " + mComponentName);
            }
            disconnect();
        }

        @Override
        public void onError(String parentId) {
            Log.e(TAG, "Subscribe error for " + mComponentName + ": " + parentId);
            mCallback.onError();
            disconnect();
        }

        @Override
        public void onError(String parentId, Bundle options) {
            Log.e(TAG, "Subscribe error for " + mComponentName + ": " + parentId
                    + ", options: " + options);
            mCallback.onError();
            disconnect();
        }
    };

    private final MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
        /**
         * Invoked after {@link MediaBrowser#connect()} when the request has successfully completed.
         * For resumption controls, apps are expected to return a playable media item as the first
         * child. If there are no children or it isn't playable it will be ignored.
         */
        @Override
        public void onConnected() {
            if (mMediaBrowser.isConnected()) {
                mCallback.onConnected();
                Log.d(TAG, "Service connected for " + mComponentName);
                String root = mMediaBrowser.getRoot();
                mMediaBrowser.subscribe(root, mSubscriptionCallback);
            }
        }

        /**
         * Invoked when the client is disconnected from the media browser.
         */
        @Override
        public void onConnectionSuspended() {
            Log.d(TAG, "Connection suspended for " + mComponentName);
            mCallback.onError();
            disconnect();
        }

        /**
         * Invoked when the connection to the media browser failed.
         */
        @Override
        public void onConnectionFailed() {
            Log.e(TAG, "Connection failed for " + mComponentName);
            mCallback.onError();
            disconnect();
        }
    };

    /**
     * Disconnect the media browser. This should be called after restart or testConnection have
     * completed to close the connection.
     */
    public void disconnect() {
        if (mMediaBrowser != null) {
            mMediaBrowser.disconnect();
        }
        mMediaBrowser = null;
    }

    /**
     * Connects to the MediaBrowserService and starts playback. QSMediaBrowser.Callback#onError or
     * QSMediaBrowser.Callback#onConnected will be called depending on whether it was successful.
     * QSMediaBrowser#disconnect should be called after this to ensure the connection is closed.
     */
    public void restart() {
        disconnect();
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = new MediaBrowser(mContext, mComponentName,
                new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.d(TAG, "Connected for restart " + mMediaBrowser.isConnected());
                        MediaSession.Token token = mMediaBrowser.getSessionToken();
                        MediaController controller = new MediaController(mContext, token);
                        controller.getTransportControls();
                        controller.getTransportControls().prepare();
                        controller.getTransportControls().play();
                        mCallback.onConnected();
                    }

                    @Override
                    public void onConnectionFailed() {
                        mCallback.onError();
                    }

                    @Override
                    public void onConnectionSuspended() {
                        mCallback.onError();
                    }
                }, rootHints);
        mMediaBrowser.connect();
    }

    /**
     * Get the media session token
     * @return the token, or null if the MediaBrowser is null or disconnected
     */
    public MediaSession.Token getToken() {
        if (mMediaBrowser == null || !mMediaBrowser.isConnected()) {
            return null;
        }
        return mMediaBrowser.getSessionToken();
    }

    /**
     * Get an intent to launch the app associated with this browser service
     * @return
     */
    public PendingIntent getAppIntent() {
        PackageManager pm = mContext.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(mComponentName.getPackageName());
        return PendingIntent.getActivity(mContext, 0, launchIntent, 0);
    }

    /**
     * Used to test if SystemUI is allowed to connect to the given component as a MediaBrowser.
     * QSMediaBrowser.Callback#onError or QSMediaBrowser.Callback#onConnected will be called
     * depending on whether it was successful.
     * QSMediaBrowser#disconnect should be called after this to ensure the connection is closed.
     */
    public void testConnection() {
        disconnect();
        final MediaBrowser.ConnectionCallback connectionCallback =
                new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.d(TAG, "connected");
                        if (mMediaBrowser.getRoot() == null) {
                            mCallback.onError();
                        } else {
                            mCallback.onConnected();
                        }
                    }

                    @Override
                    public void onConnectionSuspended() {
                        Log.d(TAG, "suspended");
                        mCallback.onError();
                    }

                    @Override
                    public void onConnectionFailed() {
                        Log.d(TAG, "failed");
                        mCallback.onError();
                    }
                };
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = new MediaBrowser(mContext,
                mComponentName,
                connectionCallback,
                rootHints);
        mMediaBrowser.connect();
    }

    /**
     * Interface to handle results from QSMediaBrowser
     */
    public static class Callback {
        /**
         * Called when the browser has successfully connected to the service
         */
        public void onConnected() {
        }

        /**
         * Called when the browser encountered an error connecting to the service
         */
        public void onError() {
        }

        /**
         * Called when the browser finds a suitable track to add to the media carousel
         * @param track media info for the item
         * @param component component of the MediaBrowserService which returned this
         * @param browser reference to the browser
         */
        public void addTrack(MediaDescription track, ComponentName component,
                QSMediaBrowser browser) {
        }
    }
}
