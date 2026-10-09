package com.fuyouxuan.canal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

import javax.annotation.Resource;

@Slf4j
@CanalTable("tb_follow")
@Component
public class FollowHandler implements EntryHandler<CanalFollow> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void insert(CanalFollow follow) {
        if (follow.getUser_id() == null || follow.getFollow_user_id() == null) {
            return;
        }
        String key = "follows:" + follow.getUser_id();
        stringRedisTemplate.opsForSet().add(key, follow.getFollow_user_id().toString());
        log.info("Canal同步关注, key={}, followUserId={}", key, follow.getFollow_user_id());
    }

    @Override
    public void delete(CanalFollow follow) {
        if (follow.getUser_id() == null || follow.getFollow_user_id() == null) {
            return;
        }
        String key = "follows:" + follow.getUser_id();
        stringRedisTemplate.opsForSet().remove(key, follow.getFollow_user_id().toString());
        log.info("Canal同步取关, key={}, followUserId={}", key, follow.getFollow_user_id());
    }
}
