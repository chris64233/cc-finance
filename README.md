# cc-finance

cc-finance 是一个面向企业财务场景的 Spring Boot 后端项目，当前提供会计科目管理与记账凭证入账能力。

## 环境

- Java 21
- Maven Wrapper
- Spring Boot 4.1.1
- Spring Data JPA + H2 内存数据库

## 常用命令

运行测试：

    ./mvnw test

启动应用：

    ./mvnw spring-boot:run

应用默认监听 `8080` 端口。

## 主要接口

### 会计科目

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/accounts` | 创建科目（编码唯一，重复返回 409） |
| GET | `/api/accounts/{code}` | 按编码查询科目（不存在返回 404） |
| GET | `/api/accounts` | 查询全部科目 |

科目字段：`code`（科目编码，唯一）、`name`（名称）、`category`（类别：`ASSET` / `LIABILITY` / `EQUITY` / `INCOME` / `EXPENSE`）、`enabled`（启用状态，默认 `true`）。编码和名称会去除首尾空白，去空白后不能为空。

### 记账凭证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/vouchers` | 凭证入账（状态为 `POSTED`） |
| GET | `/api/vouchers/{voucherNo}` | 按凭证号查询完整凭证（不存在返回 404） |

入账规则：

- 每张凭证至少两条分录，分录包含 `accountCode`、`direction`（`DEBIT` / `CREDIT`）、`amount`（大于 0，最多两位小数，BigDecimal 处理）和可选 `summary`。
- 入账前校验借方合计等于贷方合计，且所有科目存在并处于启用状态；任一条件不满足则整张凭证不落库。
- 凭证与分录在同一事务内写入，入账成功后生成唯一且不可变的凭证号（如 `JV-00000001`），返回的分录顺序与请求一致。
- `bizId`（业务唯一号）用于幂等，并有数据库唯一约束：相同 `bizId` 且请求内容一致时返回首次创建的凭证；内容不一致时返回 409。

## curl 示例

创建科目：

```bash
curl -X POST http://localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"code": "1001", "name": "银行存款", "category": "ASSET"}'
```

查询科目：

```bash
curl http://localhost:8080/api/accounts/1001
```

凭证入账：

```bash
curl -X POST http://localhost:8080/api/vouchers \
  -H 'Content-Type: application/json' \
  -d '{
    "bizId": "ORDER-20260919-001",
    "voucherDate": "2026-09-19",
    "summary": "销售回款",
    "entries": [
      {"accountCode": "1001", "direction": "DEBIT", "amount": 100.00, "summary": "收款"},
      {"accountCode": "6001", "direction": "CREDIT", "amount": 100.00}
    ]
  }'
```

查询凭证：

```bash
curl http://localhost:8080/api/vouchers/JV-00000001
```

## 错误响应

接口错误统一返回 JSON，包含 HTTP 状态、稳定的业务错误码、错误信息和请求路径，不暴露堆栈：

```json
{
  "status": 409,
  "code": "BIZ_ID_CONFLICT",
  "message": "业务唯一号已存在且请求内容不一致: ORDER-20260919-001",
  "path": "/api/vouchers",
  "timestamp": "2026-09-19T12:00:00Z"
}
```

主要错误码：`VALIDATION_FAILED`（参数校验失败）、`ACCOUNT_CODE_CONFLICT`（科目编码重复）、`ACCOUNT_NOT_FOUND`（科目不存在）、`ACCOUNT_DISABLED`（科目已停用）、`VOUCHER_UNBALANCED`（借贷不平）、`VOUCHER_NOT_FOUND`（凭证不存在）、`BIZ_ID_CONFLICT`（业务唯一号冲突）。
