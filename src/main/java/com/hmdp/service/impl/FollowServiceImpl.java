package com.hmdp.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.business.*;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper,Follow> implements IFollowService {
 @Resource private SocialService social;
 public Result follow(Long target,Boolean yes){social.follow(BusinessStore.user(),target,Boolean.TRUE.equals(yes));return Result.ok();}
 public Result isFollow(Long target){return Result.ok(social.follows(BusinessStore.user(),target));}
 public Result followCommons(Long target){return Result.ok(social.common(BusinessStore.user(),target));}
}
