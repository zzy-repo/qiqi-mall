package com.qiqi.mall.channel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiqi.mall.channel.adapter.HttpJson;
import com.qiqi.mall.channel.adapter.MockPlatformAdapter;
import com.qiqi.mall.channel.service.ChannelShopService;
import com.qiqi.mall.channel.spi.ChannelAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 两个 mock 平台适配器的装配。
 *
 * <p>为什么在配置里 new 而不是给 {@link MockPlatformAdapter} 打 {@code @Component}：
 * 它同时服务淘宝与抖音（只有 URL 段不同），打成组件就只能有一个实例 ——
 * 注册表（{@code ChannelAdapterRegistry}）按 {@code platformType()} 取适配器，
 * 两个平台必须是**两个 bean**，否则后注册的会覆盖前一个。
 *
 * <p>{@code channel.mock.base-url} 默认 {@code http://127.0.0.1:19090}
 * （mock 服务独立端口，不进 docker compose；启动方式见 mock-platform/README.md）。
 */
@Configuration
public class ChannelConfig {

    public static final String TAOBAO_PLATFORM = "TAOBAO_MOCK";
    public static final String DOUYIN_PLATFORM = "DOUYIN_MOCK";

    @Bean
    public ChannelAdapter taobaoMockAdapter(HttpJson http, ObjectMapper mapper,
                                            @Value("${channel.mock.base-url:http://127.0.0.1:19090}") String baseUrl) {
        return new MockPlatformAdapter(http, mapper, baseUrl, TAOBAO_PLATFORM, "taobao");
    }

    @Bean
    public ChannelAdapter douyinMockAdapter(HttpJson http, ObjectMapper mapper,
                                            @Value("${channel.mock.base-url:http://127.0.0.1:19090}") String baseUrl) {
        return new MockPlatformAdapter(http, mapper, baseUrl, DOUYIN_PLATFORM, "douyin");
    }

    /**
     * 启动补齐三个店铺（幂等 upsert）。
     *
     * <p>放在这里而不是让 Service 自己 {@code @Component implements ApplicationRunner}：
     * 店铺种子的执行时机是「应用装配完成」这件事，属于配置关注点；Service 只管读。
     */
    @Bean
    public ApplicationRunner channelShopSeedRunner(ChannelShopService shopService) {
        return shopService.seedOnStartup();
    }
}
