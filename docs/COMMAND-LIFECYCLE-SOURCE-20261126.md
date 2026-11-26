# Essentials 命令生命周期：源码验证 2026-11-26

原实现停用模块后仍保留命令执行器及补全器，捕获的旧处理器仍能修改旧家园服务、读取旧补全数据；裸命令名冲突时还会覆盖其他插件的命令。本次增加原插件身份核验、每次启用的独立命令入口及按槽位恢复，并完整处理部分启用／清理失败。

| 构件 | SHA-256 |
| --- | --- |
| StarCore | `b1a94dcad11cf5b82f1029ffa6c54a920781cd22ae5fb84ce8d5bef6f1225225` |
| Chronicle，主构件保持 | `1c7b8b010c9d330c15b0882753d54095fb906e42caedb926a81055d643cd78ce` |
| Chronicle 源码 JAR | `8a9634b60a7b256e228e5c96b62eb525824083d83cffb590828934d93dd2547c` |
| 证据 ZIP | `7fc9ec5e9e1105ca4f90d43c8a10723cd60f63282ba6501ad46570d3dba9b218` |

基线 Chronicle `24c17a4`、StarCore `b2cb58f`。[证据 ZIP](verification/command-lifecycle-source-20261126-evidence.zip) **74 文件**；[机器摘要](verification/command-lifecycle-source-20261126-summary.json)。包含源码、旧实现失败、最终 XML、构建／SBOM／API 与类字节核对、工具，以及上一传送检查点 CI #177 的成功日志和交付 Git 核对。没有启动 Minecraft。

## 行为与边界

- 优先使用插件命名空间查找，只有 PluginCommand 属于相同插件实例才注册；裸名仅作为相同所有者的兼容回退。别名指向同一对象时只绑定一次。
- 每次启用安装全新的执行／补全入口，所有服务、监听器、计时器完成初始化后才激活。停用首先关闭所有旧入口并释放其服务引用。重新启用不能复活旧回调；不可用时命令提示模块当前不可用，补全返回空列表。
- 记录原执行器和补全器，只在相应槽位仍是本次安装的确切对象时恢复；别的模块后来接管的槽位保留。命令自身及命名空间／别名继续注册，不删除服务器命令映射。
- 在设置处理器前登记清理记录，注册中途报错同样恢复。一个命令恢复失败不阻断其补全器、其余命令、调度器取消、监听移除或数据写入器关闭。第一异常保留，后续异常作为 suppressed；不能恢复的入口仍失效，不表示物理恢复已成功。

生命周期和命令映射修改仍要求服务器正常生命周期上下文；调用玩家命令仍要求玩家 owner。停用前已准入的命令允许完成，包括业务副作用与异步续办，本次没有等待全部在途工作或承诺回滚。GUI 状态、其他模块及完整热重载继续待办。

## 验证

| 运行 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| 旧实现命令生命周期基线 | 17 | 17 | 0 | 0 |
| 最终调度器与 Essentials 相关回归 | **356** | **0** | **0** | **0** |

新增 17 项使用真实 Paper API PluginCommand 对象及模拟服务，覆盖旧回调、旧补全、启用未完成、重启、共享别名、裸名冲突、命名空间回退、独立槽位接管、设置之后抛错、取消／恢复／最终保存失败与异常保留。旧实现 17 个失败均为行为断言，原始 XML 保留。已有生命周期测试仅补充插件名夹具，不改变其断言。

Spotless、2,266 主源／1,087 测试源编译、356 项相关测试、独立打包和在线 36 组件 SBOM 通过；最终 3 个模块类与已测 target/classes 字节一致，Java 21 和旧公开方法描述符保持。每次单个 256 MiB JVM、forkCount=0、jacoco.skip=true，无本机覆盖率。未新增 POM 排除；356 项不是全库测试。

```text
mvnw.cmd -o -B -ntp -Djacoco.skip=true spotless:apply -DskipTests test-compile
mvnw.cmd -o -B -ntp -Djacoco.skip=true -DforkCount=0 -Dtest=dev.starcore.starcore.core.scheduler.**,dev.starcore.starcore.essentials.** surefire:test
mvnw.cmd -o -B -ntp -Djacoco.skip=true -Dmaven.main.skip=true -Dmaven.test.skip=true verify
mvnw.cmd -B -ntp -Djacoco.skip=true cyclonedx:makeAggregateBom@generate-sbom
```

Chronicle 主构件沿用 NPC 检查点本地 1,021 项结果，本轮未本地重跑；新文档提交后独立跟踪 Linux CI。原 339／302／1,131 项和国库、贸易、工厂契约继续保持各自构件边界。StarCore 现有工作流不覆盖非 main 基线草稿 PR。

下一步继续玩家所有权与异步续办：经济转账、社交命令仍有远端玩家访问，其他 runSync／runNextTick 使用链及完整热重载继续审计。实际 NPC／保护组合、当前构件实服、全栈 Folia 和长期容量尚待验收；十阶段未全部完成。
