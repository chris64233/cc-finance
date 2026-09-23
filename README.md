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
| POST | `/api/periods/{periodCode}/close` | 关账（校验损益结清，幂等，重复关账返回当前结果） |
| POST | `/api/periods/{periodCode}/reopen` | 反关账（需非空原因，幂等，重复反关账返回当前结果） |

期间规则：

- 会计期间按月份管理，创建时传入 `year`（1900-2100）和 `month`（1-12），系统生成 `YYYY-MM` 格式的期间编码，并保存开始日期、结束日期、状态、创建时间、关账时间、反关账时间和反关账原因。
- 状态只支持 `OPEN` 和 `CLOSED`，新建期间默认为 `OPEN`；同一 `year` + `month` 只能有一条记录（数据库唯一约束）。
- 关账只允许把 `OPEN` 期间改为 `CLOSED` 并记录关账时间；对已关账期间重复调用直接返回当前结果，不重新校验、不重复写入，关账时间保持不变。
- 查询期间（单个或列表）会返回 `closedAt`、`reopenedAt`、`reopenReason` 字段；未发生关账或反关账时对应字段为 `null`。

关账规则：

- 关账前校验该期间内所有已入账（`POSTED`）凭证中的收入（`REVENUE`）和费用（`EXPENSE`）科目：每个科目的累计借方发生额与累计贷方发生额必须相等（余额为零）才允许关账。
- 余额只统计凭证日期落在该期间起止日期（含首尾）内的 `POSTED` 凭证，冲销凭证与普通凭证一样参与计算；其他期间的凭证不影响本期间关账。
- 已停用科目仍按当前科目类别参与校验，不会被跳过。
- 只要有一个损益科目余额不为零，关账返回 409（`PERIOD_PROFIT_LOSS_NOT_CLEARED`），期间保持 `OPEN`，不会写入关账时间。
- 期间没有凭证，或只有资产、负债、所有者权益类科目发生额时，可以正常关账。
- 关账与同一期间的凭证入账通过数据库事务和期间行级悲观锁保证并发一致：入账先成功则关账基于包含该凭证的最新余额判断；关账先成功则入账返回 409（`PERIOD_CLOSED`），不会出现遗漏未结清损益的关账结果。

反关账规则：

- 反关账只允许把 `CLOSED` 期间重新改为 `OPEN`；请求必须提供非空的反关账原因 `reason`（最长 500 字符），为空或缺失返回 400（`VALIDATION_ERROR`）。
- 反关账成功后清空原关账时间 `closedAt`，并记录本次反关账时间 `reopenedAt` 和原因 `reopenReason`，查询期间时一并返回。
- 只能重新打开当前最新的已关账期间：如果存在期间编码更晚且状态为 `CLOSED` 的期间，返回 409（`PERIOD_REOPEN_NOT_ALLOWED`），当前期间保持 `CLOSED` 不变；需先反关账更晚的期间。
- 已经生成下期期初余额结转凭证的源期间不能反关账，返回 409（`PERIOD_REOPEN_NOT_ALLOWED`），避免下期期初数据与源期间失去一致性。
- 对已经 `OPEN` 的期间重复反关账，直接返回当前结果，不覆盖已有反关账信息、不重复写入。
- 反关账成功后，该期间重新允许凭证入账，也可以再次按现有规则关账。
- 反关账与同一期间的凭证入账通过数据库事务和期间行级悲观锁保证并发一致：凭证不会写入仍处于 `CLOSED` 状态的期间，反关账提交后新的入账才能成功。

创建期间：

    curl -X POST http://localhost:8080/api/periods \
      -H 'Content-Type: application/json' \
      -d '{"year": 2026, "month": 9}'

关账：

    curl -X POST http://localhost:8080/api/periods/2026-09/close

反关账：

    curl -X POST http://localhost:8080/api/periods/2026-09/reopen \
      -H 'Content-Type: application/json' \
      -d '{"reason": "补录9月凭证"}'

