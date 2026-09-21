# cc-finance

cc-finance 是一个面向企业财务场景的 Spring Boot 后端项目，当前提供会计科目管理、会计期间管理和记账凭证入账能力。

## 环境

- Java 21
- Maven Wrapper
- Spring Boot 4.1.1
- Spring Data JPA + H2 内存数据库

## 本地运行

运行测试：

    ./mvnw test

启动应用（默认端口 8080）：

    ./mvnw spring-boot:run

## 主要接口

### 会计期间

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/periods` | 创建会计期间（同一月份唯一，重复返回 409） |
| GET | `/api/periods` | 查询全部会计期间（按期间编码升序） |
| GET | `/api/periods/{periodCode}` | 按期间编码查询，不存在返回 404 |
| POST | `/api/periods/{periodCode}/close` | 关账（幂等，重复关账返回当前结果） |

期间规则：

- 会计期间按月份管理，创建时传入 `year`（1900-2100）和 `month`（1-12），系统生成 `YYYY-MM` 格式的期间编码，并保存开始日期、结束日期、状态、创建时间和关账时间。
- 状态只支持 `OPEN` 和 `CLOSED`，新建期间默认为 `OPEN`；同一 `year` + `month` 只能有一条记录（数据库唯一约束）。
- 关账只允许把 `OPEN` 期间改为 `CLOSED` 并记录关账时间；对已关账期间重复调用直接返回当前结果，关账时间保持不变。

创建期间：

    curl -X POST http://localhost:8080/api/periods \
      -H 'Content-Type: application/json' \
      -d '{"year": 2026, "month": 9}'

关账：

    curl -X POST http://localhost:8080/api/periods/2026-09/close

### 会计科目

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/accounts` | 创建科目（编码唯一，重复返回 409） |
| GET | `/api/accounts` | 查询全部科目 |
| GET | `/api/accounts/{code}` | 按编码查询科目，不存在返回 404 |

科目类别：`ASSET`（资产）、`LIABILITY`（负债）、`EQUITY`（所有者权益）、`REVENUE`（收入）、`EXPENSE`（费用）。

创建科目：

    curl -X POST http://localhost:8080/api/accounts \
      -H 'Content-Type: application/json' \
      -d '{"code": "1001", "name": "银行存款", "category": "ASSET"}'

查询科目：

    curl http://localhost:8080/api/accounts/1001

### 记账凭证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/vouchers` | 凭证入账（同一事务写入凭证与分录） |
| GET | `/api/vouchers/{voucherNo}` | 按凭证号查询完整凭证，不存在返回 404 |
| POST | `/api/vouchers/{voucherNo}/reversal` | 按原凭证号发起冲销，生成借贷方向相反的 POSTED 凭证 |

入账规则：

- 每张凭证至少两条分录，方向为 `DEBIT` / `CREDIT`，金额必须大于 0 且最多两位小数（BigDecimal 处理）。
- 入账前根据 `voucherDate` 定位会计期间：期间不存在返回 422，期间已关账返回 409，且不会留下任何凭证或分录数据。
- 入账前校验借方合计等于贷方合计，且所有科目存在并处于启用状态；任一条件不满足则整张凭证不落库。
- 入账成功后生成唯一且不可变的凭证号（如 `JV-00000001`），返回分录顺序与请求一致。
- `bizKey` 为业务唯一号（数据库唯一约束）：相同 `bizKey` 且请求内容一致时返回首次创建的凭证；内容不一致时返回 409。
- 关账与同一期间的凭证入账通过数据库事务和行级悲观锁保证并发一致：关账成功后该期间不再接受新凭证。

凭证入账前需要先创建对应会计期间：

    curl -X POST http://localhost:8080/api/periods \
      -H 'Content-Type: application/json' \
      -d '{"year": 2026, "month": 9}'

凭证入账：

    curl -X POST http://localhost:8080/api/vouchers \
      -H 'Content-Type: application/json' \
      -d '{
        "bizKey": "BIZ-001",
        "voucherDate": "2026-09-19",
        "summary": "销售回款",
        "entries": [
          {"accountCode": "1001", "direction": "DEBIT", "amount": 1000.50, "summary": "收款"},
          {"accountCode": "6001", "direction": "CREDIT", "amount": 1000.50}
        ]
      }'

查询凭证：

    curl http://localhost:8080/api/vouchers/JV-00000001

