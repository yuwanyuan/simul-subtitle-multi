# 安卓模拟器：怎么跑、怎么点、怎么取证

本机在没有真机时用它验证界面与识别链路。**本文只讲操作，不讲原理**——踩过的坑都写在对应的步骤旁边，照着抄命令即可。这份是 fork 的本地文档（同 `AGENTS.md`），不进入上游 `docs/` 索引。

## 0. 前提：工具在哪

```bash
# Android SDK 实际在 E 盘，C:\Users\<你>\AppData\Local\Android\Sdk 是指向它的链接
# adb / emulator 都不在 PATH 里，每条命令都用全路径（或先设变量）
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
EMU="$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe"
```

`local.properties` 里 `sdk.dir=C:/Users/yuwanyuan/AppData/Local/Android/Sdk` 不用改：链接失效前一直可用（Java properties 必须用正斜杠）。

## 1. 启停与等待就绪

```bash
"$EMU" -list-avds                 # t1 / t2 / testavd / vrootavd
"$EMU" -avd testavd -no-snapshot-save       # 测试用；后台跑
"$EMU" -avd testavd -no-snapshot-load       # 模拟器自己崩过之后冷启动
```

| AVD | 镜像 | 分辨率 | 备注 |
|---|---|---|---|
| `testavd` | android-36.1 google_apis_**playstore** x86_64 | 1080×2340 | 有 Chrome/YouTube；**Play 镜像不能 `adb root`** |
| `t1` | android-36.1 google_apis x86_64 | 1080×2400 | 可 root，屏幕更高 |
| `t2` | android-36.1 google_apis x86_64 | 1080×2340 | 可 root |
| `vrootavd` | android-36.1 google_apis x86_64 | 1080×2340 | 软件渲染 |

等就绪（启动约 30–60 秒，别用固定 sleep）：

```bash
for i in $(seq 1 20); do d=$("$ADB" devices | tail -n +2 | grep -v '^$'); [ -n "$d" ] && break; sleep 6; done
"$ADB" shell getprop sys.boot_completed      # 必须回 1
```

**多设备时一律带 `-s`**：本机可能同时挂着 `emulator-5554` 与用户真机（`127.0.0.1:16384` 之类），不带 `-s` 的 adb 命令会整条失败（不是部分生效）。写法：`ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe -s emulator-5554"`。

## 2. 看一眼：截图

```bash
"$ADB" exec-out screencap -p > shot.png
```

- PNG 就是**设备分辨率**（`file shot.png` 可验，1080×2340）。
- 但读图工具会把图缩到约 921×1997（比例 ≈1.1725）。**按缩略图目测的坐标要乘回 1080/921**，否则纵向偏 1% 左右——这一步差几个像素就能点到按钮外沿。
- **每次点击前重截一张图，按那一张的坐标点**：本 App 首页有 800ms 的 ticker 重绘，页面会自己变位。

## 3. 点、滑、打字

```bash
"$ADB" shell input tap 540 1283                 # 坐标是设备像素
"$ADB" shell input swipe 540 2000 540 900 300   # 起→终，时长 ms
"$ADB" shell input text "hello"                 # 仅 ASCII；中文要输入法或改 prefs（见 §6）
"$ADB" shell input keyevent KEYCODE_BACK
```

- **`uiautomator dump` 在本 App 上基本不能用**：首页 ticker 永不 idle，报 `ERROR: could not get idle state`，而且失败时 `cat` 出来的是**上一次的旧 dump**——先看 dump 命令自己的输出，别静默 grep 旧文件。设置页偶尔成功，可拿来取 `bounds`。
- **BACK 键会退栈**：收键盘前先确认 `dumpsys input_method | grep mInputShown=true`；IME 没开时 BACK 直接退到上一个页面，甚至退出应用，后续点击会落到别的 App 上。

## 4. 装包与构建（本机特有）

本机 APK 只带 `arm64-v8a`，而模拟器是 x86_64——onnxruntime 会在 `dlopen` 静态初始化阶段 SIGSEGV（崩在 linker64，还没进应用代码）。这是模拟器 ARM 转译的缺陷，**不是 App bug，别去追**。

```bash
# 1) 临时把 abiFilters 改成 x86_64（AAR 自带 x86_64 原生库）
#    app/build.gradle.kts: ndk { abiFilters += "x86_64" }
./gradlew -q :app:assembleDebug

# 2) 装包：路径要 Windows 形式，且关掉 Git Bash 的路径转换
MSYS_NO_PATHCONV=1 "$ADB" install -r "D:\...\app\build\outputs\apk\debug\app-debug.apk"

# 3) 测完恢复
git diff app/build.gradle.kts        # 确认只有那一行
git checkout app/build.gradle.kts    # 仅当该文件没有别的未提交改动
```

