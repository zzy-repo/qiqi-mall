package com.qiqi.mall.shared.ledger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求追踪号（{@code requestId}）：每次 HTTP 请求一个，写进 MDC，响应头回带。
 *
 * <h3>为什么后台审计的幂等键必须来自它</h3>
 * {@code admin_audit_log.idem_key} 的口径是「一次请求一条，同一个人连点两次留两条（第二次失败也是证据）」。
 * 唯一能表达「这是两次不同的点击」的东西就是**请求维度**的 id：
 * <ul>
 *   <li>用业务键派生（订单号/包裹号）→ 连点两次的第二次撞 uk_idem → INSERT 失败 → 事务回滚 →
 *       本该留下的「失败证据」被抹掉，审计语义直接崩掉；</li>
 *   <li>用 request_id 派生 → 两次点击是两个 request_id → 两条审计行，第二条哪怕业务失败也留着。</li>
 * </ul>
 *
 * <h3>为什么不新造一个「请求上下文对象」而用 MDC</h3>
 * MDC 本来就是「这一次请求」的存活范围，Filter 出去时清掉即可；日志里也能顺手看到同一个 id
 * （排障时「这条台账对应哪次请求」一眼可查）。项目此前没有任何 traceId 机制，
 * 所以这里既生成它，也把它当作全项目第一个 request 维度标识。
 *
 * <h3>来源优先级</h3>
 * <ol>
 *   <li>请求头 {@code X-Request-Id}（网关 / nginx / 调用方透传，便于跨系统串联）；</li>
 *   <li>缺省 / 格式不合法（超过 64 字符或含奇怪字符）→ 自己生成 UUID。</li>
 * </ol>
 * 头里的值必须过滤：它会被写进 {@code VARCHAR(64)} 列，超长在 STRICT 模式下是**错误**，
 * 会把整个业务事务（连同审计）一起回滚 —— 不能让一个外部头决定我们要不要记审计。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** MDC 键（{@link com.qiqi.mall.shared.ledger.AdminAuditWriter} 从这里取） */
    public static final String MDC_KEY = "requestId";

    /** 透传头名 */
    public static final String HEADER = "X-Request-Id";

    /** 允许透传的字符集与长度：与 {@code admin_audit_log.request_id VARCHAR(64)} 对齐 */
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 当前请求的追踪号；不在 HTTP 请求线程里时为 {@code null}（审计侧会生成一个一次性 id） */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
