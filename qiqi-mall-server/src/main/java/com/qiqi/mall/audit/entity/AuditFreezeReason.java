package com.qiqi.mall.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 冻结原因字典（{@code audit_freeze_reason}）：审核标记 {@code FROZEN} 的原因闭集。
 *
 * <h3>为什么原因要成为一张表</h3>
 * 以前 {@code order_audit_tag.reason} 是一句自由文本，库里躺着 {@code 111} / {@code 1231} /
 * {@code 2131} 这种占位值 —— 于是两件本来很基本的事都做不到：
 * <ul>
 *   <li><b>统计</b>：「这个月为什么冻了 50 单」只能靠人肉读字符串；</li>
 *   <li><b>做键</b>：P1 的「解冻时记一条『不再以此原因冻结该单』的永久豁免」需要一个稳定键，
 *       而自由文本改一个字就换了身份。</li>
 * </ul>
 *
 * <h3>code / name / id 三者的分工（别混用）</h3>
 * <ul>
 *   <li>{@code id}：自增代理键，只用于**库内关联**（{@code order_audit_tag.reason_id} 外键）。
 *       它是**跨库不稳定**的（dev 手工插一条，e2e 的 id 就漂了），所以对外一律不用它；</li>
 *   <li>{@code code}：**对外稳定键**，一经写入永不修改 —— P1 的豁免、策略 {@code action_json}、
 *       导出/回放都以它为准。改中文名不破坏任何键，改 code 等于换了一条原因；</li>
 *   <li>{@code name}：人看的中文名，随便改。{@code order_audit_tag.reason} 里存的是它**写入当时的
 *       快照**（人话），所以改名不会篡改历史行给当时人的印象。</li>
 * </ul>
 *
 * <h3>enabled / selectable / builtin 三个开关不是重复</h3>
 * <ul>
 *   <li>{@code enabled=0}：这个原因不再用了 —— 新冻结不能再选它，但**历史行照旧显示与统计**
 *       （关联是按 id 的，不是按"还启不启用"）；</li>
 *   <li>{@code selectable=0}：这条**永远不许人选**（{@code 未分类} / {@code 历史未填} / 策略兜底），
 *       但报表里必须看得见 —— 「无法归因的那几单」是要被看见的，不许悄悄丢掉；</li>
 *   <li>{@code builtin=1}：系统内置项，禁止删除、禁止停用（迁移与兜底依赖它们存在）。</li>
 * </ul>
 */
@Data
@TableName("audit_freeze_reason")
public class AuditFreezeReason {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 稳定键：大写字母 + 下划线，**永不修改**（P1 豁免与策略 action_json 都以它为键） */
    private String code;

    /** 中文名（人看的那一句）；改名不影响任何键 */
    private String name;

    /** 说明：为什么冻、下一步该做什么（给客服看的操作指引） */
    private String description;

    /** 1 启用 0 停用；停用后新冻结不能再选它，历史行照旧显示与统计 */
    private Integer enabled;

    /** 1 允许人工选择 0 仅系统兜底/迁移归类项（可见、可统计，但不许拿它去冻新单） */
    private Integer selectable;

    /** 1 系统内置（兜底/未分类）：禁止删除、禁止停用 */
    private Integer builtin;

    /** 排序（升序，小的在前） */
    private Integer sortNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
