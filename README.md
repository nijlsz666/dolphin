# 牛牛 · Dolphin Stock Analysis

一个面向 A 股主板的股票池筛选、因子评分、AI 辅助分析和模拟交易工作台。

项目采用 Vue 3 + Ant Design Vue 构建前端，Spring Boot 提供 REST API，MySQL 保存行情、策略、分析快照和模拟交易数据。它适合作为个人研究工具、量化策略原型或全栈项目示例使用。
<img width="2996" height="1388" alt="image" src="https://github.com/user-attachments/assets/df22cbb7-7c8b-4bfe-8a2c-31699f701b7d" />

> 当前项目仍处于 MVP 阶段。行情、新闻、公告和 AI 输出都可能存在延迟、缺失或错误，所有结果仅供研究和学习，不构成任何投资建议，也不会自动连接券商执行真实交易。

## 功能概览

- **推荐中心**：对沪深主板 A 股进行硬过滤和多因子评分，支持最低价、最高价和分析日期筛选。
- **股票池管理**：手工添加、批量加入、移除股票，查看持仓、历史交易、计划操作和重大事件。
- **因子模型**：趋势、动量、量价、基本面、资金面、质量/估值等模块合计 100 分，并叠加风险惩罚。
- **交易计划**：支持买入和卖出计划，结合当前持仓成本、总资产、仓位上限和止损条件给出模拟分析。
- **技术指标**：接入真实历史 K 线后计算 MA、RSI、MACD、量比和阶段高点等指标。
- **AI 辅助分析**：支持配置 OpenAI 兼容接口，用于公司分析、实时评分、交易计划分析和成功率模型；AI 不可用时仍可使用非 AI 功能。
- **行情与公告**：支持实时行情、历史 K 线、新闻热点、分红/派息等公告信息，并提供行情源优先级和备用源配置。
- **模型维护**：支持刷新公司资料、行情数据、新闻、分析模型、成功率模型和历史准确率。
- **数据可追溯**：将因子快照、AI 分析、模型版本、交易执行和回测订单写入数据库，便于复盘。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vite、Ant Design Vue |
| 后端 | Java 17、Spring Boot 3.4、Spring Web、Spring JDBC |
| 数据库 | MySQL 8+ |
| 数据源 | 可配置的公开行情、历史 K 线、新闻和公告接口 |
| AI | OpenAI 兼容的 Chat Completions 接口，可选 |

## 项目结构

```text
.
├── README.md
├── service/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/dolphin/stock/
│       │   ├── model/       # API 数据模型
│       │   ├── service/     # 行情、策略、AI、持仓和数据访问服务
│       │   └── web/         # REST Controller
│       └── resources/
│           ├── application.yml
│           └── db/schema.sql
└── web/
    ├── package.json
    ├── vite.config.js
    └── src/
        ├── App.vue
        ├── main.js
        └── style.css
```

## 环境要求

- JDK 17+
- Maven 3.9+（或使用 IDE 内置 Maven）
- Node.js 18+
- MySQL 8+
- 可选：OpenAI 兼容模型服务的 API Key

## 快速开始

### 1. 创建数据库

先创建数据库，再导入初始化表结构：

```sql
CREATE DATABASE dolphin_stock
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p dolphin_stock \
  < service/src/main/resources/db/schema.sql
```

### 2. 配置后端

推荐通过环境变量提供数据库连接信息。至少设置数据库密码：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/dolphin_stock?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
export DB_USERNAME='root'
export DB_PASSWORD='请替换为你的数据库密码'
```

启动后端：

```bash
cd service
mvn spring-boot:run
```

后端默认地址为 `http://localhost:8080`，健康检查：

```bash
curl http://localhost:8080/api/health
```

### 3. 初始化系统数据

首次启动后，可按需初始化系统状态和基础分析数据：

```bash
curl -X POST 'http://localhost:8080/api/system/initialization'
```

也可以指定分析日期：

```bash
curl -X POST 'http://localhost:8080/api/system/initialization?asOf=2026-08-18'
```

### 4. 启动前端

在另一个终端执行：

```bash
cd web
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 已配置将 `/api` 请求代理到 `http://localhost:8080`。

生产构建：

```bash
cd web
npm run build
npm run preview
```

## 配置项

后端配置位于 `service/src/main/resources/application.yml`，支持通过环境变量覆盖：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | 本机 MySQL `dolphin_stock` | JDBC 连接地址 |
| `DB_USERNAME` | `root` | 数据库用户 |
| `DB_PASSWORD` | 空 | 数据库密码，建议始终显式设置 |
| `DB_POOL_MAX_SIZE` | `10` | 数据库连接池最大连接数 |
| `REALTIME_QUOTES_ENABLED` | `true` | 是否启用实时行情 |
| `REALTIME_QUOTES_TIMEOUT_SECONDS` | `8` | 实时行情请求超时时间 |
| `HISTORICAL_KLINE_ENDPOINT` | 腾讯公开 K 线接口 | 历史日 K 接口 |
| `HISTORICAL_KLINE_TIMEOUT_SECONDS` | `3` | 历史 K 线超时时间 |
| `HISTORICAL_KLINE_CACHE_SECONDS` | `300` | 历史 K 线缓存时间 |
| `DIVIDEND_EVENTS_ENDPOINT` | 东方财富公开接口 | 分红/派息公告接口 |
| `NEWS_FEED_URLS` | 新浪财经 RSS | 新闻源，多个地址用逗号分隔 |

