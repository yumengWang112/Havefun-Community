package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result getlist() {
        String  key="cache:typelist";
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //判断是否缓存中了
        if(!shopTypeList.isEmpty()){
            //中了 返回
            ArrayList<ShopType> shopTypes = new ArrayList<>();
            for (String shopType : shopTypeList) {
                ShopType shopType1 = JSONUtil.toBean(shopType, ShopType.class);
                shopTypes.add(shopType1);
            }
            return Result.ok(shopTypes);
        }
        //没中 去数据库查
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //查到了 存到resid
        if(!typeList.isEmpty()){
            for (ShopType shopType : typeList) {
                String s =JSONUtil.toJsonStr(shopType);
                shopTypeList.add(s);
            }
            stringRedisTemplate.opsForList().rightPushAll(key,shopTypeList);
            return Result.ok(typeList);
        }

        //没查到 返回
        return Result.fail("查询错误");
    }
}
