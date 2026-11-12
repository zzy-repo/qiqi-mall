package com.qiqi.mall.apikey.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiqi.mall.apikey.entity.AiApiKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiApiKeyMapper extends BaseMapper<AiApiKey> {

    /**
     * 只更新 last_used_at（用到时间由数据库 NOW() 决定，避免应用时钟漂移）。
     * 单独一条 UPDATE 而不是 updateById：业务行其它字段一律不碰，也不会覆盖并发改的 scopes/status。
     */
    @Update("UPDATE ai_api_key SET last_used_at = NOW() WHERE id = #{id}")
    int touchLastUsed(@Param("id") Long id);
}
