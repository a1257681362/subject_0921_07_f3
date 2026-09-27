# EVOPS 基础工作区

合作社作业管理系统骨架（F1：农机底账 + 作业单主链路；F2：卫星轨迹账每日勾稽/核销）。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf、Quartz。

启动：

1. `mvn -q -DskipTests compile` 构建。
2. `mvn spring-boot:run` 启动；Spring Boot 自动执行 `src/main/resources/schema.sql`（对旧库幂等，`CREATE TABLE IF NOT EXISTS` + 版本登记 `MERGE`）。
3. H2 数据文件默认写入工作区 `data/evops`。测试使用独立内存库（`test` profile）。
4. `mvn test` 运行全部测试。

> 交付副本 `../../docs/schema/evops.sql` 应与 `src/main/resources/schema.sql` 保持一致；当前容器对根目录 `/docs` 无写权限，部署时由有权限的环节同步。

## 核心约定

- **统一返回结构**：`{ success, message, data }`；业务/参数异常同样 HTTP 200 + `success=false`。
- **请求号**：所有新增/变更请求必须携带 `requestNo`，先登记 `t_request_record`（唯一约束），与业务写入同一事务；重放/并发重复提交只接受第一笔。
- **版本**：农机、作业单带 `version` 乐观锁，变更须携带最新版本，落库自动递增。
- **不物理删除**：农机停用（DISABLED）、作业单红冲（REVERSED）均只改状态、保留历史。
- **面积口径**：`trackArea` 轨迹核算面积是权威依据，完工必须先有轨迹面积；`manualArea` 人工上报仅留痕参考。
- **红冲**：原单置 `REVERSED`，另开反向单 `单号-R`（面积取反、`reversalFlag=1`、`originOrderId` 指向原单）。
- **事务边界**：建档/流转/面积/红冲均为单事务，台账、主表、状态日志一起提交或整体回退。
- 同一作业单号并发两笔建档，由 `uk_work_order_no` 唯一约束兜底，只成功一条。

## 接口

农机 `/api/machines`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/machines` | 农机建档（machineCode、machineName、requestNo） |
| POST | `/api/machines/{id}` | 变更信息（version 乐观锁、requestNo） |
| POST | `/api/machines/{id}/disable` | 停用（requestNo） |
| GET | `/api/machines/{id}` | 查询 |
| GET | `/api/machines` | 列表 |

作业单 `/api/work-orders`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/work-orders` | 建档（orderNo、machineId、operationType、requestNo） |
| GET | `/api/work-orders?orderNo=xxx` | 按作业单号检索（作业单 + 状态流转日志） |
| POST | `/api/work-orders/{id}/transit` | 状态流转（CREATED→IN_PROGRESS→COMPLETED；CREATED/IN_PROGRESS→CANCELLED） |
| POST | `/api/work-orders/{id}/track-area` | 登记轨迹核算面积（权威） |
| POST | `/api/work-orders/{id}/manual-area` | 人工上报面积（仅参考） |
| POST | `/api/work-orders/{id}/reverse` | 红冲，另开反向单 |

表结构（4 张业务表）：`t_machine`、`t_work_order`、`t_work_order_status_log`、`t_request_record`。

## F2 卫星轨迹账每日勾稽

与卫星轨迹核算平台**天天对账、一笔对一笔**：Quartz 每日 02:10 自动勾稽前一日（错过则启动补跑），也可手工触发；每日只允许一个批次（唯一约束 + 请求号台账双重防重入）。

### 勾稽口径（新旧并跑）

- **旧引擎（LEGACY，切换前基线）**：红冲原单/反向单整笔剔除，金额分毫必较，无容差。
- **新引擎（NEW）**：红冲原单 + 反向单都进勾稽（净额口径，双方逐笔配对，红冲单单号为 `原单号-R`）；`|双方面积差| <= 容差` 即判一致。
- **并跑模式（PARALLEL，默认）**：同快照双跑，批次头逐笔比对两种口径，给出 `MATCH/MISMATCH` 与分歧明细。至少连续两天 `MATCH` 且无未核销差异，`/api/recon/cutover-check` 才放行切换。
- 差异三类：`LOCAL_ONLY`（本地有对方无）、`REMOTE_ONLY`（对方有本地无）、`AMOUNT_DIFF`（超容差）；差异明细**带双方原始字段**（单号/面积/时间/状态/红冲标记）。
- **容差可配置**：`reconcile.tolerance`（默认 0.05 亩），也可通过接口在线调整（`t_recon_config` 落库，重启不丢），每批记录当时容差。

### 核销（手工调整）与清零

- 差异走核销流程：申请（带调整类型、原因、双方快照）→ 审批/驳回；审批通过与差异**同一事务**即时清零（明细置 `WRITTEN_OFF`、批次未平计数归零），驳回只留痕、差异保持未平。
- 核销是勾稽挂账层面的处理，**不回写作业单权威轨迹面积**。
- 已核销差异若在后续批次快照中仍存在（对方尚未补/冲账），**自动结转**到新批次并清零、挂原核销单，不会当新差异重复报。
- 所有核销动作走请求号台账，核销单带乐观锁。

### 勾稽接口 `/api/recon`、卫星账单 `/api/satellite-bills`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/satellite-bills` | 卫星账单录入（对方账，billNo、orderNo、trackArea、requestNo） |
| POST | `/api/satellite-bills/{id}/reverse` | 卫星账单红冲，另开反向账单（号-R、面积取反） |
| POST | `/api/recon/run` | 手工触发勾稽（不传日期默认前一日） |
| GET | `/api/recon/batches?limit=` | 批次列表（含双引擎汇总与比对结论） |
| GET | `/api/recon/batches/{id}` | 批次详情：新/旧引擎两份逐笔差异清单 |
| GET | `/api/recon/open-diffs?reconcileDate=` | 未核销差异清单（带双方明细） |
| GET | `/api/recon/cutover-check` | 切换闸门：连续 N 批双引擎一致且差异清零才 ready |
| GET/POST | `/api/recon/config`、`/api/recon/config/tolerance`、`/api/recon/config/engine-mode` | 查看/调整容差与引擎模式（PARALLEL/NEW/LEGACY） |
| POST | `/api/recon/writeoffs` | 核销申请 |
| POST | `/api/recon/writeoffs/{id}/approve` | 审批通过（差异即时清零） |
| POST | `/api/recon/writeoffs/{id}/reject` | 驳回（差异保持未平） |

切换操作流：`PARALLEL` 并跑 ≥ 2 天 → 每日看批次 `engineCompareResult=MATCH`、差异清零 → `cutover-check` 返回 `ready=true` → `engine-mode=NEW` 切换；异常时可改回 `LEGACY` 回退。

新增表：`t_satellite_bill`、`t_recon_batch`、`t_recon_detail`、`t_recon_writeoff`、`t_recon_config`。
