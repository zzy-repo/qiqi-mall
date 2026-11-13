package com.qiqi.mall.audit.dto;

/** 字段元数据出参（前端条件编辑器据此渲染下拉，不再硬编码字段清单）。 */
public record FieldMetaDto(String code, String label, String valueType, String allowedOps) {
}
