# 边界划分规划：商城(mall) 与 OMS 的分界线

> 回答"边界怎么划"——按三个层次递进：数据边界(ER) → 交互边界(时序) → 代码边界(包结构)。
> 现状盘点已完成：order.member_id 直连商城会员表、包结构平铺无分层、接口前缀已是 /portal|/admin 双轨。

## 一、核心判断：哪些要改，哪些不用改

### 不用改的(重要的"不用")
- **ER 图 10~13 的表结构**：划边界**不动数据表**——订单表属于谁,取决于它部署在哪个服务里,而不是表结构本身。
  第一/二阶段(逻辑分离)ER 图完全不变;只有第三阶段(物理拆库)才可能复制/拆分表
- **现有业务逻辑**：状态机/守卫/快照/幂等全部平移到新包,一行不改
- **e2e 测试**：URL 不变就全绿

### 要改的三层(按顺序)

## 二、第 1 层：代码边界(第一刀,纯目录重构,零行为变化)

现状 `controller/` 平铺 22 个文件 → 按归属分两个模块包:

```
com.qiqi.mall
├── portal/                    ← 商城侧(C端交易意图)
│   ├── controller/  PortalAuth/PortalCart/PortalCatalog/PortalMember/PortalMemberAddress/PortalOrder/PortalReturn
│   └── service/     (只保留"提交订单意图"相关)
├── oms/                       ← 订单中台(履约执行,渠道无关)
│   ├── controller/  AdminOrder/ReturnApply/ReturnReason/OrderSetting(发货/审核/调度)
│   ├── service/     OrderCreateService/OrderTransactionalCreator/OrderSnGenerator/状态机守卫
│   └── (未来) platform/ integration/  ← OMS化新增的平台适配层放这里
├── shared/                    ← 两边共用的地基
│   ├── common/ common现有内容(Result/异常/OrderSn)
│   ├── security/ 现有内容
│   └── storage/ MinIO
├── catalog/                   ← 商品中心(商城和OMS共同依赖,两边都读它)
│   ├── controller/ Product/ProductType/Attribute/Category/Sku/AdminUpload
│   └── service/ entity/ mapper/
└── member/                    ← 会员中心(商城的买家即OMS的收货人,共享)
    └── controller/ Member/MemberAddress ...
```

**边界规则(写死,后续所有开发遵守)**:
1. portal 包**禁止**直接访问 oms 的 service——只能通过内部接口(OrderGateway,见第2层)
2. oms 包**禁止**直接读 member 会员表——需要收货人信息时,订单表里的**快照字段**就是解耦手段(你已有的设计!)
3. catalog/member 是共享中心,两边都可读,只有商城可写(member 状态由商城管)

### 验收: 纯重构,编译+e2e全绿+URL不变,业务零变化

## 三、第 2 层：交互边界(OMS化第一刀时引入"订单交付"概念)

现状: `PortalOrderController.create()` 直接调 OrderCreateService 创建订单 → 商城和OMS揉在一起。

改造: 引入**订单交付接口**(内部API,逻辑上是一条"商城→OMS"的边界线):

```
时序变化(对标时序图4-场景2):
旧:  买家→portal→OrderCreateService.create()→扣库存→写订单   (一个进程一竿子到底)
新:  买家→portal→OrderGateway.submit(标准化订单DTO)──┐
                                                    │ 内部接口调用(第一阶段)
                                                    │ HTTP调用(第三阶段拆服务后)
                                                    ▼
                                            oms/OrderCreateService.create()
                                            (OMS: 幂等/审核/拆单/扣库存/写单)

ER/表变化: order 表加 source 列(CHANNEL_SELF/CHANNEL_TAOBAO/...) 标记渠道来源
           platform_order 原始报文表(OMS化第一步,报文快照=快照原则的延伸)
```

**ER 需要新增的**(第二阶段OMS化时):
- `order.source` 渠道来源列(现在只有一个渠道=SELF)
- `platform_order` 平台原始订单报文表(快照原则:渠道报文原样存档)
- `sku_mapping` 平台SKU↔系统SKU映射(产品配对)

**时序图需要新增的**: 图4(场景2)的下单链在 OrderGateway 处分叉为两条——商城内继续支付流程,OMS 侧开始履约流程(审核→拆单→下发)。ER 图 10~13 结构不变,只加上述列/表。

## 四、第 3 层：物理边界(第三阶段,可选,先不做)

oms 模块独立成 `qiqi-oms-server` 独立进程独立库:
- 交互从内部接口 → HTTP/MQ
- 共享表(member/catalog)通过 API 读取(或做数据同步)
- 此时才真正出现"两个系统",才有独立部署/独立发布的能力
- 你已有的双实例+nginx+Redis 经验直接复用

## 五、执行顺序与文档动作

| 阶段 | 动作 | 文档动作 |
|---|---|---|
| 现在 | 第1层包重构 | 图1架构图标注新包结构;ER不动;新增本文档 |
| OMS化第一步 | 第2层引入 OrderGateway + source列 + platform_order | 图10/12加2实体;新增"抓单时序图";场景图加审核段 |
| OMS化第二~五步 | 拆单/下发/对账 | 对应时序图逐张补 |
| 第三阶段 | 物理拆分 | 图1重画为跨服务架构;全链路trace |

## 一句话边界宣言

> **portal 管交易意图(下单/支付/展示),oms 管履约执行(审核/拆单/发货/售后调度),catalog+member 是共享中心,快照是跨边界的解耦手段。**
> 判断归属的方法:这件事离开商城还成不成立?成立→oms;不成立(如购物车/支付页)→portal。
