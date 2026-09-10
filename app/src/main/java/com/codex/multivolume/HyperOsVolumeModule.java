package com.codex.multivolume;

import android.app.Service;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * HyperOS 4 volume-panel entry point.
 *
 * The module deliberately hooks only the SystemUI volume plugin and MiSound's own UI service.
 * It does not change framework audio policy, Settings APK code, or the main SystemUI package.
 */
public final class HyperOsVolumeModule extends XposedModule {
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String MISOUND_PACKAGE = "com.miui.misound";
    private static final String RINGER_LAYOUT = "com.android.systemui.miui.volume.MiuiRingerModeLayout";
    private static final String SHOW_HIDE_ANIMATOR =
            "com.android.systemui.miui.volume.VolumeShowHideAnimator";
    private static final String PLUGIN_FACTORY =
            "com.android.systemui.shared.plugins.PluginInstance$PluginFactory";
    private static final String SOUND_SERVICE = "com.miui.misound.playervolume.VolumeUIService";
    private static final String SOUND_CONTROLLER = "com.miui.misound.playervolume.a";
    private static final String SHOW_PLAYER_VOLUME_ACTION =
            "com.codex.multivolume.action.SHOW_PLAYER_VOLUME";
    private static final String EXTRA_ANCHOR_X = "anchor_x";
    private static final String EXTRA_ANCHOR_Y = "anchor_y";
    private static final String SOUND_ASSIST_KEY = "sound_assist_key";
    private static final String LOG_TAG = "HyperOsMultiVolume";

    private final ProcessGuard guard = new ProcessGuard(this);
    private final Map<ClassLoader, Boolean> pluginHookLoaders =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, float[]> playerVolumeAnchors =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        try {
            guard.info("module loaded: process=" + param.getProcessName()
                    + ", api=" + getApiVersion()
                    + ", framework=" + getFrameworkName() + " " + getFrameworkVersion());
        } catch (Throwable failure) {
            guard.error("module metadata logging failed", failure);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        try {
            String packageName = param.getPackageName();
            guard.info("package loaded: " + packageName);
            if (SYSTEMUI_PACKAGE.equals(packageName)) {
                installSystemUiPluginLoaderHook(param.getDefaultClassLoader());
            } else if (PLUGIN_PACKAGE.equals(packageName)) {
                installPluginHooks(param.getDefaultClassLoader());
            } else if (MISOUND_PACKAGE.equals(packageName)) {
                installMiSoundHook(param.getDefaultClassLoader());
            } else {
                guard.debug("package ignored: " + packageName);
            }
        } catch (Throwable failure) {
            guard.error("package callback failed", failure);
            guard.fail(failure);
        }
    }

    /**
     * HyperOS loads miui.systemui.plugin through SystemUI's PluginFactory, so the plugin package
     * does not receive a separate LSPosed lifecycle callback. Hook the factory return value, then
     * install the actual volume hooks in the plugin's dedicated class loader.
     */
    private void installSystemUiPluginLoaderHook(ClassLoader loader) {
        try {
            Class<?> factoryClass = Class.forName(PLUGIN_FACTORY, false, loader);
            Method createClassLoader = factoryClass.getDeclaredMethod("createClassLoader");
            guard.info("SystemUI plugin factory found: " + createClassLoader);
            addHook(createClassLoader, "systemui.plugin.loader", chain -> {
                Object factory = chain.getThisObject();
                Object result = chain.proceed();
                if (!(result instanceof ClassLoader)) {
                    guard.warn("SystemUI plugin factory returned no class loader");
                    return result;
                }
                ClassLoader pluginLoader = (ClassLoader) result;
                String pluginPackage = pluginPackageName(factory, pluginLoader);
                if (!PLUGIN_PACKAGE.equals(pluginPackage)) {
                    guard.debug("skipping non-target plugin class loader: package="
                            + (pluginPackage == null ? "<unknown>" : pluginPackage)
                            + ", loader=" + pluginLoader);
                    return result;
                }
                guard.info("SystemUI created target plugin class loader: " + pluginLoader);
                installPluginHooks(pluginLoader);
                return result;
            });
        } catch (Throwable failure) {
            guard.error("SystemUI plugin factory hook installation failed", failure);
            guard.fail(failure);
        }
    }

