package com.codex.multivolume;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Small, independent state store.  It lives in the module process, not in SystemUI or MiSound,
 * so a SystemUI crash cannot lose the crash-loop marker.
 */
public final class SafeStateProvider extends ContentProvider {
    private static final String LOG_TAG = "HyperOsMultiVolume";
    public static final String AUTHORITY = "com.codex.multivolume.safety";
    public static final String METHOD_GET_STATE = "get_state";
    public static final String METHOD_BEGIN_SESSION = "begin_session";
    public static final String METHOD_MARK_STABLE = "mark_stable";
    public static final String METHOD_SET_ENABLED = "set_enabled";
    public static final String METHOD_CLEAR_SAFE_MODE = "clear_safe_mode";
    public static final String METHOD_SET_PLUGIN_READY = "set_plugin_ready";
    public static final String METHOD_SET_MISOUND_READY = "set_misound_ready";
    public static final String METHOD_SET_HAS_ACTIVE_PLAYERS = "set_has_active_players";
    public static final String METHOD_SET_PLAYER_VOLUME_VISIBLE = "set_player_volume_visible";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_SAFE_MODE = "safe_mode";
    public static final String KEY_ACTIVE = "active";
    public static final String KEY_LAST_ACTIVE = "last_active";
    public static final String KEY_PLUGIN_READY = "plugin_ready";
    public static final String KEY_MISOUND_READY = "misound_ready";
    public static final String KEY_HAS_ACTIVE_PLAYERS = "has_active_players";
    public static final String KEY_PLAYER_VOLUME_VISIBLE = "player_volume_visible";
    private static final String PREFS = "module_state";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_SAFE_MODE = "safe_mode";
    private static final String PREF_ACTIVE = "active";
    private static final String PREF_LAST_ACTIVE = "last_active";
    private static final String PREF_PLUGIN_READY = "plugin_ready";
    private static final String PREF_MISOUND_READY = "misound_ready";
    private static final String PREF_HAS_ACTIVE_PLAYERS = "has_active_players";
    private static final String PREF_PLAYER_VOLUME_VISIBLE = "player_volume_visible";
    private static final String PREF_VERSION_CODE = "version_code";
    private static final String PREF_BOOT_COUNT = "boot_count";
    private static final long CRASH_WINDOW_MS = 60_000L;

