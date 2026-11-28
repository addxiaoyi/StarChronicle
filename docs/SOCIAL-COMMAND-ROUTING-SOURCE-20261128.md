# `/social` 管理路由兼容修复

StarCore `7daaa4236ea410646ab93b6cf0d2a414682cf5db` 已推送开发分支。后续审查发现，前序 `2b41e71` 注册社会模拟 `/social` 时，覆盖了 SocialModule 原有的 export、import、exportplayer、list 管理入口。旧 231 项测试未覆盖两个模块的组合注册，原报告已补记此遗漏；本检查点恢复四个管理子命令，并独立声明和绑定 `/socialdata`。两条入口使用同一个 SocialDataCommand，帮助使用独立入口。普通社会模拟子命令保持原处理器。

路由只接收明确的 SocialDataCommand，不把未知插件默认 executor 当成管理处理器，避免递归转发。管理执行和补全检查 `starcore.social.admin`；无权限、服务缺失或 null 补全都不会触发 Bukkit 默认玩家补全。管理补全过滤当前前缀，停用社会模拟模块恢复原管理 executor/completer，保留的旧 router 被代次闸门拒绝。

本次只完成入口兼容和权限补全。SocialDataCommand 的同步文件 I/O、导入路径边界、在线玩家名字获取及导入导出事务尚未审计完毕，不把路由通过当作这些工作完成。社交菜单共享服务、推荐／好友状态、联盟、活动／文化及其余阶段继续待办。

## 验证

`run/native-social-command-routing-focused-1-20261128`：257 项相关 verify、27 个必需套件，零失败／错误／跳过，源码无漂移，打包成功。新增六项路由行为测试与一项真实双模块注册／恢复测试。Java 21、320 MiB heap、192 MiB metaspace、Serial GC、单处理器、forkCount=0，238.922 秒；JaCoCo 跳过，离线 SBOM 未重新生成，不是 StarCore 全库验证。

`run/social-command-routing-pair-artifacts-1-20261128`：配对公开 API、项目类资源、sources JAR 和 8 个编译夹具通过。保留 Chronicle 214 个公开类型／1,866 个描述符和 StarCore 3,010 个公开类型／35,883 个描述符。

冻结 StarCore shaded JAR SHA-256 `2cc6bcdb9848bff17ddc74e32f0421b69d3e7e99b7b2358673777b710c8f5868`；original JAR `db152c30d2ab1b76316de04eb6a91bba8168b5043f429518c6749efc2c94bbff`；Chronicle `3561a787cf02e343778d22e16bbfc05b15ad12e5e1c68f969cb63c818e2726fc`。

[证据 ZIP](verification/social-command-routing-source-20261128-evidence.zip)及[逐成员摘要](verification/social-command-routing-source-20261128-summary.json)：3,838 成员，9,546,389 bytes，SHA-256 `cf29ff61a03c9bb30dbaa5ae76ec19083be50bcfd2a809becad17bd3fec10c0e`，逐成员读取校验通过。JAR／class 未提交 Git。

Chronicle 产品保持 `53562a2`／1,195 项完整 verify。本批没有持久格式变化，没有新增数据库探针，前序八卦 schema 2 的十三 JVM 结果仍归属于其原构件。

本检查点 CI：[原始记录](verification/social-command-routing-ci-20261128.json)确认 StarCore `7daaa42` run `46090518289` 因账户付款／额度限制未启动，四项检查 runner=0、零步骤，发布 skipped；Chronicle `fd77a80` run `46090609448` 的 10 步成功。

## 实服边界

只做源码、单元测试和离线构件检查，没有启动 Minecraft／Paper／Folia，没有部署或合并主分支。当前构件实际命令权限、多人 GUI、重启、第三方组合、MySQL、长负载和全栈 Folia 仍需实际验收；十阶段尚未完成，不声明在线热卸载支持。
