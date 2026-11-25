# 国家援助与国库对账

StarChronicle 的国家援助 API 只编排一次经过 StarCore 权威服务执行的国库转账。StarCore 仍然拥有国家余额、成员资格、提款权限和实际扣款；Chronicle 负责把已提交的原生回执与世界历史、剧情收件箱和贡献记录绑定起来。

## 前置条件

当前发行版使用内置 `StarCoreTreasuryAidProvider`。使用国库援助前，必须安装经过审计的 StarCore 构建，并确认其 `TreasuryService.authorityDomainId()` 返回持久且稳定的 UUID。审计基线 `f769e62799e769a18fe51305b545869a17f80cd1` 尚无此方法；对应候选改动见 [`starcore-treasury-authority-domain.patch`](../patches/starcore-treasury-authority-domain.patch)。该补丁的 StarCore 生产／测试源码完整编译，`TreasuryAuthorityDomainTest` 5 项通过，但尚未上游合并，也没有完整 StarCore 回归或真实服务器转账验收。

旧状态没有可信 authority domain 时，插件会停用援助服务并保持 fail-closed。不要用个人经济、命令执行、随机 UUID 或自定义余额表替代原生服务。

`AidProvider` 是内置原生适配器遵守的窄合同，只接受 `AidRequest`。当前发行版不会动态选择外部 `AidProvider`；可信扩展若要使用自定义 provider，仍需 StarChronicle 明确提供注册和生命周期合同：

```java
public interface AidProvider {
    String providerId();
    String authorityDomain();
    CompletionStage<AidObservation> lookup(AidRequest request);
    CompletionStage<AidObservation> submit(AidRequest request);
}
```

`providerId` 和 `authorityDomain` 在服务创建时固定。回执必须包含同一 issuer/domain、交易 ID、付款国、收款国、金额、`OTHER` 类别、完整描述和不早于请求时间的 `appliedAt`。任何不匹配的回执都只能进入 `UNKNOWN`，不能写入历史或发放剧情结果。

## 提交流程

可信集成插件通过 Bukkit `ServicesManager` 获取 `ChronicleService`，构造稳定的 `AidRequest.outcomeId`，并调用 `submitAid`：

```java
AidRequest request = new AidRequest(
    "heir-relief-2026-01",       // stable transaction/outcome id
    "last-heir-instance",        // Chronicle instance
    "starcore:heir:42",          // stable source/reference ID
    "heir-relief",
    AidRequest.OPERATION,
    definitionSha256,
    actor.getUniqueId(),
    payerNationUuid,
    receiverNationUuid,
    new BigDecimal("2500.00"),
    Instant.now());

chronicle.submitAid(request).whenComplete((result, error) -> {
    // Re-enter the player/entity scheduler before sending a message.
});
```

服务端会重新收集当前玩家身份，要求 actor UUID、付款国和 `starcore.nation.bank.withdraw` 权限全部匹配。首次提交会先将 intent 写入本地台账，再调用原生服务。原生回执严格匹配后，Chronicle 在同一 SQL 事务中写入：

- `NATIONAL_AID_COMMITTED` 世界历史事件及可追溯证据；
- 参与者和两国的剧情触发收件箱；
- 幂等的援助台账和贡献记录。

事件 ID 由 `providerId` 与 `outcomeId` 稳定派生，因此重启或重复回调不会重复扣款或历史。剧情效果也使用稳定效果 ID 幂等提交。

## UNKNOWN 与恢复

台账状态为 `READY -> ADMITTED -> COMMITTED | REJECTED`，也可能进入 `UNKNOWN`。进程在原生调用后崩溃时，重启会把遗留 `ADMITTED` 标为 `UNKNOWN`。`UNKNOWN` 永远不会由后台自动重试；管理员或受信任 worker 必须明确调用 `reconcileAid`，该调用只读原生回执。

原生 `REJECTED` 也必须先通过 `lookup` 得到明确的 `ABSENT`，才允许落为 `REJECTED`。如果 lookup 失败、返回空结果或返回无法匹配的回执，状态继续保持 `UNKNOWN`，避免把实际已扣款误判为拒绝。

## 剧情接入

剧情定义可以监听 `NATIONAL_AID_COMMITTED`，并使用以下只读事实：`event.aid.transaction`、`event.aid.payer`、`event.aid.receiver`、`event.aid.amount`、`event.aid.campaign`、`event.aid.operation`、`event.aid.authority_domain` 和 `event.aid.applied_at`。这些事实来自已验证原生回执；剧情不能通过 `history`、`authority` 或其他 effect 自行伪造国库支出。历史事件包含付款国和收款国；当前提交调用的在线玩家上下文来自发起者和付款国成员，收款国仍会作为世界事件国家索引保存。

收款国和付款国受众由援助服务从当前可信快照筛选。若需要修改国家外交、余额或资源，剧情只能提出后续意图，实际变更必须再次经过 StarCore 权威服务并拥有独立的回执合同。

完整示例见 [`examples/national-aid/relief-aftermath.yml`](../examples/national-aid/relief-aftermath.yml)。该示例只写世界记忆和历史摘要，明确不会再次转账。

## 运维检查

1. 升级 StarCore 前备份 SQLite 数据库，并验证 authority domain 没有改变。
2. 发生 `UNKNOWN` 时先查询原生 treasury receipt，再决定是否对账；不要删除台账行或重新生成 outcome ID。
3. 运行中备份必须包含 SQLite WAL 的一致性快照。
4. Folia 环境下仅在上游调度审计通过后启用原生援助；缺失原生 domain 或实体线程调度时保持停用。
