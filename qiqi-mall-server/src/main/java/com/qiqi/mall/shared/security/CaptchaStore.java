package com.qiqi.mall.shared.security;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 验证码与登录失败计数（对外方法签名不变，内部委托 KvStore）。
 * - 验证码：key=captcha:{captchaId}，TTL 5 分钟，取用即删（防重放）
 * - 失败计数：key=captcha:fail:{username}，5 分钟固定窗口
 * 档位切换：app.kv-store=memory（进程内）/ redis（多实例共享），调用方零感知。
 */
@Component
public class CaptchaStore {

    /** 验证码有效期 5 分钟 */
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    /** 失败计数窗口 5 分钟 */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(5);
    /** 触发验证码的失败次数阈值 */
    public static final int CAPTCHA_THRESHOLD = 5;

    private static final String CAPTCHA_PREFIX = "captcha:";
    private static final String FAIL_PREFIX = "captcha:fail:";

    private final KvStore kvStore;

    public CaptchaStore(KvStore kvStore) {
        this.kvStore = kvStore;
    }

    public void saveCaptcha(String captchaId, String code) {
        kvStore.put(CAPTCHA_PREFIX + captchaId, code, CAPTCHA_TTL);
    }

    /** 校验并销毁（一次性） */
    public boolean verifyAndConsume(String captchaId, String code) {
        if (captchaId == null || code == null) return false;
        String expected = kvStore.consume(CAPTCHA_PREFIX + captchaId);
        return expected != null && expected.equalsIgnoreCase(code.trim());
    }

    public int failCount(String username) {
        return (int) kvStore.get(FAIL_PREFIX + username);
    }

    public boolean captchaRequired(String username) {
        return failCount(username) >= CAPTCHA_THRESHOLD;
    }

    /** 失败 +1（窗口由 KvStore 维护） */
    public void increaseFail(String username) {
        kvStore.increment(FAIL_PREFIX + username, FAIL_WINDOW);
    }

    public void clearFail(String username) {
        kvStore.reset(FAIL_PREFIX + username);
    }
}
