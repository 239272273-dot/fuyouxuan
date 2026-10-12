package com.fuyouxuan.service.impl;

import com.fuyouxuan.entity.UserInfo;
import com.fuyouxuan.mapper.UserInfoMapper;
import com.fuyouxuan.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    @Override
    public UserInfo getOrCreateDefault(Long userId) {
        baseMapper.insertDefault(userId);
        return getById(userId);
    }

    @Override
    public boolean deductCredits(Long userId, Integer amount) {
        return baseMapper.deductCredits(userId, amount) == 1;
    }
}