### 会计科目

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/accounts` | 创建科目（编码唯一，重复返回 409） |
| GET | `/api/accounts` | 查询全部科目 |
| GET | `/api/accounts/{code}` | 按编码查询科目，不存在返回 404 |
| POST | `/api/accounts/{code}/deactivate` | 停用科目（幂等，重复停用返回当前结果） |

科目类别：`ASSET`（资产）、`LIABILITY`（负债）、`EQUITY`（所有者权益）、`REVENUE`（收入）、`EXPENSE`（费用）。

停用规则：

- 停用前根据该科目所有已入账（`POSTED`）凭证的分录计算累计借方和累计贷方发生额，冲销凭证与普通凭证一样参与计算；只有两者相等（余额为零）时才允许停用。
- 余额不为零时返回 409（`ACCOUNT_BALANCE_NOT_ZERO`），科目状态保持不变；科目不存在时返回 404（`ACCOUNT_NOT_FOUND`）。
- 停用成功后返回更新后的科目信息（`enabled=false`）；对已停用科目重复调用直接返回当前结果，不重复写入、不报错。
- 已停用科目不能再用于新的凭证入账或冲销（返回 422，`ACCOUNT_DISABLED`）；已有凭证、历史查询和试算平衡表不受停用影响。
- 停用与凭证入账通过数据库事务和科目行级悲观锁保证并发一致：停用先成功则引用该科目的入账失败；入账先成功则停用基于包含该凭证的最新余额判断，不会出现科目已停用却又写入新凭证的情况。

创建科目：

    curl -X POST http://localhost:8080/api/accounts \
      -H 'Content-Type: application/json' \
      -d '{"code": "1001", "name": "银行存款", "category": "ASSET"}'

查询科目：

    curl http://localhost:8080/api/accounts/1001

停用科目：

    curl -X POST http://localhost:8080/api/accounts/1001/deactivate

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
- 科目停用与引用该科目的凭证入账同样通过行级悲观锁串行化：入账按科目编码升序加锁并校验启用状态，避免与停用操作并发时产生不一致。

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
- 冲销会生成新的入账凭证，因此原凭证分录引用的科目必须仍处于启用状态；科目已停用时返回 422（`ACCOUNT_DISABLED`），不会留下任何凭证或分录数据。

凭证冲销：

    curl -X POST http://localhost:8080/api/vouchers/JV-00000001/reversal \
      -H 'Content-Type: application/json' \
      -d '{"bizKey": "REV-001", "voucherDate": "2026-09-20", "summary": "冲销销售回款"}'

### 发生额试算平衡表

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/periods/{periodCode}/trial-balance` | 按会计期间查看发生额试算平衡表，期间不存在返回 404 |

试算平衡表规则：

- 按期间编码实时统计，不保存额外的报表数据；`OPEN` 和 `CLOSED` 期间都可以查询，查询不影响期间状态。
- 统计范围为凭证日期落在该期间起止日期（含首尾）内的所有 `POSTED` 凭证；冲销凭证与普通凭证一样参与统计，不做排除。
- 每个在该期间有发生额的科目返回一行（仅在其他期间有分录的科目不出现），按科目编码升序排列，字段包括：科目编码 `accountCode`、科目名称 `accountName`、科目类别 `category`、借方发生额 `debitAmount`、贷方发生额 `creditAmount`、余额方向 `balanceDirection` 和余额金额 `balanceAmount`。
- 余额方向：借方发生额大于贷方为 `DEBIT`，贷方大于借方为 `CREDIT`，两者相等为 `NONE`；余额金额为借贷差额的绝对值。
- 返回的借方发生额合计 `debitTotal` 与贷方发生额合计 `creditTotal` 必须相等；没有凭证的期间返回空明细 `items`，两个合计均为 `0.00`。
- 所有金额统一保留两位小数；科目即使已被停用，只要期间内存在历史分录仍会出现在报表中（科目名称、类别取当前档案）。
- 原凭证与冲销凭证落在同一期间时，两边发生额都保留并分别计入合计，对应科目的余额按净额正确抵消。

