package com.fuyouxuan.canal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.javatool.canal.client.annotation.CanalTable;
import top.javatool.canal.client.handler.EntryHandler;

import javax.annotation.Resource;

@Slf4j
@CanalTable("tb_blog_like")
@Component
public class BlogLikeHandler implements EntryHandler<CanalBlogLike> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void insert(CanalBlogLike blogLike) {
        if (blogLike.getBlog_id() == null || blogLike.getUser_id() == null) {
            return;
        }
        String key = "blog:liked:" + blogLike.getBlog_id();
        // ZSet score 用 create_time，没有则用当前时间
        double score = blogLike.getCreate_time() != null ? blogLike.getCreate_time() : System.currentTimeMillis();
        stringRedisTemplate.opsForZSet().add(key, blogLike.getUser_id().toString(), score);
        log.info("Canal同步点赞, key={}, userId={}", key, blogLike.getUser_id());
    }

    @Override
    public void delete(CanalBlogLike blogLike) {
        if (blogLike.getBlog_id() == null || blogLike.getUser_id() == null) {
            return;
        }
        String key = "blog:liked:" + blogLike.getBlog_id();
        stringRedisTemplate.opsForZSet().remove(key, blogLike.getUser_id().toString());
        log.info("Canal同步取消点赞, key={}, userId={}", key, blogLike.getUser_id());
    }
}
