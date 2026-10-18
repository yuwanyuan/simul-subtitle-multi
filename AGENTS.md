# 本地开发规范

本仓库是 [KorenKrita/simul-subtitle](https://github.com/KorenKrita/simul-subtitle) 的 fork（创建于 2026-10-16）。本文档面向在本仓库内工作的人与 AI 编码助手，约定**本地改动、构建、测试与上游同步**的规则；项目本身的技术文档见 [docs/README.md](docs/README.md)。本文件为本地新增（fork 的 "multi" 部分），不进入上游 `docs/` 索引，也不回传上游。

---

## 分支与远端

| 名称 | 指向 | 用途 |
|---|---|---|
| `main` | 跟踪 `upstream/main` | 上游的**快进镜像**：只接受 `merge --ff-only`，禁止提交任何内容 |
| `my-mod` | 跟踪 `origin/my-mod` | 全部本地修改的唯一工作分支 |
| `origin` | `yuwanyuan/simul-subtitle-multi` | 唯一可推送远端 |
| `upstream` | `KorenKrita/simul-subtitle` | 只读。本账号无该仓库写权限（push 实测 403），不回传任何改动 |

仓库本地配置已固两项：`remote.pushDefault=origin`（裸 `git push` 永远指向 fork）、`rerere.enabled=true`（自动复用解决过的冲突）。

## 版本锚点

以下版本是基准。升级其中任何一个都会与上游产生大面积冲突，没有充分理由且未在提交正文说明的，一律不做：

| 项 | 版本 |
|---|---|
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin（含 Compose 插件） | 2.1.21 |
| Compose Multiplatform | 1.8.2 |
| sherpa-onnx（AAR / 桌面原生库） | 1.13.8 |
| OkHttp / JUnit | 4.12.0 / 4.13.2 |
| Android | compileSdk 35，minSdk 29（AudioPlaybackCapture 下限），仅 `arm64-v8a` |

## 构建与测试

```bash
# 1. 拉取 git 未持有且无法从包管理器安装的构建依赖：sherpa-onnx AAR 与约 77 MB 离线模型，逐文件校验字节数与 SHA-256
./setup.sh

# 2. 构建（环境：JDK 17 + Android SDK platforms;android-35、build-tools;34.0.0；无需 NDK）
./gradlew -q :app:assembleRelease       # 产物 app/build/outputs/apk/release/，APK 只带 VAD，识别模型在 App 内下载
./gradlew :desktop:run                  # 桌面端源码直跑（macOS 需先 tools/mac-capture/build.sh）

# 3. 单元测试——任何对 :core 的改动，这是最低限度的验证
./gradlew :core:test :app:testDebugUnitTest

# 4. CI：推送 my-mod 自动构建 release APK（.github/workflows/android.yml），产物在 run 页 Artifacts
gh run list -R yuwanyuan/simul-subtitle-multi --workflow android.yml
gh run download -R yuwanyuan/simul-subtitle-multi <run-id> -n SimulSub-android-arm64 -D /tmp/ci-apk
# 发布（应用内更新的数据源，见下文"已知坑"）：
gh release create v<x.y.z> /tmp/ci-apk/SimulSub-*.apk -R yuwanyuan/simul-subtitle-multi
```

版本号：`versionName`/`versionCode` 定义在 `app/build.gradle.kts`，`:desktop` 用正则从该文件读取（jpackage 的 `CFBundleVersion` 另有 `1.<versionCode>`，见 docs/build.md）。本 fork 的 `versionName` 是**「上游版号 + `-multi.<本地号>`」**：同步上游后前半段换成上游版号，`-multi.` 后面的数字每次 fork 发布 +1，上游版号一变就重新从 1 数（`1.0.7-multi.3` → 上游 1.0.8 → `1.0.8-multi.1`）。上游仍用 `Version X.Y` 的独立提交改版本号，同步时把它接上后缀即可。`versionCode` **保持上游的 27 不动**，所以应用内「自动更新」不会自动触发（见下文已知坑 1：`isNewer` 比的是 versionCode）；要让 fork 的 Release 能触发它，得让 versionCode 高于已装版本（例如 `上游版号×100 + 本地号`），那是一次刻意的决定，改前先定。`UpdateRelease.parse` 已接受 `-multi.<n>` 形状的 versionName（拒绝带路径字符的形状）；发 Release 时 tag 与附件名要和它一致：`v<versionName>`、附件 `simul-subtitle-v<versionName>.apk`（`gh release create` 前把 CI 产物改名）。

签名：`:app` 打包（debug 与 release 同配置）需要 `keystore/fork-debug.keystore`，缺失时 `validateSigningDebug` 直接失败。本机已有一份；新克隆要么向作者取文件，要么用 `keytool -genkeypair -keystore keystore/fork-debug.keystore -storetype PKCS12 -storepass simulsub-debug -alias fork-debug -keyalg RSA -keysize 2048 -validity 36500` 自建一把（自建的签名与发布包不同，两者不能互相覆盖升级）。

## 本机实测（Windows + Android 模拟器）

- **arm64-only 的 APK 装到本机 x86_64 模拟器上跑不了原生库**：onnxruntime 会在 `dlopen` 静态初始化阶段就 SIGSEGV（崩在 linker64 的 call_constructors，还没进应用代码）——这是模拟器 ARM 转译的缺陷，不是应用 bug，别去追。要在模拟器上验证 sherpa-onnx 路径：临时把 `app/build.gradle.kts` 的 `abiFilters` 改成 `"x86_64"`（AAR 自带 x86_64 原生库）构建 debug 包，测完 `git checkout app/build.gradle.kts` 恢复，**这个改动绝不提交**。
- **Git Bash 会把 adb 参数里的 `/sdcard/...` 悄悄转成 Windows 路径**（曾把文件"成功"推到 `C:\Program Files\Git\sdcard\...` 本机磁盘）。推设备文件一律 `MSYS_NO_PATHCONV=1 adb push ...`。反过来 `adb push` 可以直接写 `/sdcard/Android/data/<包名>/files/asr-models/<id>/`——文件尺寸与 `AsrModels.kt` 目录锚点一致就会被认成"已下载"，不必走 UI 下载几百 MB。
- **模拟器上快速配 BYOK/选模型**（debug 构建）：`adb shell am force-stop <包名>` 后 `adb shell "run-as <包名> sed -i '...' /data/data/<包名>/shared_prefs/jpsub.xml"`。byokUrl/byokModel/byokUserAgent 是明文；key 可空（`byokReady` 不要求 key），密钥字段是用 Android Keystore 封的、别手写。改完重启应用生效。
- **uiautomator dump 在本 App 上会失败**（首页 ticker 每 800ms 重绘，永远到不了 idle）：用截图 + 坐标换算操作，截图 PNG 就是设备分辨率（1080×2340）。模拟器内存只有 2GB，加载 297MB 的多语种模型属于极限操作。


## fork 模式的两个已知坑（已于 2026-10-18 处置，此节记录现状）

1. **应用内更新检查**：`AppUpdater.kt` 的清单源**已改指向本 fork** `yuwanyuan/simul-subtitle-multi` 的 Release（改动集中在一个常量 `REPO`，与上游的冲突面只有那一行）。本仓库暂未发布 Release，检查更新会温和地提示"暂时没查到新版本"。要发布用它：把 CI 的 artifact APK（`SimulSub-<version>-<commit>.apk`）作为 `gh release create v<x.y.z>` 的附件。注意 `UpdateRelease.parse` 要求清单里的 apkUrl 与文件名匹配 `REPO` 的 release 路径形状；`versionCode` 按上游规矩不动（27），所以除非另行约定，应用内"自动更新"不会自动触发。**绝不要把这个常量改回上游地址**：那样它会把官方 APK 当更新下载，而本 fork 的 applicationId 不同，装下去是第二个 App 而不是升级。
2. **签名与包名**：本 fork 的**所有构建共用一把固定 keystore**：`keystore/fork-debug.keystore`，`keystore/` 在 `.gitignore` 里、不进仓库——本机就是仓库目录下这个文件，CI 构建前从仓库 Secret `SIMULSUB_KEYSTORE_B64`（base64）还原到同一路径，`app/build.gradle.kts` 的 debug signingConfig 显式指向它（release 沿用 debug 配置）。证书 SHA-256 指纹 `7a:9a:ed:10:38:8e:3d:c8:01:c4:05:19:b1:84:e6:0b:29:32:e8:33:e3:99:0b:fc:92:5b:b5:c1:7d:de:70:22`。文件缺失时打包**报错**，而不是悄悄换一把 key——2026-10-17/18 的教训：CI 原用 `actions/cache` 缓存 `~/.android` 来固定签名，日志显示缓存每次"命中"，两次 CI 构建却签出不同证书（两天共出现三把 key，另一把是本机的 `~/.android/debug.keystore`），本地包与 CI 包互相覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。**换 key 是破坏性操作**（所有已装设备必须卸载重装，App 数据与已下载模型一起丢），只有刻意迁移才动，且要同步改这一节。本 fork 因此使用**自己的包名** `applicationId = io.github.yuwanyuan.simulsub`（在 `app/build.gradle.kts`）——与官方版并存安装互不干扰、互不覆盖；**`namespace`（代码包名）保持 `io.github.korenkrita.simulsub` 不变**：改 namespace 要动全部源文件，等于每次同步上游都制造大面积冲突，而 Manifest 的 FileProvider authority 用 `${applicationId}` 占位符，会自动跟随。数据与模型按 applicationId 隔离（换包名后手机上等于全新安装，模型要重新下载）。

## 代码与修改规范（AI 协作硬规则）

本代码库的风格特征：注释密度约 17%，每个字段与参数都有解释**为什么与实测依据**的 KDoc（例如 `Recognition.Companion` 记录"实测 すみません/そんな/まっすぐ/おじいさん 会在句首被吞"）；全库 0 个 `TODO`/`FIXME`；UI 文案为硬编码中文字符串（`strings.xml` 仅含 `app_name`）。对外行为由 `docs/architecture.md` 的数据流描述，由 `core/src/test/` 下的用例锁定。

改代码时遵循：

- **最小手术式 diff**：只改必须有差异的行。不重排格式、不"顺手重构"、不整文件重写——本项目的代码结构是流水线与状态机，重写的合并代价极高（上游更新频繁，相邻行改动即触发冲突）。
- **新增逻辑放新文件**：新 ASR 引擎、新语种、新翻译后端、新会话功能，普遍模式是"新文件 + 原文件只留注册点一两行"。`setup.sh` 的模型清单与 `Engines.kt` 的引擎注册是预期 Modify 的两个接缝。
- **改 `Segmenter.kt` / `Pipeline.kt` 必须带测试**：这两个文件是分句与调度核心，上游持续在改。行为变更需同步给出 `SegmenterTest`/`PipelineTest` 断言，改完先跑 `:core:test` 再提交。
- **不引入 `TODO`/`FIXME`**：全库当前为零，保持之。已知但未修的问题写进提交正文，不写进代码。
- **新 UI 文案**：现状是硬编码中文，保持一致；除非上游先迁移到资源文件。
- **不动 `app/build.gradle.kts` 的发布相关字段**（`versionCode`/`versionName`）与签名密钥（`keystore/fork-debug.keystore`、CI 的 `SIMULSUB_KEYSTORE_B64` secret）：换 key 意味着所有已装设备必须卸载重装，只有刻意迁移才动，且要同步改上文「签名与包名」一节。

## 提交规范

沿用上游的文风（参照 `git log upstream/main`）：

- **Subject**：`领域: 一句话改动` 的名词短语风格，如 `ASR padding: one constant for the engines and the loss repair`、`Keep captions from jumping: fixed bottom edge and smooth scroll`。
- **正文**：解释**为什么与换来的行为变化**，不逐行复述 diff；行为变化多时用列表。正文让下一次同步冲突时能快速判断"上游想干什么、本地想干什么"。
- **一个提交一件事**；本地分支同样小步提交，出了问题按提交回退。

## 上游同步

上游节奏很快（ 2026-10-10 至 10-16 共 242 个提交），**至少每周同步一次**，拖得越久冲突面越大：

```bash
git fetch upstream
git checkout main
git merge --ff-only upstream/main     # main 只做快进；若报 not possible 说明 main 被污染，需回退
git push origin main
git checkout my-mod
git rebase main                       # 本地改动重放到最新上游上，保持线性历史
# 冲突逐个解决 → ./gradlew :core:test 验证 → git push --force-with-lease
```

冲突处理要点：

- 本项目的注释紧贴代码、构造参数一行挨一行，**相邻行改动即冲突**，属正常现象，逐块手工合并。
- 判断冲突取哪边时读上游提交正文（`git log -p upstream/main -- <文件>`），上游的提交信息质量高，正文里通常写明动机与预期行为。
- 合并后必跑 `:core:test :app:testDebugUnitTest`；桌面端相关改动再跑 `tools/mac-e2e/run.sh`（仅 macOS）。
- rebase 后推送用 `--force-with-lease`，不用裸 `--force`。

## 禁止事项

- 不向上游推送（只能失败，见远端表）。
- 不在 `main` 上提交；不把 `models/`、`app/libs/` 等已 gitignore 的构建物料 `git add -f` 进库。
- 不引入网络依赖变更后再删（OkHttp 对代理与 TLS 的行为在 `docs/build.md` 有明确描述，改动需说明）。
- 不重置/改写 `main` 的历史——它是镜像，干净镜像的价值是同步永远零冲突。
- 对本文件的修改与代码改动分开提交。
