# 指定首都持久历史合同

当前候选已实现 StarCore 原生指定首都历史与 Chronicle 持久收件箱。`NationService.designateCapital` 是唯一写入入口；Chronicle 只消费提交记录，不增加迁都 YAML effect。旧版 `CityService` 的最高等级城市、`Nation.capitalLocation()` 缓存以及在线玩家身份都不能替代迁都证据。

## 原生读写协议

`capitalHistoryAfter(after, limit)` 返回 `Optional<CapitalHistoryPage>`：独立首都存储域 UUID、同一读取边界的 `latestSequence` 及连续记录。`after` 非负，原生页限额 1–1000。未实现能力时返回 empty；损坏、读失败及游标超出末尾必须报错。empty 列表只表示已追上已验证末尾。原有 `capitalDesignationsAfter` 与 `designatedCapital` 继续保留。

每条 `CapitalDesignation` 包含全局序列、操作 UUID、国家 UUID、前首都城市 UUID（首次指定为 null）、目标城市 UUID、真实操作者 UUID 与原始毫秒提交时间。首次指定生成 `CAPITAL_DESIGNATED`；此后的实际城市变化生成 `CAPITAL_MOVED`。`A→B→A` 之间没有扫描也会保留两次移动。国家与城市使用 ID，不使用名称、位置或现任统治者补写旧记录。

原生写入在国家及城市删除监视器内重检当前创始人、国家存在与目标城市归属。新操作指定同城、非当前创始人、外国城市与不存在的城市均拒绝。同操作 ID 的相同国家／操作者／目标重试，在当前授权和目标存在检查通过后返回原始回执。历史查询不要求原统治者仍在位或目标城市仍存在；删除城市不会自动迁都。

## 提交边界与旧数据

首都状态由日志直接导出。日志使用格式／域头、独立 `.authority` 标记及 `.head`（域、已提交序列、字节数、SHA-256）。追加先强制日志落盘，再原子发布并强制提交末尾落盘；末尾发布同时确定状态与历史。进程内和操作系统文件锁防止多个实例相互覆盖，旧实例发现末尾改变会要求重新打开。

启动、分页及当前指定读取验证已提交前缀。完整尾记录被删、字节损坏、文件或标记缺失、域改变都不会退回较旧状态；仅已验证末尾以外的未提交后缀可以丢弃。写入响应不确定时，该实例停止继续读写；重启并用同一操作 ID 对账。无自动旧备份回退。要求文件系统支持原子替换及目录 fsync，并依赖存储实际遵守强制写入语义。

升级旧日志时，先验证连续性、逐国前后城市及操作唯一性，再把未修改的原始字节保存为 `.legacy-v0`，安装新域和末尾。旧记录的 ID、操作者和提交时间保留，旧缓存不生成历史。升级前已经丢失的记录无法恢复或追认。迁移中断或不完整标记集要求显式恢复。整套文件一起被替换为一致旧备份，原生端无法独立识别；Chronicle 已有游标超过恢复末尾时仍会拒绝推进。

## Chronicle 收件与事实

来源键为 `starcore.capital.designations.v1`，事件 ID 为 `starcore:capital-history:<domain>:<sequence>`。每轮最多读取 100 条；域、提交末尾、页大小、连续性和提供者身份均校验，完整转换成功后，源游标与不可变收件条目在 Chronicle 同一事务提交。引擎成功接收后才确认收件记录；中间重启允许重放，事件 ID 去重防止重复入史。其他来源的待处理失败不阻塞首都来源。

事件 `source` 为 `StarCore.NationService`，`time_basis=COMMITTED`，唯一历史参与者为原回执中的实际操作者。事实包括 `source.authority_domain`、`source.sequence`、`capital.operation_id`、`capital.nation`、`capital.previous_city`（首次为 `NONE`）、`capital.city`、`capital.actor`、`capital.kind`。首都指定不代表实物或财政交付，不生成物资贡献。

原生快照的 `starcore.capitals` 仍标记为不可用，以免从城市等级／缓存生成重复或虚构的观察迁都。已有独立、可信 `CAPITALS SnapshotProvider` 仍承担其自身完整性合同，不获得原生精确提交语义。

## 验证边界

本候选 Chronicle 927 项完整构建通过；原生 7,272 项完整回归通过，21 项既有跳过，保留原有构建排除配置。测试包含 205 条分页收件、消费提交前后恢复、源域／末尾回退拒绝、原生日志损坏、完整尾丢失、迁移、写入回执丢失、陈旧实例及城市删除并发。实服范围与精确指纹以 `CAPITAL-HISTORY-TEST-REPORT.md` 为准。

返回页的对象和记录数量有界，但每次完整性校验仍读取整个日志前缀，幂等查询扫描历史，启动保留操作 ID 集合用于查重。三万条分页测试不等于吞吐或长期容量验收。本协议不提供历史裁剪、MySQL 首都权威、跨服务共同事务或全栈 Folia 支持；也不使十阶段整体完成。
