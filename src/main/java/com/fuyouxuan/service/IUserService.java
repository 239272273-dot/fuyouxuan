package com.fuyouxuan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fuyouxuan.dto.LoginFormDTO;
import com.fuyouxuan.dto.Result;
import com.fuyouxuan.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result logout(String token);

}
