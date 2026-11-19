# Maven Wrapper 下载与校验复验

日期：2026-11-20（Asia/Shanghai）。本报告只覆盖构建启动脚本，不替代 Java clean verify 或实服验收。

- 最终复验：7/7 PASS，原始结果见 `phase-two-wrapper-summary.json` 及四份同前缀日志。
- Git Bash / Windows、Java 21；每项创建全新独立 MAVEN_USER_HOME，未清理用户缓存。HTTPS 正例访问配置的 Maven Central 地址并实际运行 Maven 3.9.11。
- SHA-512 与官方发行校验文件一致，SHA-256 固定为 `0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb`。错误校验值测试退出1，未安装发行版。
- wget 成功路径用只模拟传输接口的本地 shim，不能称为真实 wget 网络下载；Java fallback 用真实 Java 读取已验证的发行包，并覆盖模拟失败留下的 partial 文件。
- `phase-two-wrapper-fixture-attempt1.json` 保留首次工具测试失败：Windows C:/ 路径被放入 Bash PATH，冒号令 shim 未生效。Maven 实际启动成功，但 fallback 断言失败；仅修正测试工具的 PATH 表达后重跑，未把失败改成通过。
- 原 GitHub CI run 44602519234 两次均在 wget 下载时失败、未进入编译测试，原始 run/jobs/log 已以 `phase-two-ci-attempt1-*` / `attempt2-*` 保留。新的 Linux CI 结论须另行读取真实运行结果。

复验命令：

```text
python tools/verify-wrapper-bootstrap.py --java-home JAVA_21_HOME --archive PATH_TO_OFFICIAL_ZIP
```

相邻 `.sha512` 文件必须来自官方；脚本拒绝错误发行包。已有 Maven 缓存会沿用，新增校验值不能追溯认证旧缓存。启用 ZIP 校验时缺少 unzip 将明确拒绝并提示安装，不会把 tar.gz 当成同一字节包。
