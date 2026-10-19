# 构建说明

本项目不使用 Gradle。`build.ps1` 直接调用 Android SDK 工具：

```text
aapt2 -> javac -> d8 -> zipalign -> apksigner
```

## 环境

- Windows 10/11
- PowerShell 5.1 或 PowerShell 7
- JDK 17，`javac` 在 PATH
- Android SDK：
  - `build-tools;35.0.0`（或包含 `aapt2`、`d8`、`zipalign`、`apksigner` 的版本）
  - `platforms;android-35` 的 `android.jar`

默认查找：

```text
..\.android-sdk
```

SDK 在别处时，修改 `build.ps1` 顶部：

```powershell
$sdk = Join-Path $root "..\.android-sdk"
```

## 构建

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

输出：

```text
build/openbuds-battery-2.0.1.apk
```

## 签名

脚本优先使用：

```text
keystore/openbuds-dev.jks
```

仓库**不包含**这个文件。首次在干净目录构建时，脚本会用 `keytool` 创建一个本地开发签名；密码默认是 `openbuds-dev-only`，可用环境变量 `OPENBUDS_KEYSTORE_PASSWORD` 覆盖。这个签名只用于本地调试，不要用于正式发布。

正式发布请自己生成密钥，并修改：

```powershell
$keystore = ...
$alias = ...
```

不要把 `.jks`、密码或 `keystore/` 提交到 Git。

## 常见问题

### `Android SDK not found`

说明 `..\.android-sdk` 不存在，或缺少 `platforms/android-35/android.jar`。改 `$sdk` 路径。

### `aapt2` / `d8` / `apksigner` 找不到

安装 Android SDK Build-Tools，或把 `$buildTools` 指向实际目录。

### `classes.jar: Access denied`

上一次构建的 `build/` 被占用。关闭正在使用 APK 的进程，删除 `build/` 后重试。

### 安装时提示签名冲突

旧包名 `com.ruoshui.batterydock` 和新包名 `com.openbuds.batterydock` 是不同应用。不能覆盖安装，先卸载旧版，或保留两个应用。

### GitHub 下载慢

见 README 的「下载困难说明」。优先使用 Releases；源码构建需要完整 SDK，不适合普通安装用户。
