# 指定首都历史实服验收

2026-11-25 UTC：首都提交日志、连续历史收件、原始操作者、重启恢复、统治者交接及损坏拒绝已通过所选 Paper 组合验收。Chronicle 927 项 `clean verify` 全通过；StarCore 7,272 项完整 `verify` 通过，零失败／错误、21 项既有跳过，保留原有排除配置。合同见 [CAPITAL-AUTHORITY-CONTRACT.md](CAPITAL-AUTHORITY-CONTRACT.md)。

## 精确构件

| 构件 | SHA-256 | 源码 |
| --- | --- | --- |
| StarChronicle | `daa123548d66b5da6c8bd3c759767256d1bc0e6f7a66edfe5eb9c79d1fe70bf7` | `9b0e846`，`codex/capital-history-ingress-20261125`，叠加记忆阶段 |
| StarCore | `3199771bfe9d194443d1d106546ea5ac783a98452b8240e9a67e0b6f09fec135` | `54587227`，`codex/capital-history-authority-20261125`，叠加生产组合阶段 |

审阅入口为 [Chronicle PR #10](https://github.com/addxiaoyi/StarChronicle/pull/10)／[StarCore PR #8](https://github.com/addxiaoyi/starcore/pull/8)，均为草稿、未合并。

实际运行 Java 21、Paper 1.21.11、EssentialsX 2.22.0、Vault 1.7.3-b131、FancyNpcs 2.10.1-java21+2、WorldEdit 7.4.2、WorldGuard 7.0.17；[组合锁](verification/production-stack-candidate-20261125.json)保存来源和指纹。实服期间没有重建或替换双插件。后续 CI 构件须单独记录，不承接这些实服通过结论。

## 实际行为与恢复

| 目录后缀（前缀 `paper-epic-daa12354-dungeon-`） | 实际过程 | 结果 |
| --- | --- | --- |
| `capital-candidate-2`，29724 | 三次正常启动，四个非 OP 真实客户端；真实服务创建城市；205 次指定／移动；更换创始人后再迁都；删除目标城市 | 206 条连续提交、1 次首次指定、205 次迁都；9 次拒绝、2 次原操作重试；原 205 条保留 A 为唯一操作者，第 206 条为 D |
| `capital-offline-candidate-1`，29725 | 两次正常启动；第一轮保留 205 条原生提交，Chronicle 历史尚未消费；停服后将轮询从 72000 tick 调回 20；第二轮在任何客户端进程启动前补录 | 205 条原提交时间与真实操作者保持不变；5 次拒绝、2 次重试；零在线玩家时完成全部三页补录 |
| `capital-corrupt-tail-1`，29726 | 从已完成主场景复制停服实例，删除完整第 206 条，保留原独立末尾与存储域，然后真实启动 | 国家模块因 `Committed capital bytes missing or modified` 拒绝启用；损坏日志、独立末尾和域标记未被改写；服务正常停止 |

所有正常场景共五个服务端和五个客户端进程退出码 0。负例服务端也退出 0，但其业务结果明确是预期的拒绝启动，不计为成功加载。全部相关端口已释放。客户端 UUID 对照运行清单，国家、城市、指定、交接与删除均使用真实原生服务；夹具没有通过 SQL 或模型 setter 插入首都或历史。

首轮先首次指定 A 城市，再在 A／D 两座同国城市之间来回指定。两次扫描间的往返均保留，最终回到 A；记录超过单页 100 条。非创始人、外国城市、不存在的城市、当前同城新操作和已绑定 ID 的不同请求均拒绝。重启后原操作回执逐字保持；更换创始人不改旧操作者。删除当前指定城市后，旧指定和历史保留，不从城市等级或位置推算替代首都。

## 独立核对、失败与限制

检查器从日志原始字节独立读取域、序列、前后城市、操作、操作者与提交时间，核对 `.head` 长度／SHA-256、`.authority`、逐国链和操作唯一性，再比对 Chronicle SQL 收件状态、连续游标、不可变事件、规范事件哈希及玩家／国家索引。每次停服的两库快照、原生首都文件、API 实时观察、服务器／客户端日志、冻结夹具和控制器哈希均纳入核对。首都事件没有物资贡献。

在实际目录副本上执行证据破坏回归：主场景 21 项通过、2 项仅离线适用跳过；离线 23 项全部通过，共 **44 项通过、2 项跳过**。完整尾丢失、篡改域／时间／历史操作者、删除收件／索引／启动记录、异常进程退出、伪造实时拒绝／重试以及混入原生保存错误均被拒绝。

保留 `capital-candidate-1` 的真实失败：夹具在 Chronicle 正持续收件时分别请求多个 offset 历史页，完整序列核对失败。该轮停服后独立核对为 205 条原生日志、205 条 Chronicle 事件、205 条 ACKED 收件且连续一致。首轮未记录失败查询的具体页内容，因此不声称已复原该次瞬时视图。修订夹具在有限时间内重新读取完整序列，并记录每次不完整 ID 集；成功轮次实际捕获到“200 个结果、100 个唯一 ID”和“206 个结果、205 个唯一 ID”的分页边界移动，最终完整逐项匹配。产品 JAR 没有因此修改，失败 boot 没有被覆盖。

测试开发的首次专项误用历史页大小 1000、首次完整构建误把 offset 当页号，均已修正，原失败日志入档。首次离线检查器拒绝了 FancyAnalytics 的 HTTP 502 遥测错误；当前仅独立记录这个明确的供应商遥测 5xx，其他服务端／持久化错误继续拒绝。本轮离线首启有一条此类诊断，主场景没有。

## 归档与跨设备复验

[汇总](verification/capital-history-paper-20261125-summary.json)和[证据包](verification/capital-history-paper-20261125-evidence.zip)保存两个成功场景、保留失败、损坏副本启动、五次停服快照、源码工具、最终两库测试 XML 及成功／失败构建日志。包大小及 SHA-256 以汇总中的 `evidence_archive` 为准；所有 ZIP 成员逐项验证。配置密钥脱敏，双 JAR 和两库源码 tar 位于本机 `target/capital-history-paper-20261125/`。

```bash
export EPIC_MINEFLAYER_MODULE=/path/to/epic-client/node_modules/mineflayer
python3 tools/prepare-capital-paper.py --attempt NEW --port 29724 \
  --starcore /path/to/StarCore.jar --runtime /path/to/initialized-paper \
  --java-home /path/to/java21 --artifacts-dir /path/to/locked-plugins
# 对同一新目录依次运行 boot 1/2/3，scenario capital-first/second/third
python3 tools/epic-paper-session.py --run run/NEW-DIRECTORY \
  --java-home /path/to/java21 --node /path/to/node --boot 1 --scenario capital-first
python3 tools/inspect-capital-paper.py --run run/NEW-DIRECTORY
STARCHRONICLE_CAPITAL_RUN=run/NEW-DIRECTORY python3 tools/test_capital_acceptance.py
```

离线验收使用另一个目录／端口并增加准备参数 `--offline`。运行 `capital-offline-first` 正常停服后，执行 `prepare-capital-resume.py --run ...` 保存完整配置变更证据，再运行 boot2 `capital-offline-second`。破坏性验证仅对停服副本使用 `run-capital-corruption.py`，原受验目录不修改。新设备重建必须用新指纹和新目录。

本增量没有复验完整史诗业务、贸易资产共同提交、文明生产、MySQL、Folia 或长期吞吐。每次原生完整性检查仍扫描日志前缀；正常重启和故障注入不等同于物理断电与存储硬件保证。全套一致旧备份的回滚、迁移前历史损失及人工恢复边界见合同。十阶段继续推进，尚未合并或生产部署。