- fork 包名 `io.github.yuwanyuan.simulsub`，与上游包 `io.github.korenkrita.simulsub` 在模拟器上并存，互不覆盖。
- **收尾别一律 `git checkout -- <file>`**：同一个文件里可能还有未提交的真实改动（版本号等），checkout 会一起还原。要么先确认 diff 只有那一行，要么用 `sed` 精确改回。

## 5. Git Bash 的路径转换（最高频的坑）

Git Bash 会把命令里的 `/sdcard/...` 悄悄改写成 `C:\Program Files\Git\sdcard\...`（"成功"推到本机磁盘）。

```bash
# 推设备文件：必须 MSYS_NO_PATHCONV=1，且本地路径给 Windows 形式
MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w /tmp/x.bin)" /sdcard/Download/x.bin
MSYS_NO_PATHCONV=1 "$ADB" shell ls /sdcard/Download/
```

## 6. 不走界面也能配好 App（force-stop + run-as）

debug 包可用 `run-as` 直接改偏好，省掉大量点击。**改之前先 force-stop**，否则运行时会把旧值写回。

```bash
"$ADB" shell am force-stop io.github.yuwanyuan.simulsub
MSYS_NO_PATHCONV=1 "$ADB" shell "run-as io.github.yuwanyuan.simulsub cat /data/data/io.github.yuwanyuan.simulsub/shared_prefs/jpsub.xml"
```

整份重写（从 stdin 灌入，推荐；`prefs/jpsub.xml` 里常用键）：

```bash
MSYS_NO_PATHCONV=1 "$ADB" shell "run-as io.github.yuwanyuan.simulsub sh -c 'cat > /data/data/io.github.yuwanyuan.simulsub/shared_prefs/jpsub.xml'" < local.xml
```

| 键 | 含义 |
|---|---|
| `asrModel` | 选中的识别模型 id（`builtin` / `hf-korean` / `custom` …） |
| `byokUrl` / `byokModel` / `byokUserAgent` | 首个 LLM API 配置（无 id 后缀），明文。免 Key 的 B 站端点：`https://index-translate.bilibili.com/v1` + `Index-Translate-35B-A3B` + `Index-Translate-Client/1.0` |
| `byokKey` / `jevKey:*` / `byokHeaders` | 用 Android Keystore 封装，**别手写** |
| `jevOn` / `jevProvider` / `jevUrl` / `jevModel` | 可选的 Jev 在线判断；占位值时底栏显示「Jev 网络连不上」，**不影响**识别与翻译 |

模型与清单不用走下载：

```bash
# 预置"已下载"：四个文件推到 asr-models/<模型 id>/，尺寸对上即被认作已下载
MSYS_NO_PATHCONV=1 "$ADB" push <file> /sdcard/Android/data/io.github.yuwanyuan.simulsub/files/asr-models/hf-korean/

# 预置清单缓存：直接顶掉 App 读的 asr-catalog.json（mtime 新 → 一小时内的自动刷新不覆盖它）
MSYS_NO_PATHCONV=1 "$ADB" push models.json /sdcard/Android/data/io.github.yuwanyuan.simulsub/files/asr-catalog.json
```

**预置缓存是"清单改完但主源还没更新"时的唯一办法**：Play 镜像不能 `adb root`，没法改 `/etc/hosts` 屏蔽 gitee 让 App 走 GitHub 回退。别点页面上的「刷新清单」——那会去拉线上旧版。

## 7. 取证：logcat

```bash
"$ADB" logcat -c                              # 先清，避免看到上一次的
"$ADB" logcat -d -s jpsub | tail -20          # App 的阶段日志（tag jpsub）
```

常见行：

```
capture: frames=… rms=0.0000 vadMax=0.01 hal=true      # 音频进来了但全静音
seg=44.7F audio=3520 wait=2593 asrQ=11 asr=73 mtQ=3 mt=1 mtDone=2681 ja=17 zh=11
shown seg=44.7d update=2685ms                          # 字幕已显示
```

`rms=0.0000` 就说明音源没进捕获（见下一节），而不是识别坏了。

## 8. 测"播放捕获"要喂什么音频

字幕只听 **其他 App 播放的声音**（AudioPlaybackCapture），所以音源必须允许捕获：

