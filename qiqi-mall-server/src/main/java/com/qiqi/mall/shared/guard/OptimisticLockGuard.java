package com.qiqi.mall.shared.guard;

/**
 * 乐观锁（version）校验的统一口径 —— 把「缺版本号」和「版本冲突」的两句中文写死在一处，
 * 免得 5 个 update 入口各写一份、早晚有一处写成英文或写成 code=1。
 *
 * <p>**为什么必须 fail-closed**：如果 entity 的 version 为 null 就放行，
 * MyBatis-Plus 会退化成「无版本条件」的普通 UPDATE —— 也就是回到加了 version 之前的
 * last-write-wins，等于什么都没做，而且外人看不出来（接口照样 code=0）。
 * 所以这里对「缺版本号」一律拒绝，绝不静默降级。
 *
 * <p>错误码约定（与 {@link com.qiqi.mall.shared.common.Result} 一起看）：
 * <ul>
 *   <li>成功 → {@code code=0}</li>
 *   <li>缺少版本号 → {@code code=400} + {@value #MSG_MISSING_VERSION}</li>
 *   <li>版本冲突（期间被别人改过） → {@code code=409} + {@value #MSG_VERSION_CONFLICT}</li>
 * </ul>
 * 409 是项目里唯一非 0/1 的业务 code（沿用 HTTP 语义），前端据此弹「刷新」对话框。
 *
 * <p>**统一用法：抛异常**（`requireXxx`）。异常带 code，由
 * {@code GlobalExceptionHandler} 映射成**真 HTTP 400 / 409**，body 里同时带 {@code code}：
 * <pre>
 * OptimisticLockGuard.requireVersion(body.getVersion());                  // 缺 → HTTP 400
 * int rows = service.getBaseMapper().updateById(entity);                  // 必须用 baseMapper 拿行数
 * OptimisticLockGuard.requireAffected(rows);                              // 0 行 → HTTP 409
 * </pre>
 *
 * <p>不要写成 {@code return Result.fail(err.code(), msg)}：那样 Spring 会包成 <b>HTTP 200</b>，
 * 只有 body 里说失败 —— mcp-server 之外的调用方（curl 脚本、监控、网关日志、第三方）会把冲突记成成功。
 * 所以 {@code checkXxx}（返回 {@code LockError}、不抛）只保留给确实需要自己拼响应体的场景。
 */
public final class OptimisticLockGuard {

    /** 「缺少版本号」：请求里没带 version（老前端 / MCP / 脚本），明确拒绝 */
    public static final int CODE_MISSING_VERSION = 400;
    /** 「版本冲突」：WHERE version=? 没命中，说明读取之后被别人改过 */
    public static final int CODE_VERSION_CONFLICT = 409;

    public static final String MSG_MISSING_VERSION = "缺少版本号，请刷新页面后重试";
    public static final String MSG_VERSION_CONFLICT = "数据已被他人修改（可能其他人在编辑），请刷新后重试";

    /**
     * 「记录已经没了」：给**没有 version 列**的表用（delivery_item 等 5 张热表之外的子表）。
     *
     * <p>这些表 {@code updateById} 不会带 {@code AND version = ?}，影响行数 0 不可能是
     * 「版本冲突」，只可能是「这条记录在我们读取之后被别人删了」—— 同样必须 fail-closed
     * （继续往下执行等于往一条不存在的明细上记账），只是原因和文案不同。
     */
    public static final String MSG_DELIVERY_ITEM_DELETED = "发货明细已被删除，请刷新后重试";

    private OptimisticLockGuard() {
    }

    /** 校验请求带来的版本号：null → 400 错误；有值 → null（可以继续） */
    public static LockError checkVersion(Integer version) {
        return version == null ? new LockError(CODE_MISSING_VERSION, MSG_MISSING_VERSION) : null;
    }

    /** 校验 UPDATE 影响行数：0 → 409 错误；>0 → null（可以继续） */
    public static LockError checkAffected(int rows) {
        return rows == 0 ? new LockError(CODE_VERSION_CONFLICT, MSG_VERSION_CONFLICT) : null;
    }

    /**
     * 校验 UPDATE 影响行数（自定义文案）：0 → 409 + {@code message}；>0 → null。
     *
     * <p>用在哪：**没有 version 列**的表（如 {@code delivery_item}）或影响行数 0 的原因不是版本冲突时
     * —— 错误码仍是 409（前端据此弹「刷新」对话框），只是把原因说准：「记录被删了」而不是「被人改了」。
     */
    public static LockError checkAffected(int rows, String message) {
        return rows == 0 ? new LockError(CODE_VERSION_CONFLICT, message) : null;
    }

    /** 直接比对「读到的版本 vs 客户端版本」（服务层在跑 updateById 之前先拦一道） */
    public static LockError checkSameVersion(Integer expected, Integer current) {
        if (expected == null) return checkVersion(null);
        return expected.equals(current) ? null : new LockError(CODE_VERSION_CONFLICT, MSG_VERSION_CONFLICT);
    }

    /** 抛异常版：null → 400（见类注释「用法 ②」） */
    public static void requireVersion(Integer version) {
        LockError error = checkVersion(version);
        if (error != null) throw new LockException(error.code(), error.message());
    }

    /** 抛异常版：影响行数 0 → 409 */
    public static void requireAffected(int rows) {
        LockError error = checkAffected(rows);
        if (error != null) throw new LockException(error.code(), error.message());
    }

    /** 抛异常版：影响行数 0 → 409 + 自定义文案（无 version 列的表用，见 {@link #MSG_DELIVERY_ITEM_DELETED}） */
    public static void requireAffected(int rows, String message) {
        LockError error = checkAffected(rows, message);
        if (error != null) throw new LockException(error.code(), error.message());
    }

    /** 抛异常版：版本不一致 → 409 */
    public static void requireSameVersion(Integer expected, Integer current) {
        LockError error = checkSameVersion(expected, current);
        if (error != null) throw new LockException(error.code(), error.message());
    }

    /** 一条乐观锁错误：给 controller 显式返回用（不可变） */
    public record LockError(int code, String message) {
    }

    /**
     * 乐观锁校验失败异常：{@code code} 就是最终响应体里的 code（400 / 409）。
     * 由 {@code GlobalExceptionHandler} 统一映射，service 层不用层层往回传中文。
     */
    public static class LockException extends RuntimeException {

        private final int code;

        public LockException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
