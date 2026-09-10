# HyperVolume

为 HyperOS 4 音量面板添加原生风格的多应用音量调节入口。

作者：[Mo-SeTian](https://github.com/Mo-SeTian)

这是一个基于 LSPosed / libxposed API 102 的 Java 模块。它不修改 `framework.jar`、系统音频服务或设置 APK；在主 `com.android.systemui` 中只拦截插件 ClassLoader 的创建，以便进入 HyperOS 实际加载的音量插件。本项目并非小米官方项目。

## 功能

- 在勿扰按钮旁加入与原生按钮风格一致的多应用音量入口。
- 仅在 MiSound 筛选后的活动应用列表不为空时显示入口。
- 隐藏原有的左侧蓝色浮动入口，点击新按钮仍打开原生多应用音量窗口。
- 系统音量面板展开时自动隐藏入口，收起后按活动应用状态恢复。
- 多应用音量窗口从新按钮位置展开，并向同一位置收回。
- 使用系统运行时资源适配横屏、折叠屏和大屏布局。
- Hook 或资源解析异常时停止介入，并保留系统原始界面。

## 实现范围

- `com.android.systemui`：HyperOS 当前把 `miui.systemui.plugin` 作为 SystemUI 的动态插件加载，不会给插件单独创建可被 LSPosed 注入的进程。因此模块只在 SystemUI 中 Hook `PluginInstance.PluginFactory.createClassLoader()`，拿到插件自己的 ClassLoader 后，再在 `MiuiRingerModeLayout` 的创建、附着和展开状态回调中按运行时资源查找 `miui_ringer_btn_layout` 和 `dnd_layout`，确认勿扰按钮仍挂在真实容器且可点击，再在其后插入扬声器按钮。按钮布局与边框状态由系统原生 `RingerButtonHelper` 生成，横屏、折叠屏、大屏及展开状态由 ROM 资源自动决定。
- `com.miui.misound`：左侧蓝色浮动入口和原生多应用音量浮层实际由 MiSound 的 `VolumeUIService` 与播放器音量控制器管理。仅在设置开关开启、原生服务存在且右侧按钮已经成功注入时，阻止产生左侧浮窗；点击新按钮通过该服务调用与蓝色小喇叭相同的原生控制器路径，不再启动 `SoundAssistActivity`。
- `SafeStateProvider`（独立 `:safety` 进程）：独立于目标进程保存启用状态、注入状态和短时崩溃熔断标记。SystemUI 在稳定运行 25 秒后清除活动标记；若短时间内再次加载，则自动进入安全模式，不再注入或隐藏系统入口。

## 诊断日志

模块使用 libxposed 日志接口输出统一 tag：`HyperOsMultiVolume`。日志会记录进程加载、目标类和方法是否找到、Hook 是否注册、设置开关、横竖屏方向、资源/布局校验、注入结果、点击跳转、原生入口抑制和熔断原因；不会记录音频内容或账号信息。排查时优先搜索以下关键词：`module loaded`、`hook registered`、`sync skipped`、`injection complete`、`guard tripped`。

SystemUI 的 `PluginFactory` 会依次创建多个插件的 ClassLoader；日志中的 `skipping non-target plugin class loader`（例如 `MIUIAod`）是正常跳过，只有 `SystemUI created target plugin class loader` 后才会继续查找音量布局。这样不会因其他插件没有音量类而提前触发熔断。

所有 Hook 都使用 `ExceptionMode.PROTECTIVE`，回调内部也捕获 `Throwable`。资源、类、原生 Activity 或入口缺失时保持原始面板；异常时解除本进程 Hook 并保留系统原始行为。Android 负责重启崩溃的 SystemUI，模块负责下一次加载时熔断，避免反复崩溃。

## 构建

`libxposed:service:102.0.0` 必须作为运行时依赖打进 APK；它提供 LSPosed API 102 所需的 `XposedService` Provider。若只声明 API 的 `compileOnly` 依赖，模块虽然会出现在 LSPosed 作用域中，但不会真正收到入口回调。

```bash
gradle :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

### 正式版签名

首次发布前生成并长期保管同一份签名文件。以下命令会交互式询问密码，不要把密码直接写进命令或提交到仓库：

```bash
keytool -genkeypair -v \
  -keystore hypervolume-release.jks \
  -alias hypervolume \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

复制签名配置模板：

```bash
cp keystore.properties.example keystore.properties
```

编辑本地 `keystore.properties`，填写签名文件路径、别名和密码，然后构建：

```bash
gradle clean :app:assembleRelease
```

签名配置完整时，产物为 `app/build/outputs/apk/release/app-release.apk`。发布前可使用 Android SDK 的 `apksigner` 验证：

```bash
apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

如果 `apksigner` 没有加入 `PATH`，请直接使用 Android SDK `build-tools/<版本>/apksigner` 的完整路径。没有 `keystore.properties` 时只会生成 `app-release-unsigned.apk`，该文件仅用于验证 Release 编译，不能作为正式发布包。

`keystore.properties`、`*.jks` 和 `*.keystore` 已被 Git 忽略。必须离线备份签名文件与密码；丢失原签名后将无法为现有用户提供可覆盖安装的更新。

Release 构建不输出高频调试日志，但保留模块加载、Hook 注册、兼容性警告、注入结果、错误和熔断日志。Debug 构建继续输出完整诊断信息。

## GitHub Actions 发布

仓库中的 `.github/workflows/build-release.yml` 会在每次 push 或 Pull Request 时构建 Debug APK，并上传为 Actions artifact。推送与 `versionName` 一致的标签（例如 `v1.0.15`）时，工作流会额外签名 Release APK 并创建或更新 GitHub Release。

在仓库的 Settings → Secrets and variables → Actions 中添加以下 Secrets：

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

`ANDROID_KEYSTORE_BASE64` 是 `hypervolume-release.jks` 的 Base64 内容；macOS 可使用 `base64 -i hypervolume-release.jks | tr -d '\\n'` 生成后粘贴到 Secret。其余三个 Secret 分别对应 `keystore.properties` 中的密码、别名和密钥密码。工作流只在 Runner 临时目录写入签名文件，任务结束后随 Runner 一起销毁。

发布流程示例：

```bash
git tag v1.0.15
git push origin v1.0.15
```

标签必须与 `app/build.gradle.kts` 的 `versionName` 完全一致，否则 Release 任务会停止，不会发布错误版本。

在 LSPosed 中启用模块并勾选以下作用域：

```text
com.android.systemui
miui.systemui.plugin
com.miui.misound
```

模块状态页可关闭功能或清除安全模式。安装或更新模块后需要完整重启设备，确保 SystemUI 与 MiSound 同时加载相同版本。目标 ROM 版本不匹配时应保持不注入。

## 兼容性

当前实现基于 HyperOS 4 SystemUI 插件 `18.2.2.2.0` 与 MiSound `16-4.0-4-20260819` 开发和验证。其他系统版本的类名、资源或布局结构发生变化时，模块会判定为不兼容并保留原生行为。
