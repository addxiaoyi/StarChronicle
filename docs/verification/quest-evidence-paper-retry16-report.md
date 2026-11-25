# Quest Evidence Paper Retry16

这是一轮使用当前 Chronicle 与 StarCore 候选 JAR 的真实 Paper 1.21.11 / Java 21 验收。运行目录为 `run/paper-quest-evidence-8f6f83ab-retry16`，没有复制旧世界、数据库或原生任务数据。

| 产物 | SHA-256 |
|---|---|
| Chronicle | `8f6f83abe51f175078d3c0c07f2e4683f4d7884c5ef123a2d3b4c6555bea9b89` |
| StarCore candidate | `dca50c8aa6025e0c319d0756ca0ab19a90b33428ad619688d2f7320c577e4f5b` |
| Paper | `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba` |
| Fixture | `bdc9a7a1ea1bfb2b004ed9cdc0d4bd9db80efac951c119a5f7439163b6bc5059` |

## 结果

- 三次 serial boot 均正常停止，server/client exit codes 为 `0,0,0`；机器记录见 `evidence/quest-evidence-processes.json`，`three_normal_boots_completed=true`。
- Boot 1：A/B/C 三个非 OP 客户端在命令执行前完成真实连接。实际 Paper、Book、Map 制作分别产生严格的背包差量，原生任务进度为 `3/3`、`1/1`、`1/1`，完成前 gate 保持阻塞，native flush 成功。
- Boot 2：重启恢复每个原始 run、开始时间、定义指纹和进度；三位玩家通过真实 `/questgui progress`、任务详情和 slot 32 GUI 完成。三个 canonical completion ID 与独立 native run-completion marker 一致。注销 Chronicle provider 后 gate 保持 `UNSUPPORTED` 阻塞，不重注册替代 provider。B 离线前的 receipt 检查通过。
- Boot 3：仅 A/C 连接，B 保持离线。canonical lookup 发现 B 的已提交国家贡献，使 A 的国家实例 ready、C 的国家实例 blocked。A/C 完成三条示范剧情，最终荣誉为 A=`10`、C=`12`；三条 `quest.completed` 与三条 ending history 各一次。重放三个原始 fixture source 均返回 `duplicate=true`，结果没有增加。

## 边界

这轮是任务证据、重启、离线国家贡献、GUI 完成和重放幂等的功能验收，不是整服零错误或完整 StarCore 回归。Boot 1 和 boot 2 各有一次 `DUNGEON_DIRECTORY_SYNC_FAILED`，dungeon 模块未能启动；boot 3 中该模块正常启动。这些错误未破坏本轮任务证据断言，但需要单独修复或部署配置。StarCore 全量回归也仍记录为 7,968 项中 11 failures、123 errors、21 skipped。候选补丁尚未上游合并或生产部署。

失败诊断 retry12、retry13、retry14 和 retry15 保留在各自独立目录，不与 retry16 合并计算。retry16 使用的 client script SHA 为 `fa5a669facb4a5b678b87b9c2dc077d6e83b46b43e9011ad6fd03f930e0402b7`；当前工作区随后收紧了客户端未确认制作的重试安全策略，新的 script SHA 为 `a8ec898ebdb094e9a7ed1b249398ae6c0d27221feed93acd43784d43cd41e264`，尚未重新获得 Paper 现场证据。
