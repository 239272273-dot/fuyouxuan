package com.fuyouxuan.service;

import com.fuyouxuan.dto.Result;
import com.fuyouxuan.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result saveBlog(Blog blog);

    Result deleteBlog(Long id);

    Result queryBlogOfFollow(Long max, Integer offset);

}
