# Fotufilm Android APK 打包完整指南

> 适用版本：1.14 (build 1024) · 包名 `com.muastudio.fotufilm.flutter` · 仅支持 **arm64-v8a**（minSdk 28，即 Android 9.0+）

---

## 一、项目结构与核心模块

```
fotufilm-engine-main/
├── Sources/                        # 引擎核心（Swift / C++）
│   ├── FotufilmCore/               # 胶片模拟引擎核心：渲染管线 Pipeline.swift、
│   │                               #   光谱模型 SpectralModel.swift、胶片档案 FilmStock.swift、
│   │                               #   Stocks/（50 份胶片 JSON 档案，约 14MB）、
│   │                               #   BoundedCache.swift（LRU 有界缓存，防内存膨胀）
│   ├── FotufilmImaging/            # 图像成像算法
│   ├── FotufilmEditModel/          # 编辑状态模型（编辑历史、撤销）
│   ├── FotufilmHost/               # 宿主服务实现（导入/渲染/导出/缩略图）
│   ├── CFotufilmHost/              # C API 边界（fotufilm.h）：engine_create / host_call /
│   │                               #   engine_cancel / set_presenter / answer_free
│   ├── CFotufilmCodecs/            # 图像解码封装：LibRaw、libjpeg、libpng、OpenEXR、
│   │                               #   libheif、libtiff
│   ├── CFotufilmVideo/             # 视频解码封装（FFmpeg）
│   └── FotufilmHalide/             # Halide AOT 内核接入（Android 走 Vulkan 后端）
├── flutter/                        # Flutter 编辑器外壳（一套 UI：macOS/Linux/Android/Web）
│   ├── lib/
│   │   ├── main.dart               # 入口：加载目录/图标/设置 → EditorModel → runApp → start()
│   │   ├── app/                    # 窗口层：工作区布局 workspace.dart（含横竖屏断点）、
│   │   │                           #   触摸缩放 touch_scale.dart、命令与快捷键
│   │   ├── model/                  # 业务模型层：editor.dart（总装配）、renderer.dart
│   │   │                           #   （渲染调度器）、photo_library、export、video 等 50+ 文件
│   │   ├── engine/                 # 引擎连接层：engine_native.dart（dart:ffi + isolate）、
│   │   │                           #   fotufilm_ffi.dart（C 绑定）、presenter.dart
│   │   └── ui/                     # 主题 theme/、控件 kit/、检查器 inspector/、工作区 workspace/
│   ├── android/                    # Android 宿主（本项目打包目标）
│   │   └── app/src/main/kotlin/.../
│   │       ├── MainActivity.kt     # 唯一 Activity（FlutterActivity）
│   │       ├── EngineResources.kt  # 首次安装解包胶片档案到 filesDir，设引擎环境变量
│   │       └── OpenedFiles.kt      # 系统 SAF 选图，流式拷贝到 cache（1MB 缓冲）
│   ├── build-android.sh            # 一键打包脚本
│   └── tool/prepare.sh             # 资产准备（控件目录/图标/字体，需 Node.js + 网络）
├── tools/
│   ├── build-android-codecs.sh     # 交叉编译编解码静态库（一次性）
│   ├── build-android-engine.sh     # 交叉编译 libfotufilm.so + 收集随包 so + 资产
│   └── copy-shipping-resources.sh  # 收集胶片档案/相机档案/许可证到 assets
├── macos/ resolve/ finalcut/ web/  # 其他宿主（与本任务无关）
└── version.env                     # MARKETING_VERSION=1.14, CURRENT_PROJECT_VERSION=1024
```

## 二、App 运行完整业务逻辑与执行流程

### 1. 启动流程（冷启动）

```
[Android 进程启动]
  ① MainActivity.onCreate
       └─ EngineResources.prepare()
            ├─ 读 filesDir/fotufilm-resources/.installed 时间戳
            ├─ 不一致（首次安装/升级）→ 解压 assets/fotufilm/（胶片档案+相机档案，约 15MB）
            └─ setenv: FOTUFILM_RESOURCES / FOTUFILM_STOCKS / HOME / TMPDIR / XDG_*
  ② super.onCreate → FlutterEngine 初始化 → Dart 入口 main() 执行
  ③ main.dart：
       ├─ 并行加载：Catalogue.load()（控件目录）+ IconLibrary + ColourConversion + AppSettings
       ├─ new EditorModel(catalogue)（总装配 50+ 功能模型）
       ├─ runApp(FotufilmApp)（首帧上屏，闪屏消失）
       └─ await editor.start()
  ④ editor.start()：
       ├─ connectEngine() → 派生 "fotufilm-engine" isolate
       │    └─ isolate 内：DynamicLibrary.open("libfotufilm.so")
       │        → fotufilm_engine_create() → 引擎实例常驻该 isolate
       ├─ loadStocks() → host_call("prepare") → 引擎读胶片档案 → 50 部胶片目录
       ├─ NativePresenter.attach() → Android 无 presenter 通道 → 返回 null（走 PNG 回传路径）
       ├─ new Renderer(engine, catalogue, host=this)
       └─ startup 状态机：connecting → loadingFilms(70%) → ready(100%)
  ⑤ 启动收尾：胶片包/插件/更新检查 launch()；处理打开文件请求
```

