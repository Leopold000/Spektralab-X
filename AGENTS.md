# 项目工作记忆（AGENTS.md）

给在本仓库工作的 AI 助手/维护者看的流程记忆。用户可见的规则仍以
[`CONTRIBUTING.md`](CONTRIBUTING.md) 为准；这里只记录“怎么做”。

不要在本文件、提交信息或文档里写真实姓名、邮箱、电话、本地绝对路径。

## 1. 仓库与分支

- 日常工作分支：`tile-bench`。`origin` 是上游仓库，`fork` 是个人 fork
  （`git remote -v` 可看当前地址）。
- 引擎改动只能以补丁进 `patches/`；App/Gradle/文档改动直接提交。
- 推到 `main` 会触发 release 工作流（需要 `KEYSTORE_B64` 等 secrets）。
  在 fork 上不要推 `main`；测试包一律用 `workflow_dispatch` 指定分支。

## 2. 测试包（debug）构建流程

本机通常没有 Android SDK/JDK，全部走 GitHub Actions。

1. 提交并推分支：
   ```sh
   git push fork tile-bench
   ```
2. 手动触发（不要推 `main`）：
   ```sh
   gh workflow run android.yml --repo <fork>/Spektralab-X --ref tile-bench
   ```
3. 取 run id 并等待：
   ```sh
   gh run list --repo <fork>/Spektralab-X --branch tile-bench --limit 2
   gh run watch <run-id> --repo <fork>/Spektralab-X --exit-status --interval 20
   ```
4. 取 artifact 名并下载：
   ```sh
   gh api repos/<fork>/Spektralab-X/actions/runs/<run-id>/artifacts \
     --jq '.artifacts[].name'
   gh run download <run-id> --repo <fork>/Spektralab-X \
     --name Spektralab-X-<version>-run<N> --dir <目录>
   ```
5. 核对 artifact 里的 `SHA256SUMS`；debug 签名每次构建都会变，
   **装之前先卸载旧版**，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
6. 诊断：App 内 **设置 → 诊断信息 → 复制**（不含文件名/路径）。

网络注意：

- GitHub HTTPS 不通时给 git/gh/curl 设
  `HTTPS_PROXY=http://127.0.0.1:7897`（本机代理端口）。
- SSH 推送间歇超时；可用 HTTPS + proxy 推 fork。
- PowerShell 没有 heredoc：提交信息写临时文件，再 `git commit -F <file>`。
- `gh` 不在 PATH 时用本机安装路径（`where.exe gh` 查一次即可）。

CI 约 7–10 分钟：`Fetch upstream` → 原生回归 → `Build` → artifact。
`Fetch upstream` 失败通常意味着补丁不能应用到真实上游树。

## 3. 引擎补丁流程

- 上游固定 commit 在 `scripts/fetch-upstream.sh`；CI 按
  `patches/*.patch` **文件名顺序** 执行 `patch -p1 --forward --batch`。
- 新补丁：编号递增（`0054-...`）、开头写中文/英文说明、只改 `engine/`、
  LF、无 BOM。补丁集合的哈希是 CI 上游缓存的 key。
- 本地通常没有 `patch.exe`；验证方式：
  - 重建临时引擎树（上游 + 现有补丁），`git apply --check` 自检新补丁；
  - 基线有少量漂移时，用按上下文行偏移/模糊搜索的 Node 脚本应用，
    模拟 `patch` 的 offset/fuzz；
  - 生成补丁前先 `git apply --check` 到“干净基线 + 前一个补丁”的树。
- 硬约束：整幅（非窗口）路径必须逐字节不变；新字段默认 0，旧调用方
  行为不变；Android 单存储缓冲上限 128 MiB 是很多分块设计的前提。

窗口/分块相关补丁（2026-11）：

- `0054-overscan-window.patch`：片边画布的窗口渲染
  （`spk_set_render_window`、`spk_overscan_layout_at`）。
- `0055-window-effects.patch`：窗口里的日期、颗粒/眩光字段全局像素索引；
  窗口不再要求片边。

## 4. 全分辨率导出（分块）现状

- 入口：**设置 → 导出 → 全分辨率导出（分块）**，默认关。
- 关闭：保存菜单的「高质量导出」走正常队列/工作尺寸渲染。
- 打开：同一项变「全分辨率导出（分块）」，走整幅测光 + 逐块窗口渲染
  到原始像素，保留相纸边框，输出 JPEG 98 4:4:4（8 位）。
- 已支持：片边 135、日期、颗粒、光晕/眩光、暗角/蒙版/灰尘、
  HDR（Ultra HDR gain map）、16 位 SDR 输入。
- 不支持：RAW（整幅 RAW 超过 128 MiB 单缓冲，需要原生分块解码）、
  旋转/翻转、非片边裁切、无胶片。
- 分块参数：块 `3072×2730`，重叠 160 px（开光晕时 192 px），
  源图 bbox 由片门四角仿射反算。
- 真机验证重点：齿孔/边缘字/日期跨拼接线、颗粒与眩光是否成块、
  暗角是否以整幅为中心、蒙版/灰尘位置、HDR 增益图对齐。

## 5. 正式发布（release）

维护者流程（与 `CONTRIBUTING.md` 一致）：

1. `VERSION` 写新的显示版本；`VERSION_CODE` 每次发布都递增。
2. `CHANGELOG.md` 加对应章节；fastlane 的中英文发布说明按 versionCode 命名。
3. 推 `main`，CI 用 release secrets 签名并发布到上游仓库的 Releases。

调试包不要走这条；没有 `KEYSTORE_B64` 时 release job 会失败。

## 6. 设备与限制（2026-11）

- 测试机：Xiaomi 24117RK2CC / Android 16 (API 36) / SM8650 / Adreno 750。
- `maxStorageBufferRange` 128 MiB；`maxPixels` 8,388,608；
  working edge 3072；RAM 11 GB；Java heap 512 MB。
- 高负载导出会热降频（热余量可降到 0.1–0.2），偶发单块 30–70 s；
  这通常是热/内存压力，不是拼接错误。
