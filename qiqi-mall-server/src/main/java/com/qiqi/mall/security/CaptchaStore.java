package com.qiqi.mall.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证码与登录失败计数的内存存储（单实例足够）。
 * - 验证码：captchaId -> 明文，TTL 5 分钟；取用即删（防重放）
 * - 失败计数：username -> 次数，5 分钟滑动窗口
 */
@Component
public class CaptchaStore {

    /** 验证码有效期 5 分钟 */
    private static final long CAPTCHA_TTL_MILLIS = 5 * 60 * 1000L;
    /** 失败计数窗口 5 分钟 */
    private static final long FAIL_WINDOW_MILLIS = 5 * 60 * 1000L;
    /** 触发验证码的失败次数阈值 */
    public static final int CAPTCHA_THRESHOLD = 5;

    private record Entry(String code, long expireAt) {}

    private record Fail(int count, long windowStart) {}

    private final Map<String, Entry> captchas = new ConcurrentHashMap<>();
    private final Map<String, Fail> failures = new ConcurrentHashMap<>();

    public void saveCaptcha(String captchaId, String code) {
        captchas.put(captchaId, new Entry(code, System.currentTimeMillis() + CAPTCHA_TTL_MILLIS));
    }

    /** 校验并销毁（一次性） */
    public boolean verifyAndConsume(String captchaId, String code) {
        if (captchaId == null || code == null) return false;
        Entry entry = captchas.remove(captchaId);
        if (entry == null || entry.expireAt() < System.currentTimeMillis()) return false;
        return entry.code().equalsIgnoreCase(code.trim());
    }

    public int failCount(String username) {
        Fail f = failures.get(username);
        if (f == null) return 0;
        if (System.currentTimeMillis() - f.windowStart() > FAIL_WINDOW_MILLIS) {
            failures.remove(username);
            return 0;
        }
        return f.count();
    }

    public boolean captchaRequired(String username) {
        return failCount(username) >= CAPTCHA_THRESHOLD;
    }

    /** 失败 +1，并刷新 5 分钟窗口 */
    public void increaseFail(String username) {
        failures.compute(username, (k, old) -> {
            long now = System.currentTimeMillis();
            if (old == null || now - old.windowStart() > FAIL_WINDOW_MILLIS) {
                return new Fail(1, now);
            }
            return new Fail(old.count() + 1, now);
        });
    }

    public void clearFail(String username) {
        failures.remove(username);
    }

    /** 每 10 分钟清扫过期条目，防内存泄漏 */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void sweep() {
        long now = System.currentTimeMillis();
        captchas.entrySet().removeIf(e -> e.getValue().expireAt() < now);
        failures.entrySet().removeIf(e -> now - e.getValue().windowStart() > FAIL_WINDOW_MILLIS);
    }
}