### 2. 打开照片流程

```
用户点击"打开"（chooseFiles → MethodChannel "fotufilm/files" → OpenedFiles.kt）
  → 系统 SAF 选择器 ACTION_OPEN_DOCUMENT（多选，image/* + video/*）
  → 每个内容 URI 流式拷贝到 cacheDir/opened/<nanoTime>/（1MB 缓冲，不占整块内存）
  → 路径回传 Dart → editor.openPaths → strip.open → openFiles（串行、可取消）
  → engine.call("importPath", {path, negative})
      （isolate 内 fotufilm_host_call → 引擎解码 RAW/JPEG/HEIC/EXR… → 线性 Rec.2020）
  → 返回 handle + 尺寸 + 初始预览 → new PhotoDocument（handle、编辑历史 History）
  → _activate → renderer.schedule()
```

### 3. 渲染管线（每次编辑/查看）

```
renderer.schedule(interactive)
  ├─ 有渲染在跑且是全尺寸前台渲染 → engine.cancel()（引擎在安全点停止）
  └─ _run() 循环（同一时刻最多一个请求在飞，永远只执行"最新"的请求）：
       ├─ _preview(doc, interactive)
       │    ├─ 计算目标边长：照片长边 × 显示比例 × devicePixelRatio
       │    │    · 滑杆拖动中（interactive）：手机钳到 600px 草稿边（桌面 1200px）
       │    │    · 静止：最高 4096px
       │    └─ engine.call("render", {edit, maxEdge, present|bytes})
       │         → 引擎五阶段：曝光(Rec.2020 线性) → 胶片感光(81 波长光谱) →
       │           显影(特性曲线→光密度) → 颗粒(按胶片规格物理缩放) → 输出(相纸/扫描/反转)
       │         → Android 无 presenter：PNG 字节回传 → decodePng → doc.previewImage
       ├─ _detail(doc)：放大且静止时，只渲染可见区域全分辨率
       └─ 后台（250ms 空闲后）：直方图 → 预设缩略图 → 胶片行缩略图（160px，屏幕内才渲染）
```

### 4. 导出流程

```
export 对话框 → engine.callInBackground("export", …)（第二条 "fotufilm-exports" isolate 车道）
  → 导出与编辑渲染并行互不阻塞 → 进度回调实时上屏 → cancelBackground 可中断
缩略图（照片库）→ callAside → 另起 min(4, 核数/2) 条 "fotufilm-thumbnails-N" isolate 车道并行
```

## 三、性能调优（本次已完成的加固 + 原有机制说明）

### 本次修复（Android 宿主层）

| 问题 | 位置 | 修复 |
| --- | --- | --- |
| **存储泄漏**：每次选图在 `cache/opened/<nanoTime>/` 留一份 RAW/视频完整拷贝，从不清理，长期使用可累积 GB 级垃圾 | `OpenedFiles.kt` | Activity 重建/新会话启动时，后台线程清理所有写入时间早于本次启动的拷贝文件夹（本会话新拷贝不受影响；文件夹名 nanoTime 非时钟，改用文件系统 lastModified 判龄） |
| **线程池泄漏**：Activity 重建（系统回收后恢复）每次新建一个永不关闭的拷贝线程池 | `OpenedFiles.kt` | 拷贝 executor 与待应答的 picker 结果移入 companion object 进程级共享，重建时旧 pending 结果安全回空 |
| **启动崩溃风险**：`.installed` 时间戳文件损坏（写一半/磁盘满）→ readText 抛异常直接闪退 | `EngineResources.kt` | 时间戳读写全部 runCatching 包裹：读损坏→视为未安装→重新解压；写失败→下次启动重解压一次，不崩溃 |
| **时间戳获取失败** | `EngineResources.kt` | getPackageInfo 异常时回退 `System.currentTimeMillis()` |

### 原有性能设计确认（无需改动，说明如下）