### 凭证冲销

冲销规则：

- 按原凭证号发起冲销，请求传入新的 `bizKey`、冲销日期 `voucherDate` 和可选摘要 `summary`（缺省时自动生成“冲销 {原凭证号}”）。
- 成功后生成一张新的 `POSTED` 凭证：分录顺序、科目和金额与原凭证一致，借贷方向相反；原凭证和原分录不会被修改。
- 查询原凭证或冲销凭证时可通过 `reversedByVoucherNo` / `reversalOfVoucherNo` 字段看到两者的关联。
- 冲销日期对应的会计期间必须存在且处于 `OPEN` 状态：期间不存在返回 422，期间已关账返回 409；校验失败不会留下任何凭证或分录数据。
- 同一张原凭证最多只能生成一张冲销凭证（数据库唯一约束）：相同 `bizKey` 且请求内容一致时返回首次生成的冲销凭证；请求内容不一致，或用其他 `bizKey` 再次冲销同一原凭证时返回 409。
- 冲销凭证不能再次冲销（返回 409），原凭证不存在时返回 404。

凭证冲销：

    curl -X POST http://localhost:8080/api/vouchers/JV-00000001/reversal \
      -H 'Content-Type: application/json' \
      -d '{"bizKey": "REV-001", "voucherDate": "2026-09-20", "summary": "冲销销售回款"}'

### 发生额试算平衡表

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/reports/trial-balance/{periodCode}` | 按会计期间查询发生额试算平衡表，期间不存在返回 404 |

统计规则：

- 按期间编码查询，`OPEN` 和 `CLOSED` 状态的期间都可以查询；报表在查询时实时统计，不保存额外的报表数据。
- 统计范围为凭证日期落在该期间起止日期内的所有 `POSTED` 凭证（含冲销凭证，冲销凭证与普通凭证一样参与统计）。
- 每个在期间内有发生额的科目输出一行，按科目编码升序排列，包含科目编码、名称、类别、借方发生额、贷方发生额、余额方向和余额金额；科目即使之后被停用，只要期间内存在历史分录仍会出现在报表中。
- 余额方向：借方发生额大于贷方发生额时为 `DEBIT`，贷方大于借方时为 `CREDIT`，两者相等时为 `NONE`；余额金额为借贷差额的绝对值。原凭证与冲销凭证在同一期间时，两边发生额都保留，对应科目的余额相互抵消。
- 报表同时返回借方发生额合计 `debitTotal` 和贷方发生额合计 `creditTotal`，两者必然相等；没有凭证的期间返回空明细，两个合计均为 `0.00`。
- 所有金额统一保留两位小数。

查询试算平衡表：

    curl http://localhost:8080/api/reports/trial-balance/2026-09

响应示例：

    {
      "periodCode": "2026-09",
      "debitTotal": 1000.50,
      "creditTotal": 1000.50,
      "lines": [
        {
          "accountCode": "1001",
          "accountName": "银行存款",
          "category": "ASSET",
          "debitAmount": 1000.50,
          "creditAmount": 0.00,
          "balanceDirection": "DEBIT",
          "balanceAmount": 1000.50
        },
        {
          "accountCode": "6001",
          "accountName": "主营业务收入",
          "category": "REVENUE",
          "debitAmount": 0.00,
          "creditAmount": 1000.50,
          "balanceDirection": "CREDIT",
          "balanceAmount": 1000.50
        }
      ]
    }

## 错误响应

所有错误统一返回 JSON，不暴露堆栈，例如：

    {
      "timestamp": "2026-09-19T10:00:00Z",
      "status": 404,
      "code": "VOUCHER_NOT_FOUND",
      "message": "凭证不存在: JV-99999999",
      "path": "/api/vouchers/JV-99999999"
    }

主要业务错误码：`ACCOUNT_ALREADY_EXISTS`、`ACCOUNT_NOT_FOUND`、`ACCOUNT_DISABLED`、`PERIOD_ALREADY_EXISTS`、`PERIOD_NOT_FOUND`、`PERIOD_CLOSED`、`VOUCHER_NOT_BALANCED`、`VOUCHER_NOT_FOUND`、`VOUCHER_ALREADY_REVERSED`、`REVERSAL_NOT_ALLOWED`、`IDEMPOTENCY_CONFLICT`、`VALIDATION_ERROR`。
