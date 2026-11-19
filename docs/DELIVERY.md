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

- `StarChronicle/`：工程源码、测试、配置、三条主剧情、八条身份线、文档、审计证据、验证工具和上游候选补丁。
- `artifacts/`：经过校验的插件 JAR 与 sources JAR。
- `build-reports/`：本次实际 Surefire XML／文本报告及 JaCoCo XML／CSV。
- `MANIFEST-SHA256.json`：逐文件校验值、两份 JAR 的校验值及实际测试数量。

ZIP 不包含 Git 元数据、运行世界、服务器数据库、缓存或第三方服务器 JAR。Unix Maven Wrapper 保留可执行权限。

`verify-delivery.py` 不解压或执行包内代码。它检查全部文件的清单覆盖和校验值、ZIP 路径与重复项、Wrapper 执行位，以及原始测试结果与清单计数。清单用于发现损坏；验证转交包的来源时，还应对照独立给出的整个 ZIP 的 SHA-256。

## 安装与现有数据

正常停服后放置插件 JAR，再启动验证。默认 `require-starcore: true` 要求可用的真实 StarCore 服务；显式独立模式只提供本插件自己的规则、历史和展示能力。完整 StarCore/Folia 的限制见 [FOLIA-COMPATIBILITY.md](FOLIA-COMPATIBILITY.md)。

升级已有服务器前，应在正常停服后备份整个 `plugins/StarChronicle/`。当前 schema 2 会保留原事件、实例和回执，并迁移新增的离线触发与原生场景 journal；它不能补造旧版从未保存的触发定义或原生会话令牌。恢复旧备份时，应连同其对应插件版本恢复，不要把旧代码直接打开新 schema 作为回滚方法。

真实服证据按 JAR SHA-256 对应。重新编译产生的新指纹，即使源码相同，也不会自动继承旧 JAR 的实服验收结论；本地构建测试与部署验收分别列在 [TEST-REPORT.md](TEST-REPORT.md)。
