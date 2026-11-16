package com.qiqi.mall.audit.service;

import com.qiqi.mall.audit.dto.TagCatalogDto;
import com.qiqi.mall.audit.entity.AuditTagCatalog;
import com.qiqi.mall.audit.mapper.AuditTagCatalogMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <h3>⚠️⚠️ 这份缓存<b>只能给显示用</b>；「放行 / 拦住」这类判据必须直查库</h3>
 * 缓存是 <b>per-instance</b> 的：启动时装载，{@link #createCustom} 只 reload <b>处理该请求的那个实例</b>
 * ⇒ 多实例部署下其余实例会一直陈旧（{@link #TTL_MS} 只把窗口从"永久"缩到几秒，仍不是零）。
 * 2026-11-16 v1.1.9 真站事故：新加的阻断特征标在陈旧实例上"不存在"，下发保险因此静默放行，
 * 包裹真的发给了 WMS 并拿到 ACK。所以安全判据走 {@link #freshInfoByCodes}（直查库），
 * 不要用 {@link #info} / {@link #byCode} / {@link #nameOf} 去决定"放不放行"。
 * 反过来，{@link #info} / {@link #nameOf} 这类读路径用于显示与下拉时，过期只是显示旧名字 —— 可以接受。
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

    /**
     * 缓存装载时刻（毫秒）。配合 {@link #TTL_MS} 做「读时惰性过期」：
     * 这份副本是 <b>per-instance</b> 的，而 {@link #createCustom} 只会 reload <b>处理该请求的那一个实例</b>
     * ⇒ 多实例部署下别的实例会一直陈旧（2026-11-16 v1.1.9 事故根因）。
     * 见类注释的「⚠️ 这份缓存只能给显示用，安全判据必须直查库」。
     */
    private volatile long loadedAt = 0L;

    /**
     * 缓存有效期（毫秒）：过了这个点，下一次<b>读</b>就顺手 reload 一次。
     *
     * <p>取值 5s 的理由：目录是几十行的静态表，一次 reload 就是一条主键索引全表扫（<1ms）；
     * 而"运营新增一个标之后，别的实例多久能显示/能校验通过"这个窗口取 5~10s 人根本感觉不到。
     *
     * <p>⚠️ TTL <b>不是</b>为了安全判据 —— 安全判据（下发保险）已经绕开这份缓存直查库
     * （{@code AuditTagCatalogMapper#selectByCodes}）。TTL 只是把"别的实例多久能看到新标"
     * 从<b>永久</b>缩到几秒，服务于前端下拉 / 打标校验 / 中文名显示。
     */
    private static final long TTL_MS = 5_000L;

    public AuditTagCatalogService(AuditTagCatalogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 定时全量重载（兜底：某些实例可能长时间没人读目录，靠定时器保证它不会一直停在启动那一刻）。
     *
     * <p>与 TTL 是<b>两个方向</b>：TTL 保证"有人读就一定不会太旧"，定时器保证"没人读也在慢慢跟上"。
     * 两者都不承担安全判据 —— 那是下发保险直查库的事。
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void refreshPeriodically() {
        try {
            reload();
        } catch (RuntimeException e) {
            // 一次重载失败不该把定时任务炸掉（异常抛出去会让 Scheduled 取消后续调度）
            log.warn("标目录定时重载失败（沿用上一份缓存）: {}", e.getMessage());
        }
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
        this.loadedAt = System.currentTimeMillis();
        log.info("标目录已装载：{} 条（结论 {} 条 / 特征 {} 条）", all.size(),
                countCategory(all, CATEGORY_CONCLUSION), countCategory(all, CATEGORY_FEATURE));
    }

    /**
     * 读前的惰性过期：缓存超过 {@link #TTL_MS} 就 reload 一次（失败只告警，沿用旧副本）。
     *
     * <p>只在<b>读</b>路径上调用，且必须先读一次 {@code loadedAt} 判断 —— 命中时只是一次
     * volatile 读 + 一次减法，不进 synchronized，不会把热路径（队列每行都要翻中文名）拖慢。
     */
    private void reloadIfStale() {
        if (System.currentTimeMillis() - loadedAt <= TTL_MS) return;
        try {
            reload();
        } catch (RuntimeException e) {
            // 读不到库时沿用旧副本（比"整页 500"好）；下一次读还会再试
            log.warn("标目录过期重载失败（沿用上一份缓存）: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 读

    /**
     * <b>直查库</b>（绕过缓存）：按标码批量取当前目录项，返回 {@code code → TagInfo}。
     *
     * <h3>这是给「安全判据」用的，不是给显示用的</h3>
     * 缓存是 per-instance 的、会过期；「放行 / 拦住」这类决策读到过期副本就等于<b>静默失效</b>
     * （2026-11-16 v1.1.9 真站事故：新加的阻断特征标在没 reload 的实例上"不存在"，
     * 挂着阻断标的包裹被下发给 WMS 并拿到 ACK）。所以这类判据一律走这里直查。
     *
     * <p>成本：一条 `WHERE code IN (...)` 带唯一键 `uk_code` 的查询（目录只有几十行）。
     * 调用方保证 {@code codes} 非空（空集合直接短路，不必来查库）。
     *
     * <p>目录里查不到的标码<b>不会</b>出现在返回的 map 里 —— 那是合法状态（标先落库、目录后补），
     * 调 用方应按"谈不上 category/blocking"处理，绝不许因此抛异常。
     */
    public Map<String, TagInfo> freshInfoByCodes(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return Map.of();
        Map<String, TagInfo> out = new LinkedHashMap<>();
        for (AuditTagCatalog t : mapper.selectByCodes(codes)) {
            out.put(t.getCode(), new TagInfo(t.getCode(), t.getName(), t.getDescription(),
                    t.getCategory(), eq1(t.getBlocking()), eq1(t.getVisible())));
        }
        return out;
    }

    /** 全量（含停用项）：报表 / 维护页口径 */
    public List<AuditTagCatalog> all() {
        reloadIfStale();
        return List.copyOf(byCode.values());
    }

    /** 队列/抽屉用：启用 + 业务可见 */
    public List<TagCatalogDto> visible() {
        reloadIfStale();
        return byCode.values().stream()
                .filter(t -> eq1(t.getEnabled()) && eq1(t.getVisible()))
                .map(TagCatalogDto::of)
                .toList();
    }

    /** 某一档下已启用的项（期 2 的特征标下拉会用；本期前端只用 visible/all） */
    public List<TagCatalogDto> enabledByCategory(String category) {
        reloadIfStale();
        return byCode.values().stream()
                .filter(t -> eq1(t.getEnabled()) && category != null && category.equals(t.getCategory()))
                .map(TagCatalogDto::of)
                .toList();
    }

    /** 目录项；**查不到返回 null**（调用方降级显示原始标码，见类注释） */
    public AuditTagCatalog byCode(String code) {
        if (code == null) return null;
        reloadIfStale();
        return byCode.get(code);
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
