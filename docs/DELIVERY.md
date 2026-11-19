# 第一阶段交付与复验

本仓库的第一阶段包含实际插件工程、完整剧情资源、API 文档、测试源代码、构建结果和真实服务器证据。完整目标尚未完成的工作单独记录在 [REMAINING-WORK.md](REMAINING-WORK.md)；候选补丁和旧构建证据均不替代当前产物验收。

## 从源码构建

使用 Java 21，在仓库根目录执行：

```text
.\mvnw.cmd -B -ntp clean verify > verify-build.log 2>&1
python tools/verification_report.py verify-build.log
python tools/package-delivery.py
python tools/verify-delivery.py
```

Linux/macOS 的第一条命令改为 `./mvnw -B -ntp clean verify > verify-build.log 2>&1`。Maven Wrapper 固定 Maven 发行版；首次构建需要访问依赖仓库。后续三条命令使用 Python 3.9 或更新版本的标准库。

构建会生成插件 JAR 和 sources JAR。报告器验证实际 Maven 成功日志、Surefire XML、Java 21 字节码、SQLite 原生运行库，以及源码、资源、嵌入文档与 JAR 的一致性。不要在验证后修改生产源码或资源并继续分发旧 JAR。

## 交付 ZIP

`target/starchronicle-0.1.0-SNAPSHOT-delivery.zip` 包含：

- `StarChronicle/`：工程源码、测试、配置、三条主剧情、《旧都档案》后续、八条身份线、文档、审计证据、验证工具和上游候选补丁。
- `artifacts/`：经过校验的插件 JAR 与 sources JAR。
- `build-reports/`：本次实际 Surefire XML／文本报告及 JaCoCo XML／CSV。
- `MANIFEST-SHA256.json`：逐文件校验值、两份 JAR 的校验值及实际测试数量。

ZIP 不包含 Git 元数据、运行世界、服务器数据库、缓存或第三方服务器 JAR。Unix Maven Wrapper 保留可执行权限。

`verify-delivery.py` 不解压或执行包内代码。它检查全部文件的清单覆盖和校验值、ZIP 路径与重复项、Wrapper 执行位，以及原始测试结果与清单计数。清单用于发现损坏；验证转交包的来源时，还应对照独立给出的整个 ZIP 的 SHA-256。

## 安装与现有数据

正常停服后放置插件 JAR，再启动验证。默认 `require-starcore: true` 要求可用的真实 StarCore 服务；显式独立模式只提供本插件自己的规则、历史和展示能力。完整 StarCore/Folia 的限制见 [FOLIA-COMPATIBILITY.md](FOLIA-COMPATIBILITY.md)。

升级已有服务器前，应在正常停服后备份整个 `plugins/StarChronicle/`。当前 schema 4 保留 V1/V2/V3 原事件、实例和回执，在既有选择日志与因果链基础上同事务归档真实有效定义，旧选择导入不伪造原投票时间。它不能补造旧版从未保存的触发定义或原生会话令牌，也不会自动补排新安装的后续。恢复旧备份时，应连同其对应插件版本恢复，不要把旧代码直接打开新 schema 作为回滚方法。参见 [连续叙事与迁移](CONTINUOUS-NARRATIVE.md)。

真实服证据按 JAR SHA-256 对应。重新编译产生的新指纹，即使源码相同，也不会自动继承旧 JAR 的实服验收结论；本地构建测试与部署验收分别列在 [TEST-REPORT.md](TEST-REPORT.md)。

上一检查点 `d765e0d1…943b` JAR 已完成 443 项 Maven 测试、[Paper + StarCore 31 项](verification/phase-two-paper-report.md)、[Folia standalone 56 项](verification/phase-two-folia-report.md)及[实际 V2 数据库迁移 11 项](verification/phase-two-v2-upgrade-report.md)。两套实服均实际完成三次启动及正常退出；Folia standalone 不代表 StarCore 全栈 Folia 兼容。原始失败、人工时序观察与复验边界随报告保留。

源码检查点 `176e35b3c50ff99dab36073b5169797580d2300e` 的 GitHub Linux CI（run `44609270146`）已完成且结论为 `success`：443项测试无失败、错误或跳过，构建、指纹报告、交付包验证和产物上传均成功。官方 API 与完整作业日志见 [CI报告](verification/phase-two-ci-report.md)。CI重编译JAR的指纹为 `58909fc3…7f36`，它不是上述完成实服验收的本地 `d765e0d1…943b` JAR。

更早基线 `aa278396aa98245eff8c01d157799aef3b561118` 的Linux成功证据也继续保留：[run](verification/github-ci-aa27839-run.json)、[jobs](verification/github-ci-aa27839-jobs.json)。旧证据不覆盖当前代码。

## 当前版本目录迭代

新JAR `bfa80b9ca62b1f28fdd37a2f1d7244b9b94cb8c34814273fda742ff8c6f28154` 已完成514项 `clean verify` 测试，包含真实旧库形状的completed保护、退休版本引用、显式历史评估、权限、并发及实际YAML分支。对应旧存档升级和新Paper/Folia验收正在执行，不能将上面的旧JAR实服结果移给它。最终对应关系以 [TEST-REPORT.md](TEST-REPORT.md) 为准。上一完整交付仍保存在 `run/delivery-checkpoints/phase2-d765e0d1-6652853/`。
