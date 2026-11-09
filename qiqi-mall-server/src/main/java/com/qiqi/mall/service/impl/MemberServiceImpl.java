package com.qiqi.mall.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiqi.mall.entity.Member;
import com.qiqi.mall.mapper.MemberMapper;
import com.qiqi.mall.service.MemberService;
import org.springframework.stereotype.Service;

@Service
public class MemberServiceImpl extends ServiceImpl<MemberMapper, Member> implements MemberService {
}
