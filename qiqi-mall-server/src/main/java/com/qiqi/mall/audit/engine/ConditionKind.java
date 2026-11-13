package com.qiqi.mall.audit.engine;

/** 条件种类：MATCH = 必须满足；EXCLUDE = 命中即排除（不再往下判定这条策略） */
public enum ConditionKind {
    MATCH, EXCLUDE
}
