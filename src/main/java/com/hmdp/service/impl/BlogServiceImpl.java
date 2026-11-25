package com.hmdp.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.business.*;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper,Blog> implements IBlogService {
 @Resource private SocialService social;
 private long viewer(){return UserHolder.getUser()==null?0:UserHolder.getUser().getId();}
 public Result queryHotBlog(Integer page){return social.hot(page,viewer());}
 public Result queryBlogById(Long id){return Result.ok(social.blog(id,viewer()));}
 public Result likeBlog(Long id){long user=BusinessStore.user();social.like(id,user,!social.liked(id,user));return Result.ok();}
 public Result queryBlogLikes(Long id){return Result.ok(social.likes(id));}
 public Result saveBlog(Blog blog){return Result.ok(Long.toString(social.post(blog,BusinessStore.user())));}
 public Result queryBlogOfFollow(Long max,Integer offset){return social.feed(BusinessStore.user(),max,offset);}
}
