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
 * <p>{@code channel.mock.base-url} 是**进程级**配置（{@code channel_shop} 表没有 base_url 列，
 * 三家店铺共用一份），各 profile 的取值见 application-*.yml：
 * <ul>
 *   <li>dev（宿主机后端，含 e2e 分片）= {@code http://127.0.0.1:19090}：
 *       mock 是 e2e spec 自己 spawn 的宿主机进程；</li>
 *   <li>test（容器栈 = 测试/演示环境）= {@code http://mock-platform:19090}：
 *       mock 是 compose 里的服务，走 qiqi-net 内部 DNS。**不能写 127.0.0.1** ——
 *       容器里的 127.0.0.1 是容器自己，那是「点拉单必然连接失败」的老 bug。</li>
 * </ul>
 * 两处都可用环境变量 {@code CHANNEL_MOCK_BASE_URL} 覆盖。启动方式见 mock-platform/README.md。
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