查询试算平衡表：

    curl http://localhost:8080/api/periods/2026-09/trial-balance

响应示例：

    {
      "periodCode": "2026-09",
      "debitTotal": 2001.00,
      "creditTotal": 2001.00,
      "items": [
        {
          "accountCode": "1001",
          "accountName": "银行存款",
          "category": "ASSET",
          "debitAmount": 1000.50,
          "creditAmount": 1000.50,
          "balanceDirection": "NONE",
          "balanceAmount": 0.00
        }
      ]
    }

### 期间损益结转

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/periods/{periodCode}/profit-loss-carry-forward` | 对 OPEN 期间发起损益结转，生成一张已入账结转凭证 |

损益结转规则：

- 只允许对处于 `OPEN` 状态的期间发起损益结转：期间不存在返回 404（`PERIOD_NOT_FOUND`），期间已关账返回 409（`PERIOD_CLOSED`）。
- 请求必须指定承接损益的所有者权益科目 `equityAccountCode`（非空，缺失返回 400 `VALIDATION_ERROR`）；该科目必须存在、处于启用状态且类别为 `EQUITY`，否则分别返回 422 `ACCOUNT_NOT_FOUND`、`ACCOUNT_DISABLED`、`ACCOUNT_NOT_EQUITY`。
- 系统在持有期间行级悲观锁的事务内，汇总该期间起止日期（含首尾）内全部已入账（`POSTED`）凭证中收入（`REVENUE`）和费用（`EXPENSE`）科目的借贷发生额：每个余额不为零的损益科目生成一条方向相反、金额等于其余额的分录，使这些科目的期末余额归零；分录按科目编码升序排列。
- 全部损益分录的借贷差额（净损益）计入指定的所有者权益科目：净收益贷记权益科目，净亏损借记权益科目，整张凭证借贷必然平衡；差额为零时不生成金额为零的权益分录。
- 结转凭证日期固定为期间最后一天，状态为 `POSTED`，摘要为“损益结转 {期间编码}”；响应通过 `carryForwardPeriodCode` 和 `carryForwardEquityAccountCode` 标识结转凭证及其承接科目。
- 如果该期间收入和费用科目已经全部结清（含期间没有凭证、只有资产/负债/权益发生额的情况），不生成空凭证，返回 409 和稳定业务错误码 `PROFIT_LOSS_ALREADY_CLEARED`。
- 同一期间最多只能有一张损益结转凭证（`carry_forward_period_code` 数据库唯一约束）：使用相同承接科目重复请求时直接返回首次生成的凭证（幂等）；改用其他承接科目重复请求时返回 409 和稳定业务错误码 `PROFIT_LOSS_CARRY_FORWARD_CONFLICT`。
- 任何校验失败都在同一事务内回滚，不会留下凭证或分录数据。
- 损益结转与普通凭证入账、期间关账复用同一套数据库事务和期间行级悲观锁：结转金额基于锁定后的完整期间数据计算，不会遗漏同时提交的凭证；已关账期间不会生成结转凭证，结转成功后按现有规则关账可以成功。

发起损益结转：

    curl -X POST http://localhost:8080/api/periods/2026-09/profit-loss-carry-forward \
      -H 'Content-Type: application/json' \
      -d '{"equityAccountCode": "3001"}'

### 期间余额结转

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/periods/{periodCode}/balance-carry-forward` | 对已关账期间发起余额结转，在下一自然月第一天生成一张已入账期初凭证 |

余额结转规则：

