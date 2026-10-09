package com.fuyouxuan.config;

import com.fuyouxuan.entity.BlogLike;
import com.fuyouxuan.entity.Follow;
import com.fuyouxuan.service.IBlogLikeService;
import com.fuyouxuan.service.IFollowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;

/**
 * 启动时缓存预加载：将 MySQL 中已有的关注/点赞数据同步到 Redis。
 * Canal 负责增量同步，此类负责存量兜底。
 */
@Slf4j
@Component
public class CacheBootstrap implements InitializingBean {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private IBlogLikeService blogLikeService;

    @Override
    public void afterPropertiesSet() {
        log.info("=== 缓存预加载开始 ===");

        // 0. 迁移：Redis ZSet 中旧点赞数据 → tb_blog_like 表（仅首次需要，INSERT IGNORE 防重复）
        try {
            migrateBlogLikesFromRedis();
        } catch (Exception e) {
            log.warn("点赞数据迁移失败, msg={}", e.getMessage());
        }

        try {
            // 1. 关注数据：MySQL → Redis Set
            long followCount = followService.count();
            if (followCount > 0) {
                int pageSize = 1000;
                long pages = (followCount + pageSize - 1) / pageSize;
                for (int i = 0; i < pages; i++) {
                    List<Follow> follows = followService.query()
                            .last("LIMIT " + (i * pageSize) + "," + pageSize)
                            .list();
                    for (Follow follow : follows) {
                        String key = "follows:" + follow.getUserId();
                        stringRedisTemplate.opsForSet().add(key, follow.getFollowUserId().toString());
                    }
                }
                log.info("关注缓存预加载完成, count={}", followCount);
            }
        } catch (Exception e) {
            log.warn("关注缓存预加载失败, msg={}", e.getMessage());
        }

        try {
            // 2. 点赞数据：MySQL → Redis ZSet
            long likeCount = blogLikeService.count();
            if (likeCount > 0) {
                int pageSize = 1000;
                long pages = (likeCount + pageSize - 1) / pageSize;
                for (int i = 0; i < pages; i++) {
                    List<BlogLike> likes = blogLikeService.query()
                            .last("LIMIT " + (i * pageSize) + "," + pageSize)
                            .list();
                    for (BlogLike like : likes) {
                        String key = "blog:liked:" + like.getBlogId();
                        stringRedisTemplate.opsForZSet().add(
                                key, like.getUserId().toString(), like.getCreateTime());
                    }
                }
                log.info("点赞缓存预加载完成, count={}", likeCount);
            }
        } catch (Exception e) {
            log.warn("点赞缓存预加载失败, msg={}", e.getMessage());
        }

        log.info("=== 缓存预加载完成 ===");
    }

    /**
     * 一次性迁移：Redis ZSet 中的旧点赞数据 → MySQL tb_blog_like
     */
    private void migrateBlogLikesFromRedis() {
        Set<String> keys = stringRedisTemplate.keys("blog:liked:*");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        int migrated = 0;
        for (String key : keys) {
            Long blogId = Long.valueOf(key.replace("blog:liked:", ""));
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
            if (tuples == null) continue;
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                try {
                    BlogLike like = new BlogLike();
                    like.setBlogId(blogId);
                    like.setUserId(Long.valueOf(t.getValue()));
                    like.setCreateTime(t.getScore().longValue());
                    blogLikeService.save(like);
                    migrated++;
                } catch (Exception ignored) {
                    // 重复数据跳过（uk_blog_user 唯一索引）
                }
            }
        }
        if (migrated > 0) {
            log.info("Redis点赞数据迁移完成, migrated={}", migrated);
        }
    }
}
