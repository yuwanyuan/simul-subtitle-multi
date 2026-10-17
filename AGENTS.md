# 本地开发规范

本仓库是 [KorenKrita/simul-subtitle](https://github.com/KorenKrita/simul-subtitle) 的 fork（创建于 2026-10-16）。本文档面向在本仓库内工作的人与 AI 编码助手，约定**本地改动、构建、测试与上游同步**的规则；项目本身的技术文档见 [docs/README.md](docs/README.md)。本文件为本地新增（fork 的"multi"部分），不进入上游 `docs/` 索引，也不回传上游。

---

## 分支与远端

| 名称 | 指向 | 用途 |
|---|---|---|
| `main` | 跟踪 `upstream/main` | 上游的**快进镜像**，只做 `merge --ff-only`，禁止提交任何内容 |
| `my-mod` | 跟踪 `origin/my-mod` | 全部本地修改的唯一工作分支 |
| `origin` | `yuwanyuan/simul-subtitle-multi` | 唯一可推送远端 |
| `upstream` | `KorenKrita/simul-subtitle` | 只读。本账号无该仓库写权限（push 实测 403），任何改动都不回传上游 |

防误推的三层保险（2026-10-17 起）：`remote.pushDefault=origin`（裸 `git push` 永远指向 fork）+ `remote.upstream.pushurl=no-push.invalid`（**显式** `git push upstream …` 在本机直接失败，fetch/pull 不受影响）+ 上游不授予本账号写权限（真到了 GitHub 也会 403）。改本地配置时别删这两项 config。

仓库本地配置已固化：`remote.pushDefault=origin`（裸 `git push` 永远指向 fork）、`remote.upstream.pushurl=no-push.invalid`（显式推上游在本机直接失败）、`rerere.enabled=true`（复用已解决过的冲突）。上游只能 `fetch`/`merge`，永远不被 push。

## 版本锚点

改动与排查问题时以这些版本为基准；升级任何一个都意味着与上游产生大面积冲突，除非有充分理由并把理由写进提交正文：

| 项 | 版本 |
|---|---|
| Gradle wrapper | 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Kotlin Compose 插件 | 2.1.21 |
| Compose Multiplatform | 1.8.2 |
| sherpa-onnx（AAR / 桌面原生库） | 1.13.8 |
| OkHttp / JUnit | 4.12.0 / 4.13.2 |
| Android | compileSdk 35，minSdk 29（AudioPlaybackCapture 下限），仅 `arm64-v8a` |

## 构建与测试

```bash
# 1. 拉取仓库不持有构建所需物：sherpa-onnx AAR 与 77 MB 离线模型，逐文件校验字节数与 SHA-256
./setup.sh

# 2. 编译（JDK 17 + Android SDK platforms;android-35、build-tools;34.0.0；无需 NDK）
./gradlew -q :app:assembleRelease          # APK 约 90 MB：app/build/outputs/apk/release/
./gradlew -q :desktop:run                  # 桌面端源码直跑（macOS 需先 tools/mac-capture/build.sh）

# 3. 单元测试（:core 为纯 JVM，不依赖 Android SDK，是改动后最低限度的验证）
./gradlew :core:test :app:testDebugUnitTest
```

事实要点：`minSdk 29`、`versionName`/`versionCode` 定义在 `app/build.gradle.kts`（当前 1.0.7 / 27），`:desktop` 用正则从该文件读取版本号，**两处引用链动一发牵全身**；`setup.sh` 只把 **VAD 模型**复制进 `app/src/main/assets/models/`，ASR 模型不再打进 APK——手机端在 设置 › 字幕 › 语音识别模型 里按需下载（`AsrModels.kt` 的目录与校验和），桌面端和工具仍从 `./models/` 读（`.onnx` 不压缩存放）。
</think>文件比较长，我用文档级结构一次写完。现在写入根目录 `AGENTS.md`。<tool_call>Write<arg_key>content</arg_key><arg_value># 本地开发规范

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
./gradlew -q :app:assembleRelease       # 产物 app/build/outputs/apk/release/，模型不打压缩包直接随 APK 分发
./gradlew :desktop:run                  # 桌面端源码直跑（macOS 需先 tools/mac-capture/build.sh）

# 3. 单元测试——任何对 :core 的改动，这是最低限度的验证
./gradlew :core:test :app:testDebugUnitTest
```

版本号引用链：`versionName`/`versionCode` 定义在 `app/build.gradle.kts`，`:desktop` 用正则从该文件读取。**不要随手改版本号**——上游用 `Version X.Y` 格式的独立提交管理版本，本地如果也去动它会成为每次同步的冲突源；本地构建想区分时，用 tag 或分支名，不改 `versionCode`。

## fork 模式的两个已知坑（改前必读）

1. **应用内更新检查**：`AppUpdater.kt` 的清单源指向上游 `KorenKrita/simul-subtitle` 的 Release（见 `docs/build.md`）。本地修改版 APK 装到手机后，会持续把**官方版**当作"新版本"提示甚至下载覆盖。所以：本地修改版要么不要安装 release 构建（用 debug），要么先改掉 `AppUpdater` 的清单地址/禁用检查，并作为本地改动保留（改动集中在一个文件，冲突面小）。
2. **签名**：上游 Release 用原机器的 debug.keystore 签名，本地构建用的是本机 keystore。两者签名不一致意味着官方版与本地版**不能互相覆盖安装**——想并存则必须改包名（`namespace` 与 `applicationId`），注意 `CaptionService` 等组件、悬浮窗与 FileProvider 的 authority 都引用包名，需一并处理。

## 代码与修改规范（AI 协作硬规则）

本代码库的风格特征：注释密度约 17%，每个字段与参数都有解释**为什么与实测依据**的 KDoc（例如 `Recognition.Companion` 记录"实测 すみません/そんな/まっすぐ/おじいさん 会在句首被吞"）；全库 0 个 `TODO`/`FIXME`；UI 文案为硬编码中文字符串（`strings.xml` 仅含 `app_name`）。对外行为由 `docs/architecture.md` 的数据流描述，由 `core/src/test/` 下的用例锁定。

改代码时遵循：

- **最小手术式 diff**：只改必须有差异的行。不重排格式、不"顺手重构"、不整文件重写——本项目的代码结构是流水线与状态机，重写的合并代价极高（上游更新频繁，相邻行改动即触发冲突）。
- **新增逻辑放新文件**：新 ASR 引擎、新语种、新翻译后端、新会话功能，普遍模式是"新文件 + 原文件只留注册点一两行"。`setup.sh` 的模型清单与 `Engines.kt` 的引擎注册是预期 Modify 的两个接缝。
- **改 `Segmenter.kt` / `Pipeline.kt` 必须带测试**：这两个文件是分句与调度核心，上游持续在改。行为变更需同步给出 `SegmenterTest`/`PipelineTest` 断言，改完先跑 `:core:test` 再提交。
- **不引入 `TODO`/`FIXME`**：全库当前为零，保持之。已知但未修的问题写进提交正文，不写进代码。
- **新 UI 文案**：现状是硬编码中文，保持一致；除非上游先迁移到资源文件。
- **不动 `app/build.gradle.kts` 的发布相关字段**（`versionCode`/`versionName`/签名），见上文版本号引用链一节。

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
