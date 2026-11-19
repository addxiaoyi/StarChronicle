# 精确版本目录迭代 Linux CI

提交 `cc529be89708097d1ebc95cfb8b52fa37be742fc`，GitHub run `44615494372`，job `135882932903`，最终状态 `completed / success`。实际作业日志记录 `Tests run: 514, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`；指纹报告、完整交付打包、交付验证和产物上传步骤均为success。

原始证据：[run](phase-three-ci-cc529be-run.json)、[jobs](phase-three-ci-cc529be-jobs.json)、[完整日志](phase-three-ci-cc529be.log)。

CI重编译插件SHA-256为 `b57193279e23d42bb82defda3aab1ea0d2352b7806be060cb1c6285d459b05ac`，CI交付ZIP为 `63778181edda48db1269d51828741b0f11a3d74c31a7f73d21ae27a522ae067c`，440项，交付验证 `VERIFIED`。

本地冻结JAR仍为 `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154`。它已完成同一组514项完整构建测试，三套真实V2/V3存档的生产迁移也与此本地指纹对应。CI产物不继承该JAR的数据库或服务器验收，旧d765实服报告也不覆盖新JAR；新多人实服验收独立进行。
