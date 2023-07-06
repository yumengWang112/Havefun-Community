package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate  stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "followers:"+userId;
        //1.判断到底是关注还是取关
        if(isFollow){
            //2.关注 新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSucccess = save(follow);
            //用set 保存在redis中
            if(isSucccess){
                //数据库保存成功

                stringRedisTemplate.opsForSet().add(key,followUserId.toString());

            }


        }else{
            //3.取关 删除 deletee from tb_follow where userid= followid=
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if(remove){

                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());

            }

        }


        return Result.ok();
    }

    @Override
    public Result isfolllow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        //查询是否关注
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();


        return Result.ok(count > 0);
    }

    @Override
    public Result folllowCommons(Long followUserId) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key1 = "followers:"+userId;
        //获取
        String key2 = "followers:"+followUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //解析出id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户

        List<UserDTO> users = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);

    }
}
