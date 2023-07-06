package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.naming.ldap.PagedResultsControl;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.不符合 返回错误信息
            return Result.fail("手机号格式错误，请重新输入");
        }

        //3.符合 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        //session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code);

        //5.发送验证码  设置有效期
        log.debug("发送短信验证成功，验证码：{}",code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        //应该发送验证码之后锁定 手机号输入框，如果成功直接输入手机号  等验证码过期了在解锁
        // 不然也会存在收验证码的手机号和登陆的手机号不一致的情况
        //这里是session登陆 后面会有redis登陆
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
//        Object cachCode = session.getAttribute("code");
//        String code = loginForm.getCode();//提交的code
//        if(cachCode == null || !cachCode.toString().equals(code)){
//            return Result.fail("验证码错误");
//        }

        String cachCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();//提交的code
        if(cachCode == null || !cachCode.equals(code)){
            return Result.fail("验证码错误");
        }
        // 2.校验验证码
        //3.不一致，报错
        //4.一致 根据手机号 查询用户
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if (user == null) {
            //6.不存在  创建新用户并保存
            user = createUserWithPhone(phone);

        }
        //6.不存在  创建新用户并保存
        //7.保存到用户数据库
            //7.1 随机生成token，作为登录令牌

        String token = UUID.randomUUID().toString(true);
        ///7.2 将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> usertMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fileValue) ->fileValue.toString()));
        //7.3 存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,usertMap);
        //7.4 设置token有效期
        //session是超过30分钟没有访问就清除  但是redis这样是不管有没有访问  只要超过30分钟就清除
        //通过拦截器状态更新实现
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //8.返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登陆用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy-MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月第几天  1-31
        int dayOfMonth = now.getDayOfMonth();
        //5.写入bitmap
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登陆用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy-MM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月第几天  1-31
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止的所有签到记录，返回的是一个十进制数字

        //bitField 可以同时做get  set等  所以返回的是一个集合
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        //从0开始到当前日期
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        int count = 0;
        if(num == null || num == 0){
            return Result.ok(0);
        }
        //循环遍历
        while (true){
            //让这个数组和1做与运算，得到数字的最后一个bit位
            if((num & 1)  == 0){
                //判断这个bit位是否为0
                break;
            }else {
                count++;
            }

            //如果不为0 说明已签到 计数器+1
            //数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num =  num>>> 1;
            

        }



        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(5));
        //保存用户
        save(user);
        return user;
    }
}
