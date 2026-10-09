package com.fuyouxuan.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fuyouxuan.entity.BlogLike;
import com.fuyouxuan.mapper.BlogLikeMapper;
import com.fuyouxuan.service.IBlogLikeService;
import org.springframework.stereotype.Service;

@Service
public class BlogLikeServiceImpl extends ServiceImpl<BlogLikeMapper, BlogLike> implements IBlogLikeService {
}
