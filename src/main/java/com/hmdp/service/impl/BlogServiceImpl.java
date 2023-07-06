package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog

        Blog blog = getById(id);
        if(blog ==  null){
            return Result.fail("该笔记不存在！");

        }

        //2.查询blog有关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBolgLiked(blog);

        return Result.ok(blog);
    }

    private void isBolgLiked(Blog blog) {
        //这个地方进来getUser的时候要判空，不然未登录会报错的
        if (UserHolder.getUser() != null) {
            //获取登陆用户
            Long userId = UserHolder.getUser().getId();


            //1.判断 当前 用户id是否在当前博客id对应的点赞表内
            String key = "blog:liked:" + blog.getId();

            //Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            blog.setIsLike(score!=null);
        }
    }



    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBolgLiked(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result likeblog(Long id) {
        UserDTO user = UserHolder.getUser();
        if(user ==  null){
            return Result.fail("用户未登录！");
        }
        //获取登陆用户
        Long userId = user.getId();
        //1.判断 当前 用户id是否在当前博客id对应的点赞表内
        String key = "blog:liked:"+id;

        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //不在   数据库点赞数+1
            //保存用户到redis的set集合
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                //stringRedisTemplate.opsForSet().add(key,userId.toString());
                //score为时间戳
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }


        }else{
            //如果已点赞 取消点赞
            //删除id 数据库点赞数-1
            boolean isSuccess1 = update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccess1){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }

        }


        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询点赞时间前5的
        //拿到set前5
        String key = BLOG_LIKED_KEY +id;
        //解析出用户id
        //根据用户id查询出用户
        //返回
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null|| top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query().in("id",ids)

                .last("order by field(id,"+idStr+")").list()

                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());


        return Result.ok(userDTOS);
    }

    @Override
    public Result saveblog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        if(!save){
            return Result.fail("笔记保存失败！");
        }
        //查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = userid
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有粉丝
        for (Follow follow : follows) {
            //获取 粉丝id
            Long userId = follow.getUserId();
            //推送
            String key = "feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }


        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //找到收件箱
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱   zrevrangebyscore key max min withscores limit offset count
        String key  = FEED_KEY+userId;
        //返回元祖 (value,score)
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        //非空判断
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        ArrayList<Long> ids = new ArrayList<>();
        Long minTime = 0l;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //获取id

            ids.add(Long.valueOf(tuple.getValue()));
            //获取分数(时间戳)
            //遍历完最后一个就是上次最后访问时间
            Long time = tuple.getScore().longValue();
            if(time ==  minTime){
                os++;
            }else{
                minTime =  time ;
                os = 1;
            }

        }
        // 3.解析数据：blogId、score(时间戳）offset
        //4.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id",ids).last("order by field (id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //查询blog有关的用户
            queryBlogUser(blog);
            //查询blog是否被点赞
            isBolgLiked(blog);
        }
        //5.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}
