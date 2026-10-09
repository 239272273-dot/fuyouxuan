package com.fuyouxuan.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.fuyouxuan.dto.UserDTO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.fuyouxuan.utils.RedisConstants.LOGIN_USER_KEY;
import static com.fuyouxuan.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<List> REFRESH_TOKEN_SCRIPT;

    static {
        REFRESH_TOKEN_SCRIPT = new DefaultRedisScript<>();
        REFRESH_TOKEN_SCRIPT.setLocation(new ClassPathResource("refresh_token.lua"));
        REFRESH_TOKEN_SCRIPT.setResultType(List.class);
    }

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }

        String key = LOGIN_USER_KEY + token;
        List<Object> userEntries = stringRedisTemplate.execute(
                REFRESH_TOKEN_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(TimeUnit.MINUTES.toSeconds(LOGIN_USER_TTL))
        );
        if (userEntries == null || userEntries.isEmpty()) {
            return true;
        }

        UserDTO userDTO = BeanUtil.fillBeanWithMap(toMap(userEntries), new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }

    private Map<Object, Object> toMap(List<Object> entries) {
        Map<Object, Object> map = new HashMap<>(entries.size() / 2);
        for (int i = 0; i + 1 < entries.size(); i += 2) {
            map.put(entries.get(i), entries.get(i + 1));
        }
        return map;
    }
}
