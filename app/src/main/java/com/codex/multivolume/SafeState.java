package com.codex.multivolume;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Binder-safe access to the module process' persistent safety state. */
public final class SafeState {
    private static final String LOG_TAG = "HyperOsMultiVolume";
    private static final Uri URI = Uri.parse("content://" + SafeStateProvider.AUTHORITY);

    private SafeState() {
    }

    public static Bundle get(Context context) {
        return call(context, SafeStateProvider.METHOD_GET_STATE, null);
    }

    public static Bundle beginSession(Context context) {
        return call(context, SafeStateProvider.METHOD_BEGIN_SESSION, null);
    }

    public static void markStable(Context context) {
        call(context, SafeStateProvider.METHOD_MARK_STABLE, null);
    }

    public static Bundle setEnabled(Context context, boolean enabled) {
        Bundle args = new Bundle();
        args.putBoolean(SafeStateProvider.KEY_ENABLED, enabled);
        return call(context, SafeStateProvider.METHOD_SET_ENABLED, args);
    }

    public static Bundle clearSafeMode(Context context) {
        return call(context, SafeStateProvider.METHOD_CLEAR_SAFE_MODE, null);
    }

    public static Bundle setPluginReady(Context context, boolean ready) {
        Bundle args = new Bundle();
        args.putBoolean(SafeStateProvider.KEY_PLUGIN_READY, ready);
        return call(context, SafeStateProvider.METHOD_SET_PLUGIN_READY, args);
    }

    public static Bundle setMiSoundReady(Context context, boolean ready) {
        Bundle args = new Bundle();
        args.putBoolean(SafeStateProvider.KEY_MISOUND_READY, ready);
        return call(context, SafeStateProvider.METHOD_SET_MISOUND_READY, args);
    }

    public static Bundle setHasActivePlayers(Context context, boolean hasActivePlayers) {
        Bundle args = new Bundle();
        args.putBoolean(SafeStateProvider.KEY_HAS_ACTIVE_PLAYERS, hasActivePlayers);
        return call(context, SafeStateProvider.METHOD_SET_HAS_ACTIVE_PLAYERS, args);
    }

    public static Bundle setPlayerVolumeVisible(Context context, boolean visible) {
        Bundle args = new Bundle();
        args.putBoolean(SafeStateProvider.KEY_PLAYER_VOLUME_VISIBLE, visible);
        return call(context, SafeStateProvider.METHOD_SET_PLAYER_VOLUME_VISIBLE, args);
    }

    private static Bundle call(Context context, String method, Bundle args) {
        if (context == null) {
            Log.w(LOG_TAG, "safety state call skipped: context is null, method=" + method);
            return null;
        }
        try {
            Bundle result = context.getContentResolver().call(URI, method, null, args);
            if (result == null) {
                Log.w(LOG_TAG, "safety state call returned null: " + method);
            }
            return result;
        } catch (Throwable failure) {
            Log.w(LOG_TAG, "safety state call failed: " + method, failure);
            return null;
        }
    }

    public static boolean canInject(Bundle state) {
        return state != null
                && state.getBoolean(SafeStateProvider.KEY_ENABLED, false)
                && !state.getBoolean(SafeStateProvider.KEY_SAFE_MODE, true);
    }
}
