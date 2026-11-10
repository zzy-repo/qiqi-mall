package com.qiqi.mall.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiqi.mall.entity.Attribute;
import com.qiqi.mall.entity.SkuSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 把 SKU 的规格值拼成可读文本：颜色:黑,内存:256G */
@Component
public class SpecTextHelper {

    private final SkuSpecService skuSpecService;
    private final AttributeService attributeService;

    public SpecTextHelper(SkuSpecService skuSpecService, AttributeService attributeService) {
        this.skuSpecService = skuSpecService;
        this.attributeService = attributeService;
    }

    public String textOf(Long skuId) {
        List<SkuSpec> specs = skuSpecService.list(new LambdaQueryWrapper<SkuSpec>()
                .eq(SkuSpec::getSkuId, skuId).orderByAsc(SkuSpec::getId));
        return buildText(specs);
    }

    /** 批量：skuId -> 规格文本 */
    public Map<Long, String> textMapBySkuIds(List<Long> skuIds) {
        Map<Long, String> result = new HashMap<>();
        if (skuIds == null || skuIds.isEmpty()) return result;
        List<SkuSpec> specs = skuSpecService.list(new LambdaQueryWrapper<SkuSpec>()
                .in(SkuSpec::getSkuId, skuIds).orderByAsc(SkuSpec::getId));
        Map<Long, List<SkuSpec>> grouped = specs.stream().collect(Collectors.groupingBy(SkuSpec::getSkuId));
        for (Long id : skuIds) {
            result.put(id, buildText(grouped.getOrDefault(id, List.of())));
        }
        return result;
    }

    private String buildText(List<SkuSpec> specs) {
        if (specs == null || specs.isEmpty()) return "";
        Map<Long, String> attrNames = attributeNames(specs);
        List<String> parts = new ArrayList<>();
        for (SkuSpec spec : specs) {
            String name = attrNames.getOrDefault(spec.getAttributeId(), String.valueOf(spec.getAttributeId()));
            parts.add(name + ":" + spec.getValue());
        }
        return String.join(",", parts);
    }

    private Map<Long, String> attributeNames(List<SkuSpec> specs) {
        List<Long> ids = specs.stream().map(SkuSpec::getAttributeId).distinct().collect(Collectors.toList());
        Map<Long, String> map = new HashMap<>();
        if (ids.isEmpty()) return map;
        for (Attribute attribute : attributeService.listByIds(ids)) {
            map.put(attribute.getId(), attribute.getName());
        }
        return map;
    }
}
