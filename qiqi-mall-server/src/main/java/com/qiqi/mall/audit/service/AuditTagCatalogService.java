package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.dto.TagCatalogDto;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
import com.qiqi.mall.audit.mapper.AuditTagCatalogMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核标目录（{@code audit_tag_catalog}）的读入口 + 新增自定义标。
 *
 * <h3>为什么整表缓存在内存里</h3>
 * 与冻结原因字典同一条理由：目录只有几十行、几乎不变，却要在<b>每一条</b>判定落标路径上解析
 * （AUTO_PASS 之外的每种结论），还要在队列的每一行上把 {@code tag_code} 翻成中文名 + 说明。
 * 每行一次 SQL 就是 N+1。所以启动时整表装进 {@code Map<code, 目录项>}，
 * {@link #reload()} 在新增自定义标后调一次。
 *
 * <p>⚠️ 缓存里<b>必须包含停用项</b>：历史标行可能挂着已停用的标码，它照样要有中文名
 * （不是显示英文码、更不是空白）。「停用」只影响"能不能被新打上"，不影响"读得出当初是什么"。
 *
 * <h3>⚠️ 目录里查不到的标码不是错误</h3>
 * {@code tag_code} 允许先落库、目录后补（否则每加一个标都要先跑迁移才能写数据）。
 * 查不到时 {@link #info(String)} 回 {@code null}，调用方应<b>降级为显示原始标码</b>，
 * 绝不许因此抛异常 —— 那会让一条没登记的标把整张队列打成空白。
 *
 * <h3>本期只做「新增」，不做改名 / 停用 / 排序</h3>
 * 那三项是期 2 维护页的事。只读是绝对不行的（见 {@link AuditTagCatalog} 类注释）：
 * 人工遇到新情况必须能当场新增一条，否则又会滑回自由文本。
 * 新增出来的项 {@code builtin=0}：可停用（期 2）、不可删除（没有 DELETE 路径）。
 */
@Service
public class AuditTagCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AuditTagCatalogService.class);

    // ---------------------------------------------------------------- 内置标码（与 V4 的 seed 逐条一致）
    /** 无策略命中：result=NO_MATCH（现在那 18 条空白单全是这个） */
    public static final String CODE_NO_POLICY_MATCH = "NO_POLICY_MATCH";
    /** 库存不足：MANUAL 的 downgrade 子类（补货后重跑可过审） */
    public static final String CODE_STOCK_SHORT = "STOCK_SHORT";
    /** 毛利为负 / 成本未维护：MANUAL 的 downgrade 子类 */
    public static final String CODE_COST_MISSING = "COST_MISSING";
    /** 被策略拦截：result=BLOCKED */
    public static final String CODE_BLOCKED = "BLOCKED";
    /** 被冻结：result=FROZEN（以及人工冻结；标码也允许 FROZEN_xxx 细分） */
    public static final String CODE_FROZEN = "FROZEN";
    /** 待人工审核：result=MANUAL（被排除条件挡下来的那支） */
    public static final String CODE_AUDIT_PENDING = "AUDIT_PENDING";

    /** 结论标：审核跑完的产物 —— 本期只实现这一档的自动落标 */
    public static final String CATEGORY_CONCLUSION = "结论";
    /** 特征标：标记策略 / 平台旗帜 / 人工打标 —— 与"过没过"无关，本期只留位置 */
    public static final String CATEGORY_FEATURE = "特征";

    private static final int CODE_MAX = 32;
    private static final int NAME_MAX = 50;
    private static final int DESC_MAX = 255;
    /** 自定义标码的自动生成前缀（后面接自增主键，跨库稳定、可读、不需要转码中文） */
    private static final String CUSTOM_PREFIX = "CUSTOM_";

    private final AuditTagCatalogMapper mapper;

    private volatile Map<String, AuditTagCatalog> byCode = Map.of();

    public AuditTagCatalogService(AuditTagCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void loadOnStartup() {
        try {
            reload();
        } catch (RuntimeException e) {
            // 目录读不到不该让应用起不来：落标路径写的是标码（不依赖目录），
            // 目录只影响"中文名能不能显示"。整块审核停摆的代价远大于"标暂时显示英文码"。
            log.error("标目录加载失败（标将暂时退化为显示原始 tag_code）: {}", e.getMessage(), e);
        }
    }

    /** 重新装载（新增自定义标后调用；测试里也可手工刷新） */
    public synchronized void reload() {
        List<AuditTagCatalog> all = mapper.selectAllOrdered();
        Map<String, AuditTagCatalog> c = new LinkedHashMap<>();
        for (AuditTagCatalog t : all) {
            c.put(t.getCode(), t);
        }
        this.byCode = c;
        log.info("标目录已装载：{} 条（结论 {} 条 / 特征 {} 条）", all.size(),
                countCategory(all, CATEGORY_CONCLUSION), countCategory(all, CATEGORY_FEATURE));
    }

    // ---------------------------------------------------------------- 读

    /** 全量（含停用项）：报表 / 维护页口径 */
    public List<AuditTagCatalog> all() {
        return List.copyOf(byCode.values());
    }

    /** 队列/抽屉用：启用 + 业务可见 */
    public List<TagCatalogDto> visible() {
        return byCode.values().stream()
                .filter(t -> eq1(t.getEnabled()) && eq1(t.getVisible()))
                .map(TagCatalogDto::of)
                .toList();
    }

    /** 某一档下已启用的项（期 2 的特征标下拉会用；本期前端只用 visible/all） */
    public List<TagCatalogDto> enabledByCategory(String category) {
        return byCode.values().stream()
                .filter(t -> eq1(t.getEnabled()) && category != null && category.equals(t.getCategory()))
                .map(TagCatalogDto::of)
                .toList();
    }

    /** 目录项；**查不到返回 null**（调用方降级显示原始标码，见类注释） */
    public AuditTagCatalog byCode(String code) {
        return code == null ? null : byCode.get(code);
    }

    /** 目录项的精简视图（中文名 + 说明 + 是否阻断）：队列行富化用；查不到返回 null */
    public TagInfo info(String code) {
        AuditTagCatalog t = byCode(code);
        return t == null ? null : new TagInfo(t.getCode(), t.getName(), t.getDescription(),
                t.getCategory(), eq1(t.getBlocking()), eq1(t.getVisible()));
    }

    /** 中文名；目录里没有时回原始码（**永远不返回 null**，页面不必为"没登记"单独分支） */
    public String nameOf(String code) {
        AuditTagCatalog t = byCode(code);
        return t == null || t.getName() == null || t.getName().isBlank() ? code : t.getName();
    }

    /** 说明；目录里没有时回 null（页面显示"未登记"而不是编一句话） */
    public String descriptionOf(String code) {
        AuditTagCatalog t = byCode(code);
        return t == null ? null : t.getDescription();
    }

    /** 是否结论标（AUTO_PASS 那条断言的判据：只看 category='结论' 的标） */
    public boolean isConclusion(String code) {
        AuditTagCatalog t = byCode(code);
        return t != null && CATEGORY_CONCLUSION.equals(t.getCategory());
    }

    /**
     * 标目录的简约视图（队列行里每个标带上中文名/说明/是否阻断）。
     *
     * @param blocking 是否阻断（1 = 会拦住下单往下走）
     */
    public record TagInfo(String code, String name, String description, String category,
                          boolean blocking, boolean visible) {
    }

    // ---------------------------------------------------------------- 新增自定义标（本期唯一的写路径）

    /**
     * 新增一条自定义标（{@code builtin=0}）。
     *
     * <h3>为什么必须有这条路</h3>
     * 字典只读 ⇒ 人工遇到没覆盖的情况只能写自由文本 ⇒ 回到 {@code reason="1231"/"111"} 那个灾难
     * （V3 建冻结原因字典就是为了消灭它）。所以"新增"从第一天就要能走通。
     *
     * <h3>code 自动生成</h3>
     * 中文名无法可靠转写成 ASCII 码（拼音/英文/缩写三种口味都会有人反对），而 code 是
     * <b>永不修改的稳定键</b>，让运营手打反而是风险。所以默认生成 {@code CUSTOM_<自增id>}
     * —— 唯一、可读、跨库稳定（不含任何序号语义，不参与排序）。
     * 调用方也可以显式给一个合规的 code（期 2 的标记策略要按名引用时才需要）。
     *
     * <h3>为什么自定义项只能是「特征」</h3>
     * 结论标是**判定链的产物**（谁判出来的、落什么码由引擎决定），人工自建一个"结论标"
     * 没有任何东西会去落它 —— 那只会造出一批永远不会出现的字典项。
     *
     * @param code        可选；为空则自动生成
     * @param name        中文名（必填）
     * @param description 一句话说明（可空）
     * @param category    只接受 {@link #CATEGORY_FEATURE}（缺省即它）
     * @param blocking    1 = 会拦住下单往下走（缺省 0：人工打的标默认不拦流程）
     * @return 新增（或已存在同名 code 时返回既有）的目录项
     */
    @Transactional(rollbackFor = Exception.class)
    public AuditTagCatalog createCustom(String code, String name, String description,
                                        String category, Integer blocking) {
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) {
            throw new IllegalArgumentException("标的中文名必填（它就是业务人员在队列里看到的那几个字）");
        }
        if (nm.length() > NAME_MAX) {
            throw new IllegalArgumentException("标的中文名最长 " + NAME_MAX + " 个字符：" + nm);
        }
        String desc = description == null ? null : description.trim();
        if (desc != null && desc.length() > DESC_MAX) {
            throw new IllegalArgumentException("标的说明最长 " + DESC_MAX + " 个字符");
        }
        String cat = category == null || category.isBlank() ? CATEGORY_FEATURE : category.trim();
        if (!CATEGORY_FEATURE.equals(cat)) {
            throw new IllegalArgumentException("自定义标只能是「" + CATEGORY_FEATURE + "」档："
                    + "「" + CATEGORY_CONCLUSION + "」标由判定链产出，人工不能自建（建了也永远不会出现）");
        }
        Integer blk = blocking == null ? 0 : blocking;
        if (blk != 0 && blk != 1) {
            throw new IllegalArgumentException("blocking 只能是 0 或 1");
        }

        String finalCode = code == null ? null : code.trim().toUpperCase();
        if (finalCode != null && !finalCode.isEmpty()) {
            if (!finalCode.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
                throw new IllegalArgumentException("标码只能用大写字母/数字/下划线，且以字母开头（≤ " + CODE_MAX + " 字符）："
                        + finalCode);
            }
            AuditTagCatalog existing = byCode(finalCode);
            if (existing != null) {
                // 幂等：同 code 已存在 → 返回既有项，不覆盖它的中文名（名称可能已被运营改过）
                log.info("标码 {} 已存在（{}），不重复新增", finalCode, existing.getName());
                return existing;
            }
        }

        AuditTagCatalog t = new AuditTagCatalog();
        // 自动生成时先插一个**唯一的临时码**，插完按自增 id 改成 CUSTOM_<id>。
        // 临时码带随机后缀而不是固定 "PENDING"：固定值在并发新增时会撞 uk_code，
        // 而撞键后的回查只会捞到"另一条还没改完名"的临时行 —— 一个很隐蔽的错配。
        t.setCode(finalCode == null || finalCode.isEmpty()
                ? "TMP_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()
                : finalCode);
        t.setName(nm);
        t.setDescription(desc);
        t.setCategory(CATEGORY_FEATURE);
        t.setBlocking(blk);
        t.setVisible(1);
        t.setEnabled(1);
        t.setBuiltin(0);
        t.setSortNo(500);        // 自定义项排在所有内置结论标之后（期 2 的维护页才可调）
        try {
            mapper.insert(t);
        } catch (DuplicateKeyException e) {
            // 并发新增同一个显式 code：另一条请求已经插进去了 → 当成已存在
            AuditTagCatalog existing = mapper.selectByCode(t.getCode());
            if (existing != null) return existing;
            throw e;
        }
        if (finalCode == null || finalCode.isEmpty()) {
            String generated = CUSTOM_PREFIX + t.getId();
            t.setCode(generated);
            mapper.updateById(t);
            log.info("新增自定义标：{} → {}（{}）", generated, nm, desc);
        } else {
            log.info("新增自定义标：{} → {}（{}）", finalCode, nm, desc);
        }
        reload();       // 缓存必须立刻跟上：新增完马上要给单打标
        return byCode(t.getCode());
    }

    private static int countCategory(List<AuditTagCatalog> all, String category) {
        return (int) all.stream().filter(t -> category.equals(t.getCategory())).count();
    }

    private static boolean eq1(Integer v) {
        return v != null && v == 1;
    }
}
