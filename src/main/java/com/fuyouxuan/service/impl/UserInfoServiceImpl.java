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

}
