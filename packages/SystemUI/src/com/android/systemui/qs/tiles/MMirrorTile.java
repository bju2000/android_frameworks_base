/*
 * Copyright (C) 2015 Preetam D'Souza
 *
 * QuickSettings HDMI mirroring toggle.
 *
 */

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.Display;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

/** Quick settings tile: Mirror screen **/
public class MMirrorTile extends QSTileImpl<BooleanState> {
    private static final String TAG = "MMirrorTile";

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_mirroring);

    private final DisplayManager mDisplayManager;

    private final MDisplayListener mDisplayListener;
    private Set<Integer> mPresentationDisplays;

    private boolean mListening = false;

    @Inject
    public MMirrorTile(QSHost host) {
        super(host);

        mDisplayManager = (DisplayManager) host.getContext()
                .getSystemService(Context.DISPLAY_SERVICE);

        mDisplayListener = new MDisplayListener();
        mPresentationDisplays = new HashSet<Integer>();
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    public void handleClick() {
        if (mState.value) {
                mDisplayManager.disablePhoneMirroring();
        } else {
                mDisplayManager.enablePhoneMirroring();
        }
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean hasPresentationDisplay = !mPresentationDisplays.isEmpty();
        state.value = mDisplayManager.isPhoneMirroringEnabled();
        state.label = mContext.getString(R.string.quick_settings_mirroring_mode_label);
        state.icon = mIcon;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_qs_mirroring_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_qs_mirroring_changed_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_MMIRROR_TOGGLE;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_mirroring_mode_label);
    }

    @Override
    public void handleSetListening(boolean listening) {
        // defense against duplicate registers
        if (mListening == listening) {
            return;
        }

        if (listening) {
            // Log.d(TAG, "registering mDisplayListener");
            mDisplayListener.sync();
            mDisplayManager.registerDisplayListener(mDisplayListener, null);
        } else {
            // Log.d(TAG, "unregistering mDisplayListener");
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        mListening = listening;
    }

    private class MDisplayListener implements DisplayManager.DisplayListener {
        @Override
        public void onDisplayAdded(int displayId) {
            Display display = mDisplayManager.getDisplay(displayId);

            if (display.isPublicPresentation()) {
                if (mPresentationDisplays.isEmpty()) {
                    // the first presentation display was added
                    refreshState();
                }
                mPresentationDisplays.add(displayId);
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {

            if (mPresentationDisplays.remove(displayId) && mPresentationDisplays.isEmpty()) {

                refreshState();

            }
        }

        @Override
        public void onDisplayChanged(int displayId) { /* no-op */ }

        /**
         * We may miss a display event since listeners are unregistered
         * when the QS panel is hidden.
         *
         * Call this before registering to make sure the initial
         * state is up-to-date.
         */
        public void sync() {
            mPresentationDisplays.clear();
            Display[] displays = mDisplayManager
                    .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            for (Display display : displays) {
                if (display.isPublicPresentation()) {
                    mPresentationDisplays.add(display.getDisplayId());
                }
            }
        }
    }
}