- **UI 永不卡死**：所有引擎调用在独立 isolate，UI isolate 零等待；`fotufilm_ffi.dart` 所有 malloc/free 严格配对（finally 块），无 FFI 内存泄漏。
- **滑杆跟手**：拖动中降采样渲染（手机 600px 草稿边），松手后 4096px 全尺寸精渲染；新请求取消旧请求（草稿不取消——手机 GPU 上连续取消会导致画面停更，renderer.dart 顶部注释明确了这一设计）。
- **缩略图有界**：照片库缩略图 LRU 缓存上限 600 张；引擎侧 BoundedCache 同为 LRU 有界；`decodePng`/`ImmutableBuffer` 均及时 dispose。
- **大文件安全**：SAF 文件以 1MB 缓冲流式拷贝（file_selector 插件会整读内存导致大 RAW 崩溃，项目因此自写 OpenedFiles）。
- **渲染分辨率自适应**：预览边长 = 照片 × 显示比例 × devicePixelRatio，任何屏幕/缩放/旋转下都按真实物理分辨率渲染，不浪费像素也不模糊。

## 四、横竖屏切换与多分辨率自适应（已内建，机制说明）

| 层 | 机制 |
| --- | --- |
| **AndroidManifest** | `android:configChanges="orientation\|screenSize\|smallestScreenSize\|screenLayout\|density\|fontScale\|uiMode\|…"`：旋转不重建 Activity，Flutter 全权处理，无状态丢失、无闪屏 |
| **布局断点**（`workspace.dart` + `panel_layout.dart`） | `LayoutBuilder` 实时感知约束：宽度 ≤ 833px 进入手机堆叠布局；宽度 ≤ 833 且 宽 > 高 × 1.3 → **横屏布局**（dock 与画面并排，dock 宽 280–380px）；否则**竖屏布局**（画面在上，dock 在下 180–340px 可拖拽，底部 48px 面板栏） |
| **触摸缩放**（`touch_scale.dart`） | 短边 < 600dp 的设备整体 1.25× 放大绘制（MediaQuery 等比换算），旋转后尺寸变化自动重算；≥ 600dp 平板用桌面布局 |
| **渲染分辨率**（`viewer.dart` → `editor.updateViewer`） | 旋转后 LayoutBuilder 触发 → 新尺寸 + 新 DPR 写入 view 状态 → 120ms 防抖 → renderer 重渲染对应分辨率的预览 |
| **异形屏** | `MediaQuery.padding` 避让刘海/挖孔（左右 padding 由 workspace 处理，底部由 compact rail 处理）；亮暗双主题（values / values-night） |

## 五、打包依赖（在 macOS 或 Linux 主机上执行）

> ⚠️ 引擎为 Swift 编写，Swift Android 交叉编译链仅提供 macOS/Linux 主机版本，**无法在 Windows 原生构建**。Windows 用户请用 WSL2（Ubuntu）或一台 macOS/Linux 机器。本机当前环境未安装 Flutter/Android SDK，无法直接产出 APK，请按下述步骤在受支持的宿主机上执行。

| 依赖 | 版本要求 | 用途 |
| --- | --- | --- |
| Flutter SDK | ≥ 3.38（Dart ≥ 3.11） | App 外壳编译 |
| JDK | 17（Gradle 8.14 + AGP 8.11.1 要求） | Android 构建 |
| Android SDK | compileSdk 由 Flutter 决定，platform-tools | 打包 |
| Android NDK | r27+（`ANDROID_NDK_HOME` 或置于 `$ANDROID_HOME/ndk/`） | 交叉编译引擎 |
| swift.org 工具链 | 6.3.3-RELEASE（`SWIFT_TOOLCHAIN`） | Swift→Android 编译（Xcode 自带的 Swift 不行） |
| Swift Android SDK | swift-6.3.3-RELEASE_android（`swift sdk install`） | Android sysroot |
| Halide | 带 Vulkan 补丁版（`tools/build-halide-linux.sh` 自建，`HALIDE_ROOT`） | AOT 渲染内核 |
| Node.js | ≥ 18 | `tool/prepare.sh` 生成控件目录 |
| Python 3 | ≥ 3.10 | 资产校验/打包审计 |
| cmake/ninja | 最新 | Halide 内核生成 |
| 磁盘 | ≥ 20GB 空闲 | NDK + 编译中间产物 |

首次需要网络：下载 Gradle 发行版、Fontshare 字体（Satoshi）、Gradle/Maven 依赖。

## 六、构建步骤

### 方式 A：一键脚本（推荐）

