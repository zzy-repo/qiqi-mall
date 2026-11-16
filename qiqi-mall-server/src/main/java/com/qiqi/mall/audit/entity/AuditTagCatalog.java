package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核标目录（{@code audit_tag_catalog}）：标码 {@code tag_code} 的**字典**。
 *
 * <h3>这张表回答什么</h3>
 * 「STOCK_SHORT 中文叫什么、属于哪一组、会不会拦住下单、业务要不要看见」——
 * 没有它，前端只能把 FROZEN / NO_POLICY_MATCH 这些英文码硬编码进自己的常量表，
 * 于是"加一个标"要改前端、改后端、改三处文案，而且三处必然漂移。
 *
 * <h3>它不是什么（最容易混的一条）</h3>
 * <b>不是标实例</b>。谁身上有什么标，仍然只在 {@code order_audit_tag} 里 —— 那才是事实。
 * catalog 里登记 {@code STOCK_SHORT} 不代表任何单身上有它。
 * 两个表之间**没有外键**，也刻意不加：标码允许先落库、目录后补（否则每加一个标都要先跑迁移）。
 *
 * <h3>category 的两档（本期只落地一档）</h3>
 * <ul>
 *   <li>{@code 结论}：审核跑完的产物（BLOCKED / FROZEN / STOCK_SHORT / COST_MISSING /
 *       AUDIT_PENDING / NO_POLICY_MATCH）—— <b>本期只实现这一档的自动落标</b>；</li>
 *   <li>{@code 特征}：标记策略、平台旗帜、人工打标（客诉客户、绿旗急发、双地址…）。
 *       与"过没过"无关 —— 这些单打了标照样能自动审核通过。本期只留位置，
 *       落标路径是期 2 的检测点/标记策略。</li>
 * </ul>
 * 分档的理由不是分类癖：**标有两个来源**。把来源混在一起，将来"审核跑完落标"这条路径
 * 会顺手把特征标也当成审核产物重算/清除，那是灾难。
 *
 * <h3>为什么没有 is_primary / priority 列</h3>
 * 数据层就是<b>并列多个标</b>（吉客云能"挑着清"正说明它们并列共存）。
 * "第一层只显示主标"这种压缩规则只能写在**显示层**（前端：阻断型优先、同类型取最新），
 * 不许落到数据里 —— 一旦落库就有了第二套口径，谁都能拿它当判据。
 *
 * <h3>builtin 的含义（本期允许新增自定义值）</h3>
 * {@code builtin=1}：迁移种下的内置项，禁止删除；{@code builtin=0}：运行期人工新增的自定义值。
 * 字典**只读是错的**：只读 ⇒ 人工遇到新情况只能写自由文本 ⇒ 回到 {@code reason="1231"} 那个灾难。
 * 本期只做「新增」；改名 / 停用 / 排序留给期 2 的维护页。
 */
@Data
@TableName("audit_tag_catalog")
public class AuditTagCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 稳定键 = {@code order_audit_tag.tag_code}；**永不修改**（队列 ?tag= 筛选、报表聚合、策略 action_json 都以它为准） */
    private String code;

    /** 中文名（业务看的那一句）；改名不影响任何键 */
    private String name;

    /** 一句话说明：这标是什么意思、下一步该做什么 */
    private String description;

    /** 分组：{@code 结论} / {@code 特征}（见类注释，本期只落地结论档的落标） */
    private String category;

    /** 1 = 会拦住下单往下走（人工必须处理才能继续）；0 = 只是提示 */
    private Integer blocking;

    /** 1 = 业务可见；0 = 只在排障/报表里可见 */
    private Integer visible;

    private Integer sortNo;

    /** 1 启用 0 停用（停用不删：历史标行照旧按它显示中文名） */
    private Integer enabled;

    /** 1 内置（禁删） 0 运行期新增的自定义值 */
    private Integer builtin;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