    /**
     * PluginFactory creates a separate class loader for every SystemUI plugin (for example
     * MIUIAod and the volume plugin).  Only the latter contains MiuiRingerModeLayout.  Read the
     * factory's ApplicationInfo first; keep an APK-path fallback for minor class shape changes.
     */
    private String pluginPackageName(Object factory, ClassLoader loader) {
        if (factory != null) {
            Class<?> type = factory.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField("pluginAppInfo");
                    field.setAccessible(true);
                    Object value = field.get(factory);
                    if (value instanceof ApplicationInfo) {
                        String packageName = ((ApplicationInfo) value).packageName;
                        if (packageName != null && !packageName.isEmpty()) {
                            return packageName;
                        }
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                } catch (Throwable failure) {
                    guard.debug("plugin package reflection unavailable: " + failure);
                    break;
                }
            }
        }
        String loaderText = String.valueOf(loader);
        if (loaderText.contains("MIUISystemUIPlugin") || loaderText.contains(PLUGIN_PACKAGE)) {
            return PLUGIN_PACKAGE;
        }
        return null;
    }

    private void installPluginHooks(ClassLoader loader) {
        try {
            synchronized (pluginHookLoaders) {
                if (pluginHookLoaders.containsKey(loader)) {
                    guard.debug("plugin hooks already installed for class loader: " + loader);
                    return;
                }
                pluginHookLoaders.put(loader, Boolean.TRUE);
            }
            guard.info("installing SystemUI plugin hooks; loader=" + loader);
            Class<?> layoutClass = Class.forName(RINGER_LAYOUT, false, loader);
            Method finish = layoutClass.getDeclaredMethod("onFinishInflate");
            Method attach = layoutClass.getDeclaredMethod("onAttachedToWindow");
            Method detach = layoutClass.getDeclaredMethod("onDetachedFromWindow");
            Method updateExpanded = layoutClass.getDeclaredMethod("updateExpandedH", boolean.class);
            Class<?> showHideAnimatorClass = Class.forName(SHOW_HIDE_ANIMATOR, false, loader);
            Method initAnimator = showHideAnimatorClass.getDeclaredMethod(
                    "initView", View.class, View.class, View.class);
            Method showAnimator = null;
            for (Method candidate : showHideAnimatorClass.getDeclaredMethods()) {
                if ("show".equals(candidate.getName())
                        && candidate.getParameterCount() == 2) {
                    showAnimator = candidate;
                    break;
                }
            }
            if (showAnimator == null) {
                throw new NoSuchMethodException(SHOW_HIDE_ANIMATOR + ".show(2 args)");
            }
            guard.info("plugin target found: " + layoutClass.getName()
                    + ", finish=" + finish + ", attach=" + attach
                    + ", detach=" + detach + ", updateExpanded=" + updateExpanded);
            addHook(finish, "plugin.finish", chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                if (target instanceof ViewGroup) {
                    ViewGroup root = (ViewGroup) target;
                    guard.debug("plugin.finish callback: root=" + root.getClass().getName()
                            + ", children=" + root.getChildCount());
                    try {
                        UiInjector.sync(root, guard);
                    } catch (Throwable failure) {
                        guard.error("plugin.finish sync failed", failure);
                        SafeState.setPluginReady(root.getContext(), false);
                        guard.fail(failure);
                        UiInjector.removeInjected(root);
                    }
                }
                return result;
            });
            addHook(attach, "plugin.attach", chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                if (target instanceof ViewGroup) {
                    ViewGroup root = (ViewGroup) target;
                    guard.debug("plugin.attach callback: root=" + root.getClass().getName());
                    try {
                        UiInjector.sync(root, guard);
                        root.post(() -> UiInjector.refreshStyle(root, guard));
                    } catch (Throwable failure) {
                        guard.error("plugin.attach sync failed", failure);
                        SafeState.setPluginReady(root.getContext(), false);
                        guard.fail(failure);
                        UiInjector.removeInjected(root);
                    }
                }
                return result;
            });
            addHook(detach, "plugin.detach", chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                if (target instanceof ViewGroup) {
                    guard.debug("plugin.detach callback: root=" + target.getClass().getName());
                    UiInjector.detach((ViewGroup) target, guard);
                }
                return result;
            });
            addHook(updateExpanded, "plugin.expanded", chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                List<Object> args = chain.getArgs();
                if (target instanceof ViewGroup && !args.isEmpty()
                        && args.get(0) instanceof Boolean) {
                    ViewGroup root = (ViewGroup) target;
                    try {
                        UiInjector.updateExpanded(root, (Boolean) args.get(0), guard);
                    } catch (Throwable failure) {
                        guard.error("plugin.expanded style update failed", failure);
                        SafeState.setPluginReady(root.getContext(), false);
                        guard.fail(failure);
                        UiInjector.removeInjected(root);
                    }
                }
                return result;
            });
            addHook(initAnimator, "plugin.show_hide_animator", chain -> {
                Object result = chain.proceed();
                try {
                    List<Object> args = chain.getArgs();
                    View volumeView = !args.isEmpty() && args.get(0) instanceof View
                            ? (View) args.get(0) : null;
                    UiInjector.includeInShowHideAnimation(
                            chain.getThisObject(), volumeView, guard);
                } catch (Throwable failure) {
                    guard.error("optional injected-button animation setup failed", failure);
                }
                return result;
            });
            addHook(showAnimator, "plugin.show_hide_rebind", chain -> {
                try {
                    UiInjector.includeInShowHideAnimation(
                            chain.getThisObject(), null, guard);
                } catch (Throwable failure) {
                    guard.error("optional injected-button animation rebind failed", failure);
                }
                return chain.proceed();
            });
        } catch (Throwable failure) {
            // An unknown ROM layout is an unsupported target.  Leave the native panel untouched.
            guard.error("SystemUI plugin hook installation failed", failure);
            synchronized (pluginHookLoaders) {
                pluginHookLoaders.remove(loader);
            }
            guard.fail(failure);
        }
    }

    private void installMiSoundHook(ClassLoader loader) {
        try {
            guard.info("installing MiSound hooks; loader=" + loader);
            Class<?> controllerClass = Class.forName(SOUND_CONTROLLER, false, loader);
            Method getController = controllerClass.getDeclaredMethod("j", Context.class);
            Method startController = controllerClass.getDeclaredMethod("B");
            Method showExpanded = controllerClass.getDeclaredMethod("y");
            Method showFloat = controllerClass.getDeclaredMethod("z");
            Method dismissExpanded = controllerClass.getDeclaredMethod("p");
            Method animateDismissExpanded = controllerClass.getDeclaredMethod("g");
            Class<?> serviceClass = Class.forName(SOUND_SERVICE, false, loader);
            Method method = serviceClass.getDeclaredMethod("onStartCommand", Intent.class, int.class, int.class);
            Method destroy = serviceClass.getDeclaredMethod("onDestroy");
            guard.info("MiSound service target found: " + method);
            addHook(method, "misound.start", chain -> {
                Object target = chain.getThisObject();
                if (target instanceof Context) {
                    guard.rememberContext((Context) target);
                    SafeState.setMiSoundReady((Context) target, true);
                    guard.debug("MiSound service started; ready state recorded");
                }
                List<Object> args = chain.getArgs();
                Intent command = !args.isEmpty() && args.get(0) instanceof Intent
                        ? (Intent) args.get(0) : null;
                if (target instanceof Context && command != null
                        && SHOW_PLAYER_VOLUME_ACTION.equals(command.getAction())) {
                    guard.info("native player-volume request received");
                    if (!showNativePlayerVolume((Context) target, getController,
                            startController, showExpanded,
                            command.getFloatExtra(EXTRA_ANCHOR_X, Float.NaN),
                            command.getFloatExtra(EXTRA_ANCHOR_Y, Float.NaN))) {
                        SafeState.setMiSoundReady((Context) target, false);
                    }
                    return Service.START_STICKY;
                }
                return chain.proceed();
            });
            addHook(destroy, "misound.destroy", chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                if (target instanceof Context) {
                    SafeState.setMiSoundReady((Context) target, false);
                    SafeState.setPlayerVolumeVisible((Context) target, false);
                    guard.info("MiSound service destroyed; active player state cleared");
                }
                return result;
            });
            addHook(showExpanded, "misound.expanded.show", chain -> {
                Object result = chain.proceed();
                Context context = guard.lastContext();
                if (context != null) {
                    SafeState.setPlayerVolumeVisible(context, true);
                    guard.debug("MiSound player-volume overlay shown state recorded");
                }
                return result;
            });
            addHook(dismissExpanded, "misound.expanded.dismiss", chain -> {
                Object result = chain.proceed();
                playerVolumeAnchors.remove(chain.getThisObject());
                Context context = guard.lastContext();
                if (context != null) {
                    SafeState.setPlayerVolumeVisible(context, false);
                    guard.debug("MiSound player-volume overlay dismissed state recorded");
                }
                return result;
            });
            addHook(animateDismissExpanded, "misound.expanded.dismiss_animation", chain -> {
                Object result = chain.proceed();
                applyAnchoredPlayerVolumeExitAnimation(chain.getThisObject());
                return result;
            });

            // z() is the single method that adds the blue left-side WindowManager view.  Keeping
            // this guard as well as the service guard covers delayed/repeated show calls.
            guard.info("MiSound float target found: " + controllerClass.getName() + ".z()");
            addHook(showFloat, "misound.float", chain -> {
                Context context = guard.lastContext();
                boolean hasActivePlayers = controllerHasActivePlayers(chain.getThisObject());
                if (context != null) {
                    SafeState.setHasActivePlayers(context, hasActivePlayers);
                    guard.debug("MiSound filtered active-player state=" + hasActivePlayers);
                }
                if (context != null && shouldSuppressNativeFloat(context, null)) {
                    guard.info("native MiSound float suppressed for z()");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
            // MiSound is optional on some regional builds.  In that case the native entry remains.
            guard.error("MiSound hook installation failed; native entry remains", ignored);
        }
    }

    private boolean showNativePlayerVolume(Context context, Method getController,
            Method startController, Method showExpanded, float anchorX, float anchorY) {
        try {
            Object controller = getController.invoke(null, context.getApplicationContext());
            if (controller == null) {
                throw new IllegalStateException("MiSound controller is null");
            }
            // B() rebuilds the active-player columns and adapter.  Its z() call is intercepted by
            // misound.float, so no left-side button is added; y() then opens the same native panel
            // that the original blue button opens.
            startController.invoke(controller);
            if (!controllerHasActivePlayers(controller)) {
                SafeState.setHasActivePlayers(context, false);
                SafeState.setPlayerVolumeVisible(context, false);
                guard.info("native player-volume request ignored: MiSound active-player list empty");
                return true;
            }
            if (Float.isFinite(anchorX) && Float.isFinite(anchorY)) {
                playerVolumeAnchors.put(controller, new float[]{anchorX, anchorY});
            } else {
                playerVolumeAnchors.remove(controller);
            }
            showExpanded.invoke(controller);
            applyAnchoredPlayerVolumeAnimation(controller, anchorX, anchorY);
            guard.info("native MiSound player-volume overlay shown");
            return true;
        } catch (Throwable failure) {
            SafeState.setPlayerVolumeVisible(context, false);
            guard.error("native MiSound player-volume request failed", failure);
            guard.fail(failure);
            return false;
        }
    }

    private boolean controllerHasActivePlayers(Object controller) {
        try {
            for (Field field : controller.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(controller);
                if (!(value instanceof List<?>)) {
                    continue;
                }
                for (Object item : (List<?>) value) {
                    if (item instanceof AudioPlaybackConfiguration) {
                        return true;
                    }
                }
            }
        } catch (Throwable failure) {
            guard.error("MiSound active-player list check failed; request ignored", failure);
        }
        return false;
    }

    private void applyAnchoredPlayerVolumeAnimation(Object controller, float anchorX,
            float anchorY) {
        if (!Float.isFinite(anchorX) || !Float.isFinite(anchorY)) {
            guard.debug("player-volume anchor unavailable; keeping native animation");
            return;
        }
        try {
            Field pagerField = null;
            for (Field candidate : controller.getClass().getDeclaredFields()) {
                if ("androidx.viewpager2.widget.ViewPager2".equals(
                        candidate.getType().getName())) {
                    pagerField = candidate;
                    break;
                }
            }
            if (pagerField == null) {
                guard.warn("player-volume ViewPager2 field unavailable; keeping native animation");
                return;
            }
            pagerField.setAccessible(true);
            Object value = pagerField.get(controller);
            if (!(value instanceof View)) {
                guard.warn("player-volume animation target unavailable; keeping native animation");
                return;
            }
            View target = (View) value;
            String fieldName = pagerField.getName();
            if (target.isAttachedToWindow() && target.isLaidOut()) {
                startAnchoredScale(target, anchorX, anchorY, fieldName, false);
            } else {
                target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View view, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        view.removeOnLayoutChangeListener(this);
                        startAnchoredScale(view, anchorX, anchorY, fieldName, false);
                    }
                });
            }
        } catch (Throwable failure) {
            guard.error("anchored player-volume animation unavailable; keeping native animation",
                    failure);
        }
    }

    private void applyAnchoredPlayerVolumeExitAnimation(Object controller) {
        float[] anchor = playerVolumeAnchors.get(controller);
        if (anchor == null || anchor.length != 2) {
            guard.debug("player-volume exit anchor unavailable; keeping native animation");
            return;
        }
        try {
            for (Field candidate : controller.getClass().getDeclaredFields()) {
                if (!"androidx.viewpager2.widget.ViewPager2".equals(
                        candidate.getType().getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Object value = candidate.get(controller);
                if (value instanceof View) {
                    startAnchoredScale((View) value, anchor[0], anchor[1],
                            candidate.getName(), true);
                    return;
                }
            }
            guard.warn("player-volume exit target unavailable; keeping native animation");
        } catch (Throwable failure) {
            guard.error("anchored player-volume exit animation unavailable; keeping native animation",
                    failure);
        }
    }

    private void startAnchoredScale(View target, float anchorX, float anchorY, String fieldName,
            boolean exiting) {
        try {
            if (!target.isAttachedToWindow() || target.getWidth() <= 0
                    || target.getHeight() <= 0) {
                guard.debug("player-volume target not laid out; keeping native animation");
                return;
            }
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            float pivotX = anchorX - location[0];
            float pivotY = anchorY - location[1];
            ScaleAnimation scale = new ScaleAnimation(
                    exiting ? 1.0f : 0.0f, exiting ? 0.0f : 1.0f,
                    exiting ? 1.0f : 0.0f, exiting ? 0.0f : 1.0f,
                    Animation.ABSOLUTE, pivotX,
                    Animation.ABSOLUTE, pivotY);
            scale.setDuration(301L);
            Animation nativeAnimation = target.getAnimation();
            if (nativeAnimation != null && nativeAnimation.getInterpolator() != null) {
                scale.setInterpolator(nativeAnimation.getInterpolator());
            }
            scale.setFillAfter(exiting);
            AnimationSet anchored = new AnimationSet(true);
            anchored.addAnimation(scale);
            target.clearAnimation();
            target.startAnimation(anchored);
            guard.info("player-volume " + (exiting ? "exit" : "entry")
                    + " animation anchored after layout at screen=("
                    + anchorX + "," + anchorY + "), target=("
                    + location[0] + "," + location[1] + ","
                    + target.getWidth() + "x" + target.getHeight() + "), local=("
                    + pivotX + "," + pivotY + "), field=" + fieldName);
        } catch (Throwable failure) {
            guard.error("anchored " + (exiting ? "exit" : "entry")
                    + " animation failed; keeping native animation", failure);
        }
    }

    private boolean shouldSuppressNativeFloat(Context context, List<Object> args) {
        if (guard.isDisabled()) {
            guard.debug("float decision: keep native entry because guard is disabled");
            return false;
        }
        if (args != null) {
            if (args.isEmpty() || !(args.get(0) instanceof Intent)) {
                guard.debug("float decision: keep native entry because service args are not volume intent");
                return false;
            }
            Intent event = (Intent) args.get(0);
            if (!event.hasExtra("streamType") || !event.hasExtra("flags")) {
                guard.debug("float decision: keep native entry because volume extras are missing");
                return false;
            }
        }
        try {
            if (Settings.Global.getInt(context.getContentResolver(), SOUND_ASSIST_KEY, 0) != 1) {
                guard.debug("float decision: keep native entry because sound_assist_key is disabled");
                return false;
            }
            if (!hasPlayerVolumeService(context)) {
                guard.warn("float decision: keep native entry because VolumeUIService is unavailable");
                return false;
            }
            android.os.Bundle state = SafeState.get(context);
            boolean suppress = state != null
                    && state.getBoolean(SafeStateProvider.KEY_ENABLED, false)
                    && !state.getBoolean(SafeStateProvider.KEY_SAFE_MODE, true)
                    && state.getBoolean(SafeStateProvider.KEY_PLUGIN_READY, false)
                    && state.getBoolean(SafeStateProvider.KEY_MISOUND_READY, false)
                    && state.getBoolean(SafeStateProvider.KEY_HAS_ACTIVE_PLAYERS, false);
            guard.debug("float decision: suppress=" + suppress + ", state=" + describeState(state));
            return suppress;
        } catch (Throwable failure) {
            guard.error("float decision failed; keeping native entry", failure);
            guard.fail(failure);
            return false;
        }
    }

    private void addHook(Executable executable, String id, XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(id)
                .intercept(hooker);
        guard.addHandle(handle);
        guard.info("hook registered: " + id + " -> " + executable);
    }

    static boolean hasPlayerVolumeService(Context context) {
        try {
            Intent intent = new Intent().setClassName(MISOUND_PACKAGE, SOUND_SERVICE);
            return context.getPackageManager().resolveService(intent, 0) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void requestPlayerVolume(View anchor, ProcessGuard guard) {
        Context context = anchor.getContext();
        try {
            if (!hasPlayerVolumeService(context)) {
                guard.warn("click ignored: VolumeUIService is unavailable");
                return;
            }
            Intent intent = new Intent(SHOW_PLAYER_VOLUME_ACTION)
                    .setClassName(MISOUND_PACKAGE, SOUND_SERVICE);
            int[] location = new int[2];
            anchor.getLocationOnScreen(location);
            intent.putExtra(EXTRA_ANCHOR_X, location[0] + anchor.getWidth() / 2.0f);
            intent.putExtra(EXTRA_ANCHOR_Y, location[1] + anchor.getHeight() / 2.0f);
            SafeState.setPlayerVolumeVisible(context, true);
            UiInjector.setInjectedVisibility(anchor, false);
            guard.info("click requesting native MiSound player-volume overlay; anchor=("
                    + intent.getFloatExtra(EXTRA_ANCHOR_X, Float.NaN) + ","
                    + intent.getFloatExtra(EXTRA_ANCHOR_Y, Float.NaN) + ")");
            context.startForegroundService(intent);
        } catch (Throwable failure) {
            SafeState.setPlayerVolumeVisible(context, false);
            UiInjector.setInjectedVisibility(anchor, true);
            guard.error("click request failed", failure);
            SafeState.setPluginReady(context, false);
            guard.fail(failure);
        }
    }

    static boolean soundAssistEnabled(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), SOUND_ASSIST_KEY, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static final class ProcessGuard {
        private final XposedInterface xposed;
        private final AtomicBoolean disabled = new AtomicBoolean(false);
        private final AtomicBoolean sessionStarted = new AtomicBoolean(false);
        private WeakReference<Context> lastContext = new WeakReference<>(null);
        private final List<XposedInterface.HookHandle> handles =
                Collections.synchronizedList(new ArrayList<>());

        ProcessGuard(XposedInterface xposed) {
            this.xposed = xposed;
        }

        void debug(String message) {
            write(Log.DEBUG, message, null);
        }

        void info(String message) {
            write(Log.INFO, message, null);
        }

        void warn(String message) {
            write(Log.WARN, message, null);
        }

        void error(String message, Throwable failure) {
            write(Log.ERROR, message, failure);
        }

        private void write(int priority, String message, Throwable failure) {
            try {
                if (failure == null) {
                    xposed.log(priority, LOG_TAG, message);
                } else {
                    xposed.log(priority, LOG_TAG, message, failure);
                }
            } catch (Throwable ignored) {
                try {
                    Log.println(priority, LOG_TAG, message
                            + (failure == null ? "" : " : " + failure));
                } catch (Throwable ignoredAgain) {
                    // Logging must never affect a host process.
                }
            }
        }

        void addHandle(XposedInterface.HookHandle handle) {
            if (handle != null) {
                handles.add(handle);
            }
        }

        boolean isDisabled() {
            return disabled.get();
        }

        void rememberContext(Context context) {
            lastContext = new WeakReference<>(context);
        }

        Context lastContext() {
            return lastContext.get();
        }

        boolean canInject(Context context) {
            if (disabled.get() || context == null) {
                debug("injection denied: guardDisabled=" + disabled.get() + ", contextNull=" + (context == null));
                return false;
            }
            try {
                if (!sessionStarted.get() && sessionStarted.compareAndSet(false, true)) {
                    android.os.Bundle state = SafeState.beginSession(context);
                    info("session started: " + describeState(state));
                    if (!SafeState.canInject(state)) {
                        warn("injection disabled by safety state: " + describeState(state));
                        disabled.set(true);
                        unhookAll();
                        return false;
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> SafeState.markStable(context), 25_000L);
                }
                android.os.Bundle state = SafeState.get(context);
                boolean allowed = SafeState.canInject(state);
                if (!allowed) {
                    debug("injection denied by current safety state: " + describeState(state));
                }
                return allowed;
            } catch (Throwable failure) {
                error("safety state check failed", failure);
                fail(failure);
                return false;
            }
        }

        void fail(Throwable failure) {
            if (disabled.compareAndSet(false, true)) {
                error("guard tripped; all module hooks will be removed", failure);
                unhookAll();
                // Do not throw into a framework callback.  The protective hook and the guard both
                // ensure that a module error cannot take down the host UI.
            }
        }

        private void unhookAll() {
            synchronized (handles) {
                int count = handles.size();
                for (XposedInterface.HookHandle handle : handles) {
                    try {
                        handle.unhook();
                    } catch (Throwable ignored) {
                    }
                }
                handles.clear();
                info("hooks removed: " + count);
            }
        }
    }

    private static String describeState(android.os.Bundle state) {
        if (state == null) {
            return "null";
        }
        return "enabled=" + state.getBoolean(SafeStateProvider.KEY_ENABLED, false)
                + ",safeMode=" + state.getBoolean(SafeStateProvider.KEY_SAFE_MODE, true)
                + ",active=" + state.getBoolean(SafeStateProvider.KEY_ACTIVE, false)
                + ",pluginReady=" + state.getBoolean(SafeStateProvider.KEY_PLUGIN_READY, false)
                + ",miSoundReady=" + state.getBoolean(SafeStateProvider.KEY_MISOUND_READY, false)
                + ",hasActivePlayers="
                + state.getBoolean(SafeStateProvider.KEY_HAS_ACTIVE_PLAYERS, false)
                + ",playerVolumeVisible="
                + state.getBoolean(SafeStateProvider.KEY_PLAYER_VOLUME_VISIBLE, false);
    }

    static final class UiInjector {
        private static final String BUTTON_TAG = "codex.multivolume.button";
        private static final String DIVIDER_TAG = "codex.multivolume.divider";
        private static final Map<ViewGroup, android.database.ContentObserver> OBSERVERS =
                Collections.synchronizedMap(new WeakHashMap<>());
        private static final Map<View, Object> BUTTON_HELPERS =
                Collections.synchronizedMap(new WeakHashMap<>());
        private static final Map<ViewGroup, Boolean> EXPANDED_STATES =
                Collections.synchronizedMap(new WeakHashMap<>());

        private UiInjector() {
        }

        static void sync(ViewGroup root, ProcessGuard guard) throws Exception {
            Context context = root.getContext();
            boolean canInject = guard.canInject(context);
            boolean settingEnabled = soundAssistEnabled(context);
            guard.debug("sync begin: orientation="
                    + context.getResources().getConfiguration().orientation
                    + ", canInject=" + canInject + ", sound_assist_key=" + settingEnabled);
            if (!canInject || !settingEnabled) {
                guard.debug("sync skipped: safety or setting gate is closed");
                SafeState.setPluginReady(context, false);
                removeInjected(root);
                if (!guard.isDisabled()) {
                    ensureObserver(root, guard);
                }
                return;
            }
            if (!hasPlayerVolumeService(context)) {
                guard.warn("sync skipped: VolumeUIService cannot be resolved");
                SafeState.setPluginReady(context, false);
                removeInjected(root);
                return;
            }
            int parentId = id(context, "miui_ringer_btn_layout");
            int dndId = id(context, "dnd_layout");
            int bgBlurId = id(context, "bg_blur");
            if (parentId == 0 || dndId == 0 || bgBlurId == 0) {
                guard.warn("sync skipped: required IDs missing; parent=" + parentId
                        + ", dnd=" + dndId + ", bgBlur=" + bgBlurId);
                SafeState.setPluginReady(context, false);
                removeInjected(root);
                return;
            }
            ViewGroup parent = root.findViewById(parentId);
            View dnd = root.findViewById(dndId);
            View dndBackground = dnd == null ? null : dnd.findViewById(bgBlurId);
            if (parent == null || dnd == null || dnd.getParent() != parent
                    || dndBackground == null || !dndBackground.isClickable()) {
                guard.warn("sync skipped: runtime DND layout structure is incompatible"
                        + ", parent=" + (parent != null)
                        + ", dnd=" + (dnd != null)
                        + ", dndParentMatches=" + (dnd != null && dnd.getParent() == parent)
                        + ", background=" + (dndBackground != null)
                        + ", clickable=" + (dndBackground != null && dndBackground.isClickable()));
                SafeState.setPluginReady(context, false);
                removeInjected(root);
                return;
            }
            int layoutId = layout(context, "miui_ringer_mode_layout");
            int iconId = id(context, "icon");
            int dividerId = id(context, "miui_volume_ringer_divider");
            int speakerDrawable = drawable(context, "ic_miui_volume_media");
            if (layoutId == 0 || bgBlurId == 0 || iconId == 0 || speakerDrawable == 0) {
                guard.warn("sync skipped: button resources missing; layout=" + layoutId
                        + ", bgBlur=" + bgBlurId + ", icon=" + iconId
                        + ", speakerDrawable=" + speakerDrawable + ", divider=" + dividerId);
                SafeState.setPluginReady(context, false);
                removeInjected(root);
                return;
            }
            if (!markPluginReady(context, true)) {
                guard.fail(new IllegalStateException("safety provider unavailable"));
                removeInjected(root);
                return;
            }
            ensureObserver(root, guard);
            android.os.Bundle state = SafeState.get(context);
            boolean shouldShow = state != null
                    && state.getBoolean(SafeStateProvider.KEY_MISOUND_READY, false)
                    && state.getBoolean(SafeStateProvider.KEY_HAS_ACTIVE_PLAYERS, false)
                    && !state.getBoolean(
                            SafeStateProvider.KEY_PLAYER_VOLUME_VISIBLE, false);
            if (!shouldShow) {
                guard.debug("injected button hidden by MiSound player state: "
                        + describeState(state));
                removeInjected(root);
                return;
            }

            View existingButton = findTagged(parent, BUTTON_TAG);
            if (existingButton != null) {
                guard.debug("sync skipped: injected button already exists");
                setInjectedVisibility(existingButton,
                        !Boolean.TRUE.equals(EXPANDED_STATES.get(root)));
                refreshButtonStyle(existingButton, null);
                if (guard.isDisabled()) {
                    SafeState.setPluginReady(context, false);
                    removeInjected(root);
                }
                return;
            }
            if (Boolean.TRUE.equals(EXPANDED_STATES.get(root))) {
                guard.debug("sync skipped: system volume panel is expanded");
                return;
            }

            View button = LayoutInflater.from(context).inflate(layoutId, parent, false);
            button.setTag(BUTTON_TAG);
            View buttonBackground = button.findViewById(bgBlurId);
            ImageView icon = button.findViewById(iconId);
            if (buttonBackground == null || icon == null) {
                SafeState.setPluginReady(context, false);
                return;
            }
            button.setLayoutParams(copyLayoutParams(dnd.getLayoutParams()));
            Object buttonHelper = createButtonHelper(root, button);
            BUTTON_HELPERS.put(button, buttonHelper);
            refreshButtonStyle(button, null);
            applyAccessibility(button);
            View.OnClickListener listener = view -> requestPlayerVolume(button, guard);
            button.setOnClickListener(listener);
            button.setClickable(true);
            buttonBackground.setOnClickListener(listener);
            buttonBackground.setClickable(true);

            int insertion = parent.indexOfChild(dnd) + 1;
            guard.info("injecting right-side multi-app volume button; insertion=" + insertion
                    + ", divider=" + (dividerId != 0));
            View divider = createDivider(context, parent, dnd, dividerId);
            if (divider != null) {
                parent.addView(divider, insertion++);
            }
            parent.addView(button, insertion);
            if (guard.isDisabled()) {
                SafeState.setPluginReady(context, false);
                removeInjected(root);
                return;
            }
            guard.info("injection complete; orientation="
                    + context.getResources().getConfiguration().orientation
                    + ", children=" + parent.getChildCount());
        }

        static void detach(ViewGroup root, ProcessGuard guard) {
            guard.debug("detaching observers; injected views retained for panel reuse");
            removeObserver(root);
        }

        static void removeInjected(ViewGroup root) {
            if (root == null) {
                return;
            }
            removeTagged(root, BUTTON_TAG);
            removeTagged(root, DIVIDER_TAG);
        }

        private static void removeTagged(ViewGroup parent, String tag) {
            for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                View child = parent.getChildAt(i);
                if (tag.equals(child.getTag())) {
                    BUTTON_HELPERS.remove(child);
                    parent.removeViewAt(i);
                } else if (child instanceof ViewGroup) {
                    removeTagged((ViewGroup) child, tag);
                }
            }
        }

        private static View findTagged(ViewGroup parent, String tag) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (tag.equals(child.getTag())) {
                    return child;
                }
            }
            return null;
        }

        static void setInjectedVisibility(View button, boolean visible) {
            if (button == null || !BUTTON_TAG.equals(button.getTag())) {
                return;
            }
            int visibility = visible ? View.VISIBLE : View.INVISIBLE;
            button.setVisibility(visibility);
            Object parent = button.getParent();
            if (parent instanceof ViewGroup) {
                View divider = findTagged((ViewGroup) parent, DIVIDER_TAG);
                if (divider != null) {
                    divider.setVisibility(visibility);
                }
            }
        }

        static void includeInShowHideAnimation(Object animator, View volumeView,
                ProcessGuard guard) throws Exception {
            if (animator == null) {
                guard.debug("show/hide animation sync skipped: animator missing");
                return;
            }
            if (volumeView == null) {
                Field volumeViewField = animator.getClass().getDeclaredField("mVolumeView");
                volumeViewField.setAccessible(true);
                Object currentVolumeView = volumeViewField.get(animator);
                volumeView = currentVolumeView instanceof View ? (View) currentVolumeView : null;
            }
            if (!(volumeView instanceof ViewGroup)) {
                guard.debug("show/hide animation sync skipped: volume view missing");
                return;
            }
            View button = findTaggedRecursive((ViewGroup) volumeView, BUTTON_TAG);
            if (button == null) {
                guard.debug("show/hide animation sync waiting for injected button");
                return;
            }
            Field viewsField = animator.getClass().getDeclaredField("mRingerBtnLayouts");
            Field positionsField = animator.getClass().getDeclaredField("ringerBtnLayoutsX");
            viewsField.setAccessible(true);
            positionsField.setAccessible(true);
            View[] currentViews = (View[]) viewsField.get(animator);
            Float[] currentPositions = (Float[]) positionsField.get(animator);
            if (currentViews == null || currentPositions == null
                    || currentViews.length != currentPositions.length) {
                throw new IllegalStateException("native ringer animation arrays are incompatible");
            }
            int nativeCount = 0;
            for (View current : currentViews) {
                if (current != null && !BUTTON_TAG.equals(current.getTag())) {
                    nativeCount++;
                }
            }
            View[] updatedViews = new View[nativeCount + 1];
            Float[] updatedPositions = new Float[nativeCount + 1];
            int targetIndex = 0;
            for (int i = 0; i < currentViews.length; i++) {
                View current = currentViews[i];
                if (current != null && !BUTTON_TAG.equals(current.getTag())) {
                    updatedViews[targetIndex] = current;
                    Float position = currentPositions[i];
                    updatedPositions[targetIndex] = position == null ? 0.0f : position;
                    targetIndex++;
                }
            }
            updatedViews[targetIndex] = button;
            updatedPositions[targetIndex] = 0.0f;
            viewsField.set(animator, updatedViews);
            positionsField.set(animator, updatedPositions);
            guard.info("current injected button bound to native show/hide animation; count="
                    + updatedViews.length);
        }

        private static View findTaggedRecursive(ViewGroup parent, String tag) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (tag.equals(child.getTag())) {
                    return child;
                }
                if (child instanceof ViewGroup) {
                    View nested = findTaggedRecursive((ViewGroup) child, tag);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
            return null;
        }

        private static View createDivider(Context context, ViewGroup parent, View dnd, int dividerId) {
            if (dividerId == 0) {
                return null;
            }
            View source = parent.findViewById(dividerId);
            if (source == null) {
                return null;
            }
            View divider = new View(context);
            divider.setTag(DIVIDER_TAG);
            divider.setLayoutParams(copyLayoutParams(source.getLayoutParams()));
            divider.setBackground(source.getBackground());
            return divider;
        }

        private static Object createButtonHelper(ViewGroup root, View button) throws Exception {
            ClassLoader loader = root.getClass().getClassLoader();
            Class<?> helperClass = Class.forName(
                    RINGER_LAYOUT + "$RingerButtonHelper", false, loader);
            Constructor<?> constructor = helperClass.getDeclaredConstructor(
                    root.getClass(), View.class, boolean.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(root, button, true, false);
        }

        static void updateExpanded(ViewGroup root, boolean expanded, ProcessGuard guard)
                throws Exception {
            EXPANDED_STATES.put(root, expanded);
            ViewGroup parent = root.findViewById(id(root.getContext(), "miui_ringer_btn_layout"));
            View button = parent == null ? null : findTagged(parent, BUTTON_TAG);
            if (button == null) {
                if (!expanded) {
                    sync(root, guard);
                }
                return;
            }
            setInjectedVisibility(button, !expanded);
            if (!expanded) {
                refreshButtonStyle(button, false);
            }
            guard.debug("injected button visibility updated: expanded=" + expanded
                    + ", visible=" + !expanded);
        }

        static void refreshStyle(ViewGroup root, ProcessGuard guard) {
            try {
                ViewGroup parent = root.findViewById(
                        id(root.getContext(), "miui_ringer_btn_layout"));
                View button = parent == null ? null : findTagged(parent, BUTTON_TAG);
                if (button != null) {
                    refreshButtonStyle(button, null);
                    guard.debug("injected button style refreshed after attach");
                }
            } catch (Throwable failure) {
                guard.error("injected button style refresh failed", failure);
                SafeState.setPluginReady(root.getContext(), false);
                guard.fail(failure);
                removeInjected(root);
            }
        }

        private static void refreshButtonStyle(View button, Boolean expanded) throws Exception {
            Object helper = BUTTON_HELPERS.get(button);
            if (helper == null) {
                return;
            }
            if (expanded != null) {
                Method onExpanded = helper.getClass().getDeclaredMethod(
                        "onExpanded", boolean.class, boolean.class);
                onExpanded.setAccessible(true);
                onExpanded.invoke(helper, expanded, false);
            }
            Method updateState = helper.getClass().getDeclaredMethod("updateState");
            updateState.setAccessible(true);
            updateState.invoke(helper);
            Context context = button.getContext();
            ImageView icon = button.findViewById(id(context, "icon"));
            int speakerDrawable = drawable(context, "ic_miui_volume_media");
            if (icon == null || speakerDrawable == 0) {
                throw new IllegalStateException("speaker icon resources unavailable");
            }
            icon.setImageResource(speakerDrawable);
            icon.setContentDescription("多应用音量");
        }

        private static void applyAccessibility(View button) {
            Context context = button.getContext();
            View standard = button.findViewById(id(context, "miui_standard_btn"));
            if (standard != null) {
                standard.setAccessibilityDelegate(null);
                standard.setContentDescription("多应用音量");
            }
            button.setContentDescription("多应用音量");
        }

        private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
            if (source == null) {
                return new LinearLayout.LayoutParams(-2, -2);
            }
            LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(source);
            if (source instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams linear = (LinearLayout.LayoutParams) source;
                copy.weight = linear.weight;
                copy.gravity = linear.gravity;
            }
            if (source instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) source;
                copy.setMargins(margins.leftMargin, margins.topMargin,
                        margins.rightMargin, margins.bottomMargin);
            }
            return copy;
        }

        private static int id(Context context, String name) {
            return context.getResources().getIdentifier(name, "id", PLUGIN_PACKAGE);
        }

        private static int layout(Context context, String name) {
            return context.getResources().getIdentifier(name, "layout", PLUGIN_PACKAGE);
        }

        private static int drawable(Context context, String name) {
            return context.getResources().getIdentifier(name, "drawable", PLUGIN_PACKAGE);
        }

        private static boolean markPluginReady(Context context, boolean ready) {
            android.os.Bundle state = SafeState.setPluginReady(context, ready);
            return state != null
                    && state.getBoolean(SafeStateProvider.KEY_PLUGIN_READY, !ready) == ready;
        }

        private static void ensureObserver(ViewGroup root, ProcessGuard guard) {
            if (OBSERVERS.containsKey(root)) {
                guard.debug("settings observer already registered for root");
                return;
            }
            WeakReference<ViewGroup> reference = new WeakReference<>(root);
            android.database.ContentObserver observer = new android.database.ContentObserver(
                    new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    ViewGroup current = reference.get();
                    if (current == null) {
                        return;
                    }
                    try {
                        current.post(() -> {
                            try {
                                if (!current.isAttachedToWindow()) {
                                    return;
                                }
                                sync(current, guard);
                            } catch (Throwable failure) {
                                SafeState.setPluginReady(current.getContext(), false);
                                guard.fail(failure);
                                removeInjected(current);
                            }
                        });
                    } catch (Throwable failure) {
                        SafeState.setPluginReady(current.getContext(), false);
                        guard.fail(failure);
                    }
                }
            };
            boolean globalRegistered = false;
            try {
                root.getContext().getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(SOUND_ASSIST_KEY), false, observer);
                globalRegistered = true;
                root.getContext().getContentResolver().registerContentObserver(
                        android.net.Uri.parse("content://" + SafeStateProvider.AUTHORITY),
                        false, observer);
                OBSERVERS.put(root, observer);
                guard.info("settings and safety observers registered");
            } catch (Throwable failure) {
                if (globalRegistered) {
                    try {
                        root.getContext().getContentResolver().unregisterContentObserver(observer);
                    } catch (Throwable ignored) {
                    }
                }
                guard.error("observer registration failed", failure);
                guard.fail(failure);
            }
        }

        private static void removeObserver(ViewGroup root) {
            android.database.ContentObserver observer = OBSERVERS.remove(root);
            if (observer == null) {
                return;
            }
            try {
                root.getContext().getContentResolver().unregisterContentObserver(observer);
            } catch (Throwable ignored) {
            }
        }
    }
}
