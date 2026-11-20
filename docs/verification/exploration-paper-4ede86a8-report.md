# 配置地区首访 Paper 验收

插件 SHA-256：`4ede86a8bb6315776184c612a488f0c9ebc0321ccef73ce84ddfb097c1f374ca`。同一独立测试服完成两次正常启动／退出，server 与 client 各次退出码均为 0。

6 项可执行检查通过：真实步行、实际传送、真实 GUI 选择、重启后规范首访及再次到访幂等。

本轮还验证了真实取消传送不入史。

两次正常停服的 SQLite 均保留 2 条规范首访、2 条 ACKED 首访回执、2 个完成实例、2 条 LIVE ballot／2 条 resolution、4 条 DONE 结果，最终荣誉严格为 5。独立检查器复核实际物理动作、GUI 实例与节点、来源／范围／个人索引及两次数据库检查点的一致性。

机器结果见 [summary](exploration-paper-4ede86a8-summary.json)，40 份原始日志、实际使用脚本、配置和检查点描述见 [逐文件哈希清单](exploration-paper-4ede86a8/evidence-manifest.json)。原始停服数据库保留在 `run/exploration-4ede86a8-paper-retry1`，归档不包含数据库、服务器世界或第三方 JAR。

本轮显式使用 `require-starcore:false`，只验证 Chronicle 自有探索能力，不代表 StarCore 全栈 Folia 或原生国家权威通过。时间基准为 `OBSERVED`，瞬间经过且未被实体线程确认的到访不会被补造。旧构建或失败 attempt 独立保留，不能与本轮合并计算。