AI 服务和行情源也可以在前端工作台中配置。AI Key 会保存到数据库，页面只展示脱敏值；部署时请限制数据库权限，并不要把 API Key 写入代码或提交到 Git。

## 选股与交易逻辑说明

### 股票范围

默认只扫描沪深主板 A 股：

- 上海：`600`、`601`、`603`、`605`
- 深圳：`000`、`001`、`002`、`003`

默认排除北京证券交易所、科创板 `688/689` 和创业板 `300/301`。

### 硬过滤

系统会综合检查 ST、停牌、上市天数、价格、流动性和涨跌停可交易性。实时行情不可用时返回 `NETWORK_ERROR` 和空价格，不使用虚拟价格生成交易建议。

### 评分模型

| 模块 | 权重 |
| --- | ---: |
| 趋势 | 25 |
| 动量 | 15 |
| 量价 | 10 |
| 基本面 | 20 |
| 资金面 | 10 |
| 质量/估值 | 20 |
| 合计 | 100 |

风险惩罚会影响最终分数。买入区间默认参考 MA20 附近的候选区间，不使用固定价格追涨；硬止损和盈利回撤止损的优先级高于普通卖出评分。

对于没有真实历史 K 线或财务数据的手工股票，系统会明确标记未接入的数据模块，不使用占位指标伪造分数或交易价格。

## API 入口

后端 API 前缀为 `/api`，常用接口如下：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/health` | 健康检查 |
| `GET` | `/api/recommendations` | 获取推荐结果 |
| `POST` | `/api/recommendations/refresh` | 刷新推荐结果 |
| `GET` | `/api/managed-pool` | 获取我的股票池 |
| `POST` | `/api/managed-pool` | 加入股票池 |
| `DELETE` | `/api/managed-pool/{code}` | 移除股票池成员 |
| `GET` | `/api/managed-pool/{code}/position` | 查询持仓 |
| `PUT` | `/api/managed-pool/{code}/position` | 更新模拟持仓 |
| `PUT` | `/api/managed-pool/{code}/planned-order` | 保存交易计划 |
| `POST` | `/api/managed-pool/{code}/planned-order/analyze` | 分析交易计划 |
| `POST` | `/api/managed-pool/{code}/planned-order/confirm` | 确认模拟交易 |
| `GET` | `/api/strategy-config` | 获取策略配置 |
| `PUT` | `/api/strategy-config` | 更新策略配置 |
| `GET` | `/api/scoring-model` | 获取评分模型 |
| `GET` | `/api/account/assets` | 获取账户资产 |
| `PUT` | `/api/account/assets` | 更新账户资产 |
| `GET` | `/api/ai/config` | 获取 AI 配置（Key 脱敏） |
| `PUT` | `/api/ai/config` | 更新 AI 配置 |

完整接口定义可直接查看 `service/src/main/java/com/dolphin/stock/web/StockPoolController.java`。

## 数据库说明

`service/src/main/resources/db/schema.sql` 会初始化以下类型的数据表：

- 行情和基础资料：日 K、分钟行情、复权行情、财务指标、公司资料
- 市场信息：新闻公告、热点、分红事件、行情源配置
- 分析数据：因子快照、AI 分析、实时缓存、组合分析、模型快照和模型使用记录
- 交易数据：股票池成员、持仓、计划订单、交易执行、交易信号
- 策略与回测：策略配置、市场规则、回测运行和回测订单

历史财务数据必须遵守 `available_at <= 回测时点`，避免未来数据泄漏。公开行情接口的返回格式和可用性可能变化，生产环境建议自行维护稳定的数据适配器或代理服务。

## 开源发布前检查

发布到 GitHub 前，建议至少完成以下检查：

- 确认数据库地址、数据库密码、AI API Key 和个人数据没有出现在代码、提交历史或日志中。
- 为生产环境关闭宽泛的跨域配置，并增加登录鉴权、权限控制和请求限流。
- 不要把 MySQL 端口直接暴露到公网；为应用创建最小权限数据库用户。
- 根据实际用途补充 `LICENSE` 文件；当前仓库未预置许可证。
- 检查 `service/target`、`web/node_modules`、`web/dist` 和 `.DS_Store` 没有被提交。
- 在发布说明中注明外部行情、新闻和 AI 服务的使用条款及限流风险。

## 开发检查

后端编译检查：

```bash
cd service
mvn test
```

前端构建检查：

```bash
cd web
npm run build
```

## 贡献方式

欢迎通过 Issue 报告问题、提出策略改进建议或提交 Pull Request。提交代码前请说明：

1. 修改背景和目标；
2. 是否影响数据库结构或已有 API；
3. 如何验证修改；
4. 是否涉及外部数据源、AI 服务或潜在的隐私/安全风险。

## 许可证

当前仓库尚未包含许可证文件。若希望允许他人自由使用、修改和分发，建议在公开前根据项目实际需求补充 MIT、Apache-2.0 或其他合适的开源许可证。
