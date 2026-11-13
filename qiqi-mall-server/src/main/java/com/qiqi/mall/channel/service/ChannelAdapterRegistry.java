package com.qiqi.mall.channel.service;

import com.qiqi.mall.channel.spi.ChannelAdapter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 适配器注册表：{@code platformType → ChannelAdapter}。
 *
 * <p>业务层只拿 {@code channel_shop.platform_type} 来这里换实现，不 import 任何具体适配器
 * —— 加一个平台 = 加一个 bean，业务代码零改动。
 *
 * <p>装配期就把「同一平台注册了两个适配器」拦下来：这种错如果留到运行期，
 * 表现是「某个平台的单悄悄走了另一个平台的适配器」，排查成本极高。
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<String, ChannelAdapter> adapters = new LinkedHashMap<>();

    public ChannelAdapterRegistry(List<ChannelAdapter> beans) {
        for (ChannelAdapter adapter : beans) {
            ChannelAdapter previous = adapters.put(adapter.platformType(), adapter);
            if (previous != null) {
                throw new IllegalStateException("平台类型 " + adapter.platformType()
                        + " 注册了两个适配器：" + previous.getClass().getSimpleName()
                        + " / " + adapter.getClass().getSimpleName());
            }
        }
    }

    /** 取适配器；平台没接（配置漏了）时抛业务异常，后台会看到明确文案 */
    public ChannelAdapter of(String platformType) {
        ChannelAdapter adapter = adapters.get(platformType);
        if (adapter == null) {
            throw new IllegalStateException("平台类型 " + platformType + " 没有对应的适配器（已注册："
                    + String.join(", ", adapters.keySet()) + "）");
        }
        return adapter;
    }

    /** 已注册的平台（排查用） */
    public List<String> platformTypes() {
        return List.copyOf(adapters.keySet());
    }
}