    private final Object lock = new Object();
    private SharedPreferences preferences;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        try {
            Context storageContext = context.createDeviceProtectedStorageContext();
            migratePreferencesIfUnlocked(context, storageContext);
            preferences = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            resetTransientStateForBoot(context);
            Log.i(LOG_TAG, "safety provider created in device-protected storage");
            return true;
        } catch (Throwable failure) {
            preferences = null;
            Log.e(LOG_TAG, "safety provider initialization failed", failure);
            return false;
        }
    }

    private void migratePreferencesIfUnlocked(Context credentialContext, Context storageContext) {
        try {
            UserManager userManager = credentialContext.getSystemService(UserManager.class);
            if (userManager != null && userManager.isUserUnlocked()
                    && storageContext.moveSharedPreferencesFrom(credentialContext, PREFS)) {
                Log.i(LOG_TAG, "migrated safety state to device-protected storage");
            }
        } catch (Throwable failure) {
            Log.w(LOG_TAG, "failed to migrate safety state", failure);
        }
    }

    private void resetTransientStateForBoot(Context context) {
        int bootCount = Settings.Global.getInt(
                context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        int previousBootCount = preferences.getInt(PREF_BOOT_COUNT, Integer.MIN_VALUE);
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(PREF_PLUGIN_READY, false)
                .putBoolean(PREF_PLAYER_VOLUME_VISIBLE, false);
        if (bootCount >= 0 && bootCount != previousBootCount) {
            editor.putInt(PREF_BOOT_COUNT, bootCount)
                    .putBoolean(PREF_ACTIVE, false)
                    .putBoolean(PREF_MISOUND_READY, false)
                    .putBoolean(PREF_HAS_ACTIVE_PLAYERS, false);
            Log.i(LOG_TAG, "cleared transient safety state for boot " + bootCount);
        }
        editor.apply();
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (preferences == null || method == null) {
            Log.w(LOG_TAG, "safety provider call rejected: initialized="
                    + (preferences != null) + ", methodNull=" + (method == null));
            return null;
        }
        if (!METHOD_GET_STATE.equals(method)) {
            debug("safety provider call: " + method);
        }
        synchronized (lock) {
            switch (method) {
                case METHOD_GET_STATE:
                    return stateLocked();
                case METHOD_BEGIN_SESSION:
                    return beginSessionLocked();
                case METHOD_MARK_STABLE:
                    preferences.edit().putBoolean(PREF_ACTIVE, false).apply();
                    Log.i(LOG_TAG, "safety session marked stable");
                    return stateLocked();
                case METHOD_SET_ENABLED:
                    boolean enabled = extras != null && extras.getBoolean(KEY_ENABLED, false);
                    preferences.edit().putBoolean(PREF_ENABLED, enabled)
                            .putBoolean(PREF_ACTIVE, false)
                            .putBoolean(PREF_PLUGIN_READY, false)
                            .putBoolean(PREF_HAS_ACTIVE_PLAYERS, false)
                            .putBoolean(PREF_PLAYER_VOLUME_VISIBLE, false)
                            .putBoolean(PREF_MISOUND_READY, enabled
                                    && preferences.getBoolean(PREF_MISOUND_READY, false)).apply();
                    Log.i(LOG_TAG, "module enabled changed: " + enabled);
                    notifyStateChanged();
                    return stateLocked();
                case METHOD_CLEAR_SAFE_MODE:
                    preferences.edit().putBoolean(PREF_SAFE_MODE, false)
                            .putBoolean(PREF_ACTIVE, false)
                            .putBoolean(PREF_PLUGIN_READY, false)
                            .putBoolean(PREF_HAS_ACTIVE_PLAYERS, false)
                            .putBoolean(PREF_PLAYER_VOLUME_VISIBLE, false).apply();
                    Log.i(LOG_TAG, "safe mode cleared");
                    notifyStateChanged();
                    return stateLocked();
                case METHOD_SET_PLUGIN_READY:
                    boolean ready = extras != null && extras.getBoolean(KEY_PLUGIN_READY, false);
                    boolean previousReady = preferences.getBoolean(PREF_PLUGIN_READY, false);
                    if (previousReady != ready) {
                        preferences.edit().putBoolean(PREF_PLUGIN_READY, ready).apply();
                        debug("plugin ready changed: " + ready);
                        notifyStateChanged();
                    }
                    return stateLocked();
                case METHOD_SET_MISOUND_READY:
                    boolean misoundReady = extras != null
                            && extras.getBoolean(KEY_MISOUND_READY, false);
                    boolean previousMisoundReady = preferences.getBoolean(PREF_MISOUND_READY, false);
                    boolean hadActivePlayers = preferences.getBoolean(
                            PREF_HAS_ACTIVE_PLAYERS, false);
                    if (previousMisoundReady != misoundReady
                            || (!misoundReady && hadActivePlayers)) {
                        SharedPreferences.Editor readyEditor = preferences.edit()
                                .putBoolean(PREF_MISOUND_READY, misoundReady);
                        if (!misoundReady) {
                            readyEditor.putBoolean(PREF_HAS_ACTIVE_PLAYERS, false);
                        }
                        readyEditor.apply();
                        debug("MiSound ready changed: " + misoundReady);
                        notifyStateChanged();
                    }
                    return stateLocked();
                case METHOD_SET_HAS_ACTIVE_PLAYERS:
                    boolean hasActivePlayers = extras != null
                            && extras.getBoolean(KEY_HAS_ACTIVE_PLAYERS, false);
                    boolean previousHasActivePlayers = preferences.getBoolean(
                            PREF_HAS_ACTIVE_PLAYERS, false);
                    if (previousHasActivePlayers != hasActivePlayers) {
                        preferences.edit().putBoolean(
                                PREF_HAS_ACTIVE_PLAYERS, hasActivePlayers).apply();
                        debug("active player state changed: " + hasActivePlayers);
                        notifyStateChanged();
                    }
                    return stateLocked();
                case METHOD_SET_PLAYER_VOLUME_VISIBLE:
                    boolean playerVolumeVisible = extras != null
                            && extras.getBoolean(KEY_PLAYER_VOLUME_VISIBLE, false);
                    boolean previousPlayerVolumeVisible = preferences.getBoolean(
                            PREF_PLAYER_VOLUME_VISIBLE, false);
                    if (previousPlayerVolumeVisible != playerVolumeVisible) {
                        preferences.edit().putBoolean(
                                PREF_PLAYER_VOLUME_VISIBLE, playerVolumeVisible).apply();
                        debug("player-volume overlay visibility changed: "
                                + playerVolumeVisible);
                        notifyStateChanged();
                    }
                    return stateLocked();
                default:
                    return null;
            }
        }
    }

    private Bundle beginSessionLocked() {
        long now = System.currentTimeMillis();
        long currentVersion = currentVersionCode();
        long previousVersion = preferences.getLong(PREF_VERSION_CODE, -1L);
        boolean versionChanged = currentVersion >= 0L && previousVersion != currentVersion;
        boolean active = preferences.getBoolean(PREF_ACTIVE, false);
        long previous = preferences.getLong(PREF_LAST_ACTIVE, 0L);
        boolean crashLoop = !versionChanged
                && active
                && previous > 0L
                && now - previous <= CRASH_WINDOW_MS;
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(PREF_ACTIVE, true)
                .putLong(PREF_LAST_ACTIVE, now)
                .putBoolean(PREF_PLUGIN_READY, false)
                .putBoolean(PREF_PLAYER_VOLUME_VISIBLE, false);
        if (currentVersion >= 0L) {
            editor.putLong(PREF_VERSION_CODE, currentVersion);
        }
        if (versionChanged) {
            editor.putBoolean(PREF_SAFE_MODE, false)
                    .putBoolean(PREF_HAS_ACTIVE_PLAYERS, false);
        }
        if (crashLoop) {
            editor.putBoolean(PREF_SAFE_MODE, true);
        }
        editor.apply();
        Log.i(LOG_TAG, "safety session started: crashLoop=" + crashLoop
                + ", previousActive=" + active
                + ", versionChanged=" + versionChanged
                + ", previousVersion=" + previousVersion
                + ", currentVersion=" + currentVersion);
        notifyStateChanged();
        return stateLocked();
    }

    private long currentVersionCode() {
        Context context = getContext();
        if (context == null) {
            return -1L;
        }
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (Throwable failure) {
            Log.w(LOG_TAG, "failed to read module version code", failure);
            return -1L;
        }
    }

    private Bundle stateLocked() {
        Bundle result = new Bundle();
        result.putBoolean(KEY_ENABLED, preferences.getBoolean(PREF_ENABLED, true));
        result.putBoolean(KEY_SAFE_MODE, preferences.getBoolean(PREF_SAFE_MODE, false));
        result.putBoolean(KEY_ACTIVE, preferences.getBoolean(PREF_ACTIVE, false));
        result.putLong(KEY_LAST_ACTIVE, preferences.getLong(PREF_LAST_ACTIVE, 0L));
        result.putBoolean(KEY_PLUGIN_READY, preferences.getBoolean(PREF_PLUGIN_READY, false));
        result.putBoolean(KEY_MISOUND_READY, preferences.getBoolean(PREF_MISOUND_READY, false));
        result.putBoolean(KEY_HAS_ACTIVE_PLAYERS,
                preferences.getBoolean(PREF_HAS_ACTIVE_PLAYERS, false));
        result.putBoolean(KEY_PLAYER_VOLUME_VISIBLE,
                preferences.getBoolean(PREF_PLAYER_VOLUME_VISIBLE, false));
        return result;
    }

    private void notifyStateChanged() {
        Context context = getContext();
        if (context != null) {
            try {
                context.getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY), null);
            } catch (Throwable ignored) {
                Log.w(LOG_TAG, "failed to notify safety state observers", ignored);
            }
        }
    }

    private static void debug(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, message);
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
