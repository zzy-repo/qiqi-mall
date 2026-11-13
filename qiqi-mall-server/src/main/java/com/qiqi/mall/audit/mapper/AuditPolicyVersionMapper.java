package com.qiqi.mall.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.audit.entity.AuditPolicyVersion;
import org.apache.ibatis.annotations.Mapper;

/** 策略版本快照 DAO（只追加）。 */
@Mapper
public interface AuditPolicyVersionMapper extends BaseMapper<AuditPolicyVersion> {
}
