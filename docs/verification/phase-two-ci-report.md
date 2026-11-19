# 连续叙事检查点 Linux CI

日期：2026-11-20（Asia/Shanghai）。GitHub官方API原始返回和实际作业日志共同证明本次结果。

- 提交：`176e35b3c50ff99dab36073b5169797580d2300e`；分支：`codex/dynamic-narrative-engine`。
- Run：`44609270146`，attempt 1；job：`135861211461`；最终状态 `completed / success`。
- Ubuntu 26.04.1、Temurin Java 21；使用仓库的 Maven Wrapper 完成 `clean verify`。
- 实际日志：`Tests run: 443, Failures: 0, Errors: 0, Skipped: 0`，随后 `BUILD SUCCESS`。
- 指纹报告、源码/产物打包、`verify-delivery.py`及GitHub产物上传步骤均为success。
- CI生成的插件SHA-256：`58909fc354b20580b8d5d6c158500f03bb370056bc311421902c94dd5a7b7f36`。
- CI生成的ZIP SHA-256：`004aec45b94a90f864ed15d65ffc6e31d0be877bcedf497a46ab7f6b001bcc25`，404项，443项测试，交付验证 `VERIFIED`。

原始证据：[run](phase-two-ci-176e35b-run.json)、[jobs](phase-two-ci-176e35b-jobs.json)、[完整作业日志](phase-two-ci-176e35b.log)。前两次旧提交在wget下载阶段失败的记录仍以 `phase-two-ci-attempt1-*` / `attempt2-*`保留，没有将它们列为测试成功。

CI重编译产物不能自动继承本地JAR的Paper/Folia实服验收。实际部署测试对应的本地JAR仍为 `d765e0d14b9a9122e0ba1304c9c1b0d3ef844d4458295b1f4847ecb213bd943b`。本报告也不证明StarCore全栈Folia、真实战争或外部奖励端到端严格一次。
