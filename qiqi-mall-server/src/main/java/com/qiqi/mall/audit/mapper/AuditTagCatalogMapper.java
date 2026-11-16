package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 审核标目录 DAO。
 *
 * <p>写路径只有一条：**新增自定义标**（{@code builtin=0}）。改名 / 停用 / 排序是期 2 的维护页，
 * 本期不提供 —— 但也<b>没有 DELETE</b>：目录项被删掉，历史标行就再也翻不出中文名了
 * （和 {@code audit_freeze_reason} 同一条纪律：停用不删）。
 */
@Mapper
public interface AuditTagCatalogMapper extends BaseMapper<AuditTagCatalog> {

    /** 全量（目录只有几十行，整表缓存 + 维护页都用它） */
    @Select("SELECT * FROM audit_tag_catalog ORDER BY sort_no ASC, id ASC")
    List<AuditTagCatalog> selectAllOrdered();

    @Select("SELECT * FROM audit_tag_catalog WHERE code = #{code} LIMIT 1")
    AuditTagCatalog selectByCode(@Param("code") String code);

    /** 某个 category 下已启用的项（队列第一层压缩显示 / 期 2 的特征标下拉会用） */
    @Select("SELECT * FROM audit_tag_catalog WHERE enabled = 1 AND category = #{category} "
            + "ORDER BY sort_no ASC, id ASC")
    List<AuditTagCatalog> selectEnabledByCategory(@Param("category") String category);

    /**
     * 按标码批量<b>直查库</b>（<b>绕过内存缓存</b>）—— 给「安全判据」用，不是显示用的。
     *
     * <h3>为什么必须有这条绕开缓存的入口</h3>
     * {@link com.qiqi.mall.audit.service.AuditTagCatalogService} 的目录是 <b>per-instance 内存副本</b>
     * （启动时整表装载，只有处理 {@code createCustom} 的那个实例会 reload）。多实例部署下，
     * 另一个实例的副本会一直陈旧 —— 如果<b>下发保险</b>的判据读这份副本，
     * 新增的阻断特征标在那个实例上就"不存在"，保险<b>静默放行</b>（2026-11-16 v1.1.9 真站事故，
     * 见 {@code DeliverFlowService#enforceDispatchTagGate} 的注释）。
     *
     * <p>所以「放行 / 拦住」这类决策一律走本方法直查库：多一次带主键/唯一键的查询，
     * 换掉一整类"缓存过期 ⇒ 保险失效"的隐患。缓存留给显示与下拉（过期只是显示旧名字）。
     *
     * @param codes 标码集合（调用方保证非空；空集合会被上层短路，不走到这里）
     */
    @Select("<script>SELECT * FROM audit_tag_catalog WHERE code IN "
            + "<foreach item='c' collection='codes' open='(' separator=',' close=')'>#{c}</foreach>"
            + "</script>")
    List<AuditTagCatalog> selectByCodes(@Param("codes") java.util.Collection<String> codes);

    /**
     * 这几条标码里<b>最早被登记进目录</b>的时刻 —— 也就是"这条规则从哪一刻起生效"。
     *
     * <h3>谁在用、为什么必须直查库</h3>
     * 对账端点（{@code GET /admin/order-audit/queue/conclusion-parity}）要用它把两类完全不同的单分开：
     * <ul>
     *   <li><b>历史单</b>：规则生效<b>之前</b>就离开过待审核 ⇒ 那时候结算只清空、不落终局标，
     *       它们身上没有终局标是<b>如实的过去</b>（结论退回决策行，不回填）；</li>
     *   <li><b>真缺口</b>：规则生效<b>之后</b>按结算四条路离开 7，却没有终局标 ⇒ 代码 bug，必须报警。</li>
     * </ul>
     * 判据不能靠"猜"，只能靠一个真实的时间点。这里取的是<b>迁移写下这几条目录项</b>的那一刻
     * （{@code audit_tag_catalog.created_at}，V8 里三条同一语句写入、时刻相同；重放迁移不会刷新它，
     * 因为 V8 是 {@code ON DUPLICATE KEY UPDATE id=id}）—— 比耦合 Flyway 记账表更稳，
     * 也不需要在代码里硬编一个日期。
     *
     * @return 最早那条的创建时间；目录里一条都没有（迁移没跑）时回 {@code null}，上层据此报"判据未知"
     */
    @Select("<script>SELECT MIN(created_at) FROM audit_tag_catalog WHERE code IN "
            + "<foreach item='c' collection='codes' open='(' separator=',' close=')'>#{c}</foreach>"
            + "</script>")
    java.time.LocalDateTime selectEarliestCreatedAt(@Param("codes") java.util.Collection<String> codes);
}
