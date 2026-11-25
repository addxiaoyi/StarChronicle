# 国家生产配方与并发容量

`plugins/StarCore/nation-production.json` 控制新开工的配方、单次批数及每国同时运行的生产数／总批数。首次启用安装内置文件；旧默认三配方的材料、产出和时长不变。容量指国家未领取／未取消的 RUNNING 批次，不是工厂建筑数量、等级或速度。

```json
{
  "format": 1,
  "enabled": true,
  "limits": {"max-running-runs": 16, "max-running-batches": 1000},
  "recipes": [{
    "id": "industrial_refining",
    "name": "工业精炼",
    "inputs": {"mineral": 3, "energy": 1},
    "outputs": {"industrial": 2},
    "seconds-per-batch": 30,
    "max-batches": 1000
  }]
}
```

`/nationproduction recipes` 显示当前配方和单次上限；`/nationproduction capacity` 由当前国家创始人查看已占用与总容量。所有 RUNNING 批次都计数，包括已到领取时间、旧配方已删除、容量已经下调的批次。下调不取消已付原料的批次；仅 COMPLETED／CANCELLED 释放占用。

控制台或持有 `starcore.nationproduction.admin`（默认 OP）的管理员可执行 `nationproduction validate` 与 `nationproduction reload`。校验只读，不改变运行配置；重载先完整校验，再一次发布不可变配置。重载必须位于 Paper 主线程，Folia 拒绝。文件错误时，热重载保留旧配置；启动时关闭新开工，旧批次仍按原条件领取或取消。`enabled:false` 可以配合空配方表停用新开工。

批次已经保存的投入、产出、原操作者、原国家与领取时间不会受配方改名、改量、删配方、缩短时长或停用影响。同 ID 精确重试仍可核对已处理状态；同 ID 更换操作者、配方 ID 或批数会被拒绝。生产结算及退款始终只使用原批次保存的条款。

新开工先检查当前配置，再在资源 revision 写锁内、同一次库存／生产日志 SQL 事务中复核容量。独立服务实例争抢最后一个名额不能双扣库存。数据库竞争失败的调用保守返回 UNKNOWN，并重读权威状态；可以按原批次 ID 查询／重试。没有用逐项退款补偿猜测事务结果。 配置重载仅作用于本服，不向其他写入实例传播。多个实例共享库时必须部署一致的配方／容量策略；这不是跨服配置共识协议。

文件必须是至多 64 KiB 的常规 UTF-8 文件，拒绝符号链接、重复 JSON 键、未知或缺少的字段、未知资源、字符串／小数／指数形式的数量、控制字符、过深结构与数量乘法越界。最多 100 配方，配方 ID 只用小写字母、数字及 `_.:-`、长度 1–64；名称 1–80 字符。每批时长 1–86400 秒；每次 1–1000 批；单项资源乘以该配方最大批数不得超过 100,000,000。国家运行数上限 1–1000，总运行批数上限 1–1,000,000。

本功能没有迁移旧 ProcessingService 工厂：旧记录缺少可靠原操作者和完整付费回执，不能据此自动退料、发货或补个人贡献。旧建筑容量／等级、配方平衡、长期容量、全栈 Folia、真实生产部署继续单独推进。

本轮构建、MySQL专项与所选组合实服进度见[验收报告](PRODUCTION-CATALOG-TEST-REPORT.md)，全目标状态见[十阶段交接](HANDOFF-2026-11-25.md)。