| 音源 | 结果 |
|---|---|
| Chrome 打开的 wav | **不工作**：浏览器音频对捕获不可见，播完 rms 仍是 0.0000 |
| `app_process` + `MediaPlayer` | **不工作**：路径版与 FileDescriptor 版都会走到 `FileUtils.convertToModernFd → Context.getPackageManager()` 空指针（app_process 没有 system Context） |
| `app_process` + **AudioTrack 直写 PCM** | **可用**，实测 rms=0.15、vadMax=1.00，识别/翻译/字幕全通 |

可用那条的完整配方（自己解析 RIFF 头取 `data` 段，`USAGE_MEDIA` + `ALLOW_CAPTURE_BY_ALL`，循环喂以便 VAD 出分段边界）：

```bash
# 1) 写 Play.java（AudioTrack + FileInputStream 读 wav），编译成 dex
cd <工作目录>
javac -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$LOCALAPPDATA/Android/Sdk/platforms/android-36/android.jar" Play.java
"$LOCALAPPDATA/Android/Sdk/build-tools/36.1.0/d8.bat" --release --min-api 29 --output . Play.class

# 2) 推 dex 与音频（wav 必须放 /sdcard：mediaserver 读不了 /data/local/tmp）
MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w classes.dex)" /data/local/tmp/play.dex
MSYS_NO_PATHCONV=1 "$ADB" push "$(cygpath -w 0.wav)" /sdcard/Download/0.wav

# 3) 后台播放（app_process 以 shell 身份跑）
MSYS_NO_PATHCONV=1 "$ADB" shell "CLASSPATH=/data/local/tmp/play.dex app_process /data/local/tmp Play /sdcard/Download/0.wav"

# 4) 停：kill 掉那个 app_process
pid=$("$ADB" shell "ps -A -o PID,ARGS | grep 'local/tmp Play' | grep -v grep" | awk '{print $1}'); "$ADB" shell kill $pid
```

`javac` 务必带 `-encoding UTF-8`（源码里有中文注释时，默认 GBK 会报"不可映射字符"）。

## 9. 完整流程示例（这次实测的路径）

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe -s emulator-5554"
# 1) 启动模拟器 → 等 sys.boot_completed=1
# 2) 改 abiFilters 为 x86_64 → assembleDebug → install -r
# 3) 预置清单缓存 + 模型四件套（§6）
# 4) 写 jpsub.xml：asrModel=<id> + 免 Key 的翻译端点（§6）
# 5) am start 主界面 → 截图 → 点「模型」行 → 点「使用」 → BACK
# 6) 点「开始字幕」（首页底部；若按钮是「去填写」说明 byokReady 不成立）
# 7) 用 AudioTrack 起音频（§8）→ 截图看字幕 + logcat 看 rms/seg
# 8) 还原 abiFilters，git status 确认干净
```

权限可以先用命令授（省弹窗点击）：

```bash
"$ADB" shell appops set io.github.yuwanyuan.simulsub PROJECT_MEDIA allow       # 听播放（还需 RECORD_AUDIO）
"$ADB" shell appops set io.github.yuwanyuan.simulsub SYSTEM_ALERT_WINDOW allow  # 悬浮字幕
"$ADB" shell pm grant io.github.yuwanyuan.simulsub android.permission.POST_NOTIFICATIONS
"$ADB" shell pm grant io.github.yuwanyuan.simulsub android.permission.RECORD_AUDIO
```

`PROJECT_MEDIA` 之外，App 自己会弹录音权限（播放捕获需要 `RECORD_AUDIO`）——第一点「去允许」就是这个弹窗，选 **While using the app**。

## 10. 黑屏 = 宿主显卡驱动崩了，不是 App bug

判据：`dumpsys activity top` 的视图树完整，但 `dumpsys window windows` 里连系统「设置」都 `Surface: shown=false / mLastHidden=true`，同时模拟器弹 NVIDIA 驱动崩溃提示。

修法：停 emulator/qemu 进程 → 清 `~/.android/avd/<avd>.avd/*.lock` → 把 `config.ini` 的 `hw.gpu.mode` 由 `auto` 改成 `swiftshader_indirect`（软件渲染，原值先备份）→ 冷启动（`-no-snapshot-load`）。命令行参数 `-gpu swiftshader_indirect` 在现版本上无效，必须改 config.ini。代价是更慢。

## 11. 内存

AVD 只给了 2GB。339MB 的多语种模型属于极限操作（load + 预热要 6–8 秒）；测试大会话前先 force-stop 掉不相关的 App。
