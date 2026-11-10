package com.qiqi.mall.shared.security;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 图形验证码：生成 4 位字符 + base64 图片 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private final CaptchaStore store;

    public CaptchaService(CaptchaStore store) {
        this.store = store;
    }

    public Map<String, String> generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        store.saveCaptcha(captchaId, captcha.getCode());
        // 仅 debug 级别输出（dev），生产 logging=info 不会落到日志
        log.debug("生成验证码 captchaId={} code={}", captchaId, captcha.getCode());

        Map<String, String> body = new LinkedHashMap<>();
        body.put("captchaId", captchaId);
        body.put("image", "data:image/png;base64," + captcha.getImageBase64());
        return body;
    }
}