- 只允许对处于 `CLOSED` 状态的源期间发起余额结转：源期间不存在返回 404（`PERIOD_NOT_FOUND`），源期间尚未关账返回 409（`PERIOD_NOT_CLOSED`）。
- 下一个自然月的会计期间必须已经存在且处于 `OPEN` 状态：目标期间不存在返回 409（`TARGET_PERIOD_NOT_FOUND`），目标期间已关账返回 409（`TARGET_PERIOD_CLOSED`）。
- 系统在同一事务内按期间编码升序对源期间和目标期间加行级悲观锁，汇总凭证日期不晚于源期间结束日的全部已入账（`POSTED`）凭证中资产（`ASSET`）、负债（`LIABILITY`）和所有者权益（`EQUITY`）科目的累计借贷发生额；收入（`REVENUE`）和费用（`EXPENSE`）科目不参与。
- 每个期末余额不为零的科目生成一条期初分录，方向和金额与期末余额一致（借方余额借记，贷方余额贷记），分录按科目编码升序排列；整张凭证借贷必然平衡，金额统一保留两位小数。
- 期初凭证日期固定为目标期间第一天（跨年时为下一年 1 月 1 日），状态为 `POSTED`，摘要为“期初余额结转自 {源期间编码}”，响应通过 `balanceCarryForwardPeriodCode` 标识其来源期间。
- 如果资产、负债和所有者权益科目余额全部为零，不生成空凭证，返回 409 和稳定业务错误码 `BALANCE_ALREADY_CLEARED`。
- 同一源期间只能生成一张期初凭证（`balance_carry_forward_period_code` 数据库唯一约束）：重复请求（即使目标期间随后被关账）直接返回第一次生成的凭证，不重复写入；并发重复请求由唯一约束兜底，最多落一张凭证。
- 任何校验失败都在同一事务内回滚，不会留下凭证或分录数据。
- 已经生成期初凭证的源期间不能再反关账，返回 409 和稳定业务错误码 `PERIOD_REOPEN_NOT_ALLOWED`，避免下期期初数据与源期间失去一致性。
- 余额结转、源期间反关账和目标期间关账通过同一套数据库事务和期间行级悲观锁串行化：结转只能基于仍为 `CLOSED` 的源期间、只能写入仍为 `OPEN` 的目标期间。并发结束后不会出现源期间已反关账但期初凭证已生成，或目标期间已关账后仍写入期初凭证的情况；失败的一方在状态恢复后（如重新关账/反关账目标期间）可以补做结转。

发起余额结转：

    curl -X POST http://localhost:8080/api/periods/2026-09/balance-carry-forward

## 错误响应

所有错误统一返回 JSON，不暴露堆栈，例如：

    {
      "timestamp": "2026-09-19T10:00:00Z",
      "status": 404,
      "code": "VOUCHER_NOT_FOUND",
      "message": "凭证不存在: JV-99999999",
      "path": "/api/vouchers/JV-99999999"
    }

主要业务错误码：`ACCOUNT_ALREADY_EXISTS`、`ACCOUNT_NOT_FOUND`、`ACCOUNT_DISABLED`、`ACCOUNT_NOT_EQUITY`、`ACCOUNT_BALANCE_NOT_ZERO`、`PERIOD_ALREADY_EXISTS`、`PERIOD_NOT_FOUND`、`PERIOD_CLOSED`、`PERIOD_NOT_CLOSED`、`PERIOD_PROFIT_LOSS_NOT_CLEARED`、`PERIOD_REOPEN_NOT_ALLOWED`、`TARGET_PERIOD_NOT_FOUND`、`TARGET_PERIOD_CLOSED`、`BALANCE_ALREADY_CLEARED`、`PROFIT_LOSS_ALREADY_CLEARED`、`PROFIT_LOSS_CARRY_FORWARD_CONFLICT`、`VOUCHER_NOT_BALANCED`、`VOUCHER_NOT_FOUND`、`VOUCHER_ALREADY_REVERSED`、`REVERSAL_NOT_ALLOWED`、`IDEMPOTENCY_CONFLICT`、`VALIDATION_ERROR`。
