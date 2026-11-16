package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.OrderAuditTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 审核标 DAO。写路径只有两条：**加标**（insert，靠 uk_active 防重复）与**解标**（active=0）。
 * 没有 DELETE —— 冻结/解冻是历史事实，证据不删。
 */
@Mapper
public interface OrderAuditTagMapper extends BaseMapper<OrderAuditTag> {

    @Select("SELECT * FROM order_audit_tag WHERE order_id = #{orderId} AND active = 1 ORDER BY id ASC")
    List<OrderAuditTag> selectActive(@Param("orderId") Long orderId);

    /** 冻结判定：FROZEN 类标（tag_code 以 FROZEN 开头，允许 FROZEN_RETURN_APPLY 这种细分） */
    @Select("SELECT * FROM order_audit_tag WHERE order_id = #{orderId} AND active = 1 "
            + "AND tag_code LIKE 'FROZEN%' ORDER BY id ASC LIMIT 1")
    OrderAuditTag selectActiveFrozen(@Param("orderId") Long orderId);

    @Select("SELECT * FROM order_audit_tag WHERE order_id = #{orderId} AND tag_code = #{tagCode} "
            + "AND active = 1 LIMIT 1")
    OrderAuditTag selectActiveByCode(@Param("orderId") Long orderId, @Param("tagCode") String tagCode);

    /** 解标（解冻 / 恢复）：只改 active 与解除信息，行保留 */
    @Update("UPDATE order_audit_tag SET active = 0, released_at = CURRENT_TIMESTAMP(3), released_by = #{releasedBy} "
            + "WHERE order_id = #{orderId} AND tag_code = #{tagCode} AND active = 1")
    int release(@Param("orderId") Long orderId, @Param("tagCode") String tagCode,
                @Param("releasedBy") String releasedBy);

    /**
     * 结论标互斥（期 1.5）：把该单身上**其他** active 的「结论」档标置为已解除。
     *
     * <p>为什么需要它：结论标描述的是"<b>当前判定结论</b>"，而当前结论天然只有一个 ——
     * 一单不可能同时是"无策略命中"和"待人工审核"。不做互斥的话，一单从 NO_MATCH 变成 MANUAL 之后
     * 两个标会并列挂着，{@code ?tag=NO_POLICY_MATCH} 会把它算进两类，
     * 「这个月多少单无策略命中」当场被高估（这是期 1 留下的真问题，期 1.5 修掉）。
     *
     * <p><b>这不是"删历史"</b>：只把 {@code active} 置 0 并记 {@code released_at / released_by}
     * —— 行还在，"它曾经被判成什么"永远查得到。也就是「一条 UPDATE 换掉当前状态，不物理删除」。
     *
     * <p>为什么必须**一条** UPDATE：逐条查+改会变成 N 次往返，而且中途失败会留下"两个结论标同时 active"
     * 的半截状态；一条语句要么全换、要么全不换。
     *
     * <p>为什么用 JOIN 标目录判 category 而不是枚举几个码：结论档的闭集在字典里，
     * 将来加一类结论标只改字典、不用回来改这句 SQL；反过来，**特征标（category='特征'）一个都不动** ——
     * 客诉客户、双地址这类标描述的是"这单是什么样"，与"过没过"无关，天然并存。
     *
     * <p>⚠️ FROZEN 也在这条 UPDATE 的射程内（它在字典里也是「结论」档），但<b>冻结单根本走不到落标路径</b>：
     * 候选范围包含已冻结的单，而引擎对它们是 {@code SKIPPED}（不写判定记录、不落标），
     * 所以这里不会把"冻结"静默放掉。真发生了就说明冻结已被绕过，那时把它标成
     * {@code SYSTEM:SUPERSEDED} 才是如实记录（解冻仍只由 unfreeze 负责，那笔审计不受影响）。
     *
     * @param keepTagCode 本次要落的新结论标码（自己不能被自己解除，否则同一事务里刚插的行会被置 0）
     */
    @Update("UPDATE order_audit_tag t JOIN audit_tag_catalog c ON c.code = t.tag_code "
            + "SET t.active = 0, t.released_at = CURRENT_TIMESTAMP(3), t.released_by = #{releasedBy} "
            + "WHERE t.order_id = #{orderId} AND t.active = 1 "
            + "  AND c.category = '结论' AND t.tag_code <> #{keepTagCode}")
    int supersedeOtherConclusionTags(@Param("orderId") Long orderId,
                                     @Param("keepTagCode") String keepTagCode,
                                     @Param("releasedBy") String releasedBy);

    /** 解冻：一次放掉所有 FROZEN 类标（人工只点一次「解冻」，不该按细分标逐个点） */
    @Update("UPDATE order_audit_tag SET active = 0, released_at = CURRENT_TIMESTAMP(3), released_by = #{releasedBy} "
            + "WHERE order_id = #{orderId} AND tag_code LIKE 'FROZEN%' AND active = 1")
    int releaseFrozen(@Param("orderId") Long orderId, @Param("releasedBy") String releasedBy);
}
