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
 * 服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getList() {
        String key = "shopTypeCache:shopTypeList";

        //1、 从 redis中查询 商铺类型集合
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 2、判断 redis中是否存在
        if (!shopTypeList.isEmpty()) {
            ArrayList<ShopType> typeList = new ArrayList<>();
            for (String shopTypeString : shopTypeList) {
                // 将字符串转换成对象
                ShopType shopType = JSONUtil.toBean(shopTypeString, ShopType.class);
                typeList.add(shopType);
            }
            // 3、存在，直接返回
            return Result.ok(typeList);
        }
        // 4、redis 中不存在，从数据库中查询 商铺类型集合
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 5、判断数据库中是否存在
        if (typeList.isEmpty()) {
            // 不存在，返回错误信息
            return Result.fail("获取商铺列表出错了");
        }

        // 6、数据库中存在，则写入 redis 中
        for (ShopType shopType : typeList) {
            // 将商铺类型转换成字符串
            String jsonStr = JSONUtil.toJsonStr(shopType);
            shopTypeList.add(jsonStr);
        }
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList);

        // 返回前端
        return Result.ok(typeList);
    }
}