```sh
# 0. 环境变量（按实际路径调整）
export ANDROID_HOME=~/Android/Sdk              # 或 ~/Library/Android/sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.x.x
export HALIDE_ROOT=/path/to/halide             # 带 Vulkan 补丁的 Halide
export SWIFT_TOOLCHAIN=/Library/Developer/Toolchains/swift-6.3.3-RELEASE.xctoolchain
export SWIFT_SDK=swift-6.3.3-RELEASE_android   # 已 swift sdk install

# 1.（一次性）交叉编译编解码静态库 → build/android-deps/arm64/
tools/build-android-codecs.sh

# 2. 交叉编译引擎 → flutter/build/android-engine/{jniLibs,assets}
tools/build-android-engine.sh

# 3. 一键打包（内部自动做 2 与资产准备，然后 flutter build apk）
flutter/build-android.sh
# 产物：build/flutter-host/Fotufilm.apk

# 可选：Google Play 格式
flutter/build-android.sh --appbundle          # → build/flutter-host/Fotufilm.aab

# 可选：release 签名（不设则用 debug 签名，可直接侧载安装）
export FOTUFILM_ANDROID_KEYSTORE=/path/keystore.jks
export FOTUFILM_ANDROID_KEYSTORE_PASSWORD=…
export FOTUFILM_ANDROID_KEY_ALIAS=…
export FOTUFILM_ANDROID_KEY_PASSWORD=…
```

### 方式 B：开发迭代（只改 Flutter 层时）

```sh
cd flutter
tool/prepare.sh                                # 每次改 web/src 的控件/图标后重跑
flutter build apk --release --target-platform android-arm64
# 或真机热重载：flutter run --release
```

### 安装到手机

```sh
adb install -r build/flutter-host/Fotufilm.apk   # debug 签名可直接覆盖安装
```

## 七、最终产物结构

**外部产物**：`build/flutter-host/Fotufilm.apk`（arm64-v8a，minSdk 28）

```
Fotufilm.apk
├── lib/arm64-v8a/
│   ├── libfotufilm.so            # 引擎本体（Swift 核心 + C++ 编解码 + Halide AOT 内核，
│   │                             #   已 strip，符号表经 exports.map 裁剪）
│   ├── libswiftCore.so 等        # Swift 运行时（按 DT_NEEDED 自动遍历收集）
│   ├── libFoundation*.so         # Swift Foundation
│   └── libavcodec.so 等          # FFmpeg（视频解码，运行时按名加载）
├── assets/
│   └── fotufilm/                 # 由 EngineResources.kt 首次启动解压到 filesDir
│       ├── Stocks/               # 47+2 部胶片 JSON 档案 + CC BY-SA 4.0 许可证
│       ├── CameraProfiles/       # 相机光谱档案
│       ├── rec2020-reflectance-prior.coeff
│       └── LICENSE / NOTICE / ThirdPartyNotices.txt
├── flutter_assets/               # Flutter 产物（图标、字体、着色器、控件目录）
│   └── shaders/display_p3_to_srgb.frag
├── classes.dex                   # Kotlin 宿主（MainActivity + EngineResources + OpenedFiles）
└── res/                          # 启动主题（亮/暗）、图标
```

**APK 之外**：`build/flutter-host/Fotufilm.aab`（--appbundle 时）；引擎中间产物在 `build/android-engine/`（obj）与 `flutter/build/android-engine/`（jniLibs+assets，Gradle 直接引用）。

## 八、常见问题排查

| 现象 | 原因与处理 |
| --- | --- |
| `error: no Android NDK (set ANDROID_NDK_HOME)` | 设 `ANDROID_NDK_HOME` 指向 NDK 根目录 |
| `set SWIFT_TOOLCHAIN to a swift.org toolchain` | 必须用 swift.org 的 6.3.3 工具链，Xcode/系统 Swift 读不了 Android SDK 模块 |
| `Swift SDK … is not installed` | `swift sdk install <swift-6.3.3-RELEASE_android.artifactbundle.tar.gz>` |
| `no Android codecs in build/android-deps` | 先跑 `tools/build-android-codecs.sh`（一次性） |
| Halide 内核缺失/重生成 | 设 `HALIDE_ROOT` 后重跑 `tools/build-android-engine.sh`（或 `tools/generate-halide-aot-android.sh` 手动生成） |
| Gradle `OutOfMemoryError` | `flutter/android/gradle.properties` 的 `-Xmx8G` 按构建机内存调低（如 4G） |
| prepare.sh 字体下载失败 | 检查网络；字体只下载一次（已有缓存则跳过） |
| APK 内残留本地路径导致脚本报错 | 脚本自带 `audit-bundle-paths.py` 审计，按提示把源码移到无空格/无用户名的中性路径重编 |

## 九、许可与边界

- 引擎代码 Apache-2.0；胶片档案 CC BY-SA 4.0（修改/再分发需署名+相同方式共享；渲染结果照片不受限）。
- 本指南只覆盖本地打包与侧载安装；不涉及 Google Play 上架、商店审核与签名资质代办。
