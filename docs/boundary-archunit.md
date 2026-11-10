# 边界方案增补：OrderFacade 改名 + ArchUnit 边界守护

## 一、OrderGateway → OrderFacade 改名

原命名 "Gateway" 易被理解为 API 网关中间件（队列/路由/协议转换），实际是 oms 包内一个十行的门面类（同步方法调用，转发到 OrderCreateService）。更名后语义准确：**GoF 门面模式——给外部一个简化的统一入口，屏蔽内部复杂度**。

- `docs/flows/19/20` 时序图中的 OrderGateway participant 改名为 OrderFacade
- `boundary-plan.md` 第 2 层同步改名
- 实施会话编码时直接用 OrderFacade

## 二、ArchUnit 边界守护（防止边界被腐蚀）

### 是什么
ArchUnit = 一个 Java 单测框架：**用写单元测试的方式给架构立规矩，违反规矩 = 测试红 = 构建失败**。

### 解决的问题
"portal 只能通过 OrderFacade 接口访问 oms" 这条规矩写在文档里没有强制力——总有开发者图方便直接 `import OrderCreateService`（IDE 自动补全一点就进来了）。Code Review 能拦但靠人眼。ArchUnit 把规矩变成**机器强制的测试**。

### qiqi-mall 的守护规则（进 e2e 同级的架构测试目录）

```java
// ArchArchitectureTest.java (JUnit)
@AnalyzeClasses(packages = "com.qiqi.mall", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {

    // 规则1: portal 禁止直接依赖 oms 的实现类(只许依赖 OrderFacade 接口)
    @Test
    void portal_only_touches_oms_via_facade() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.qiqi.mall");
        ArchRule rule = noClasses().that().resideInAPackage("..mall.portal..")
            .should().dependOnClassesThat().resideInAPackage("..mall.oms..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.qiqi.mall.oms.service.OrderCreateService");
        // 例外: 允许依赖 OrderFacade 接口
        rule = rule.because("商城与OMS的边界:只许通过 OrderFacade 接口交互(boundary-plan.md 铁律1)");
        rule.check(classes);
    }

    // 规则2: oms 禁止依赖 member/portal 的内部实现(收货人信息只能用快照)
    @Test
    void oms_is_isolated_from_portal_internals() { ...对称规则... }

    // 规则3: catalog/member 共享中心只读(oms 侧禁止写会员表)
}
```

### 违规时的效果
开发者写了 `import com.qiqi.mall.oms.service.OrderCreateService;` →
`mvn test` 直接失败，报错信息含 "because: 商城与OMS的边界..." —— **架构违规像单测失败一样当场暴露，不用等 Code Review**。

### 依赖
```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.3.0</version>
  <scope>test</scope>
</dependency>
```

### 价值定位
- **学习项目里它防的不是别人，是 AI 会话自己**——多模型并行改代码时，边界最容易被动无意破坏；一条测试让每次 mvn test 自动巡检边界
- **面试价值**："边界不是画在图上的,是 ArchUnit 测试强制的" —— 边界治理的完整闭环:模型(排序方式) → 实现(Facade) → 强制(ArchUnit)
