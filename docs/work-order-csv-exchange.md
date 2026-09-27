# 作业单文件交换格式规范（F3）

作业单 CSV 导出 / 模板下载 / 批量导入的统一口径。导出文件与导入模板**同列同序**，导出文件可直接再导入，往返内容一致。转义规则的唯一实现是 `CsvCodec`（`com.evops.workorder.exchange`），本文档与其保持一致；如有出入以代码为准并回修文档。

## 1. 文件格式

| 项 | 规则 |
| --- | --- |
| 编码 | UTF-8。导出文件带 BOM（Excel 直接打开不乱码）；导入自动剥离 BOM，有无均可 |
| 行尾 | 导出统一 CRLF；导入兼容 CRLF / LF / CR |
| 表头 | 第 1 行，固定为 `orderNo,machineCode,operationType,plotName,remark`，逐字符精确匹配（区分大小写、不含空格） |
| 数据行 | 第 2 行起；纯空白行自动跳过，不计入数据行 |
| 字段值 | 原样入库，不做 trim；可空字段（plotName、remark）留空即存 NULL |

## 2. 列定义（顺序固定）

| 列 | 必填 | 长度上限 | 说明 |
| --- | --- | --- | --- |
| orderNo | 是 | 40 | 作业单号，全局唯一；判重与幂等的依据 |
| machineCode | 是 | 40 | 农机**业务编码**（非内部 ID），须已建档且状态 ACTIVE |
| operationType | 是 | 40 | 作业类型，如 播种、收割 |
| plotName | 否 | 80 | 地块名称 |
| remark | 否 | 255 | 备注 |

超长在导入侧按行报错，不会落库截断。

## 3. 转义规则（RFC 4180 子集）

- 字段含**逗号、双引号、换行**任一字符时，整个字段用双引号 `"` 包裹。
- 字段内的双引号**双写**：`"` → `""`。
- 引号包裹的字段内允许换行，原样保留（该记录跨多个物理行）。
- 不含上述字符的字段不包裹。

示例（模板第二行即演示）：

```csv
orderNo,machineCode,operationType,plotName,remark
EXAMPLE-002,M-002,"收割,秸秆还田","西地块（示范）","备注含逗号, 与""引号""，演示转义"
```

解析异常（引号未闭合、引号字段后紧跟非法字符）整批拒绝，报错信息带物理行号。

## 4. 行号规则

- 行号按**物理行**计：表头 = 第 1 行，第一条数据 = 第 2 行。
- 引号字段内的换行同样推进物理行号；一条跨行记录报告其**起始**物理行号。
- 导入结果中每行返回 `lineNo`（物理行号）与 `rawLine`（该行的规范化单行表示，截断 200 字符）；定位源文件以 `lineNo` 为准。

## 5. 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/work-orders/export?orderNo=&status=&machineCode=` | 导出 CSV，按 orderNo 升序；三个过滤参数均可选、精确匹配；单次上限 10000 行，超出返回 `success=false` 提示先过滤 |
| GET | `/api/work-orders/import-template` | 模板下载：表头 + 2 行示例（EXAMPLE 开头，导入前删除） |
| POST | `/api/work-orders/import` | 批量导入。multipart 表单：`file`（CSV 文件，≤10MB）+ `requestNo`（批次请求号，≤52 字符）。单次最多 5000 行数据 |

导出/模板为文件下载（`text/csv; charset=UTF-8`，带 BOM）；导入返回统一结构 `{success, message, data}`，`data` 为逐行结果。

## 6. 导入幂等语义

两道防线，重复导入不产生重复单：

1. **批次级**：`requestNo` 登记 `t_request_record`（唯一约束）。同一 `requestNo` 重复提交 → 整批拒绝（"请求号已处理"）。注意：批次一旦登记即留痕，即使文件解析失败该请求号也已占用，修正文件后须**换新请求号**重试。
2. **行级**：按 `orderNo` 判重——
   - 已存在且**内容一致**（machineCode/operationType/plotName/remark 逐字段比对，NULL 与空串同视）→ `SKIPPED`，不写库；
   - 已存在但**内容冲突** → `FAILED`，报冲突字段名；
   - 不存在 → 建档（行级请求号 = `批次requestNo#L行号` 登记台账，`uk_work_order_no` 唯一约束兜底并发）。

因此：同一文件换一个新 `requestNo` 再导入，结果应全部为 `SKIPPED`，库中无新增。

## 7. 导入结果结构

```json
{
  "success": true,
  "message": "OK",
  "data": {
    "totalRows": 3,
    "createdCount": 1,
    "skippedCount": 1,
    "failedCount": 1,
    "rows": [
      { "lineNo": 2, "orderNo": "WO-001", "status": "CREATED", "message": "建档成功", "rawLine": "WO-001,M-001,播种,东地块," },
      { "lineNo": 3, "orderNo": "WO-002", "status": "SKIPPED", "message": "已存在且内容一致，跳过", "rawLine": "..." },
      { "lineNo": 4, "orderNo": "WO-003", "status": "FAILED", "message": "农机不存在: M-999", "rawLine": "..." }
    ]
  }
}
```

- 行级独立事务：失败行不影响其他行，成功行已落库，**不要**因部分失败整体重导（重导时成功行会判 SKIPPED，失败行可修正后单独再导）。
- 整批拒绝（`success=false`）的情形：请求号缺失/重复/超长、文件为空、表头不符、CSV 解析失败、数据行超上限、无数据行。

## 8. 往返一致性

导出 →（可选：编辑但遵守本规范）→ 导入 → 再导出，两次导出文件逐字节一致。保证这一点的是：同一份列定义（`WorkOrderCsvFormat`）、同一个编解码器（`CsvCodec`）、字段值原样入库不 trim、可空字段空值与 NULL 同视。
