# 发布检查清单

本文将仓库已有的 Gradle 发布能力整理成可执行流程。任何发布凭据都不得进入仓库。

## 1. 准备版本

- 确定版本号并更新根工程的 `allprojects.version`。
- 将 `CHANGELOG.md` 中相关内容从 `Unreleased` 移入带日期的版本章节，再创建空的
  `Unreleased` 章节。
- 更新 README 的构建/兼容表和远程依赖示例；Central 尚未完成发布时保留源码/工作区声明。
- 为所有源码、二进制、行为或持久化数据的不兼容变更补充迁移步骤。
- 运行 `./gradlew apiCheck`。公开 API 有意变化时，先审查差异，再运行
  `./gradlew apiDump`，并将 `api/*.api` 与实现一起提交。

## 2. 验证源码和设备

运行确定性的 JVM 与 Android 构建门禁：

```bash
./gradlew apiCheck test lintRelease assembleRelease :example:assembleDebug
```

在没有初始化/权限弹窗遮挡测试 Activity 的干净专用设备或 AVD 上运行 Compose 设备测试：

```bash
./gradlew :kanvas-compose:connectedDebugAndroidTest
```

在发布说明中记录 Android 版本、API、ABI、设备/AVD 名称和测试数量。当前本地基线为
Android 16 / API 36 ARM64。库的 `minSdk` 不能替代实际测试矩阵。

渲染或交互热路径发生变化时，在配置的真机矩阵上运行严格性能工作流。只有全部严格门槛通过，
才可以宣称版本稳定达到 120fps。

## 3. 本地验证发布物

```bash
./gradlew publishAllPublicationsToWorkspaceRepository verifyPublishedPomScopes
```

检查 `build/maven-repository/com/zhumeng/`，确认每个库模块都包含：

- release AAR 或 JAR；
- Gradle 元数据和 POM；
- Sources 与 Dokka Javadoc JAR；
- 公开签名依赖使用 compile scope；
- 正确的 group、artifact、version、License、SCM 和开发者信息。

上传前创建一个只依赖工作区仓库的小型消费工程，编译一次 `KanvasChart` 接入。

只有全部 Dokka Javadoc 任务完成，才算发布物验证成功。如果某个 Dokka 任务长时间没有输出，
应在 CI 或其他非受限环境用 `--stacktrace --info` 单独重跑该模块的
`dokkaGeneratePublicationJavadoc`。不能只因 POM 已生成就判定发布成功。

## 4. 签名和上传

通过环境变量或等价 Gradle 属性提供：

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

构建通过 Sonatype Portal OSSRH Staging API 上传签名发布物。上传后在 Portal 审查 staging，
再由发布人员手动完成最终确认。在 Central 能下载该版本的所有模块之前，不得把远程依赖示例
写入 README。

## 5. 发布版本元数据

- 从已验证提交创建签名或带注释的 `v<version>` tag。
- 使用对应 CHANGELOG 章节发布 Release Notes，明确设备/性能证据和 API/迁移影响。
- 在干净消费工程中验证 README 的每个依赖示例。
- 更新 `CHANGELOG.md` 链接，使 `Unreleased` 比较新 tag 与 `HEAD`。

## 失败或部分发布

部分对消费者可见的版本不得重复使用。Portal 仍允许时关闭或删除 staging；已经公开后应发布
修复补丁版本，并在 CHANGELOG 记录受影响坐标和规避方式。不得覆盖或静默替换已发布制品。
