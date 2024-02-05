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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 2、符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3、将验证码保存到 session
//        session.setAttribute("code", code);
        // 3、将验证码保存到 redis                  login:code:                     2L
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);


        // 4、模拟发送验证码
        log.debug("发送验证码成功：{}", code);  // 加 @Slf4j 注解

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1、校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 2、从 session 获取验证码并校验
//        Object cacheCode = session.getAttribute("code");// 存储的验证码
//        String code = loginForm.getCode();// 前端传回的验证码
//        if (cacheCode == null || !cacheCode.toString().equals(code)) {
//            // 验证码不一致
//            return Result.fail("验证码错误");
//        }

        // 2、从 redis 获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);// 存储的验证码
        String code = loginForm.getCode();// 前端传回的验证码
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 验证码不一致
            return Result.fail("验证码错误");
        }


        // 3、验证通过，根据手机号码查询用户  select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 4、判断用户是否存在
        if (user == null) {
            // 不存在则创建新用户
            user = createUserWithPhone(phone);
        }

        // 5、保存用户信息到 session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        // 5、保存用户信息到 redis
        // 5.1、随机生成 token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 5.2、将 user 对象转换为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //  *这里需要将 Long类型转换成 String
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue)
                        -> fieldValue.toString())
        );
        // 5.3、存储       login:token:
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5.4、设置token有效期                   30L
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

//        return Result.ok();

        // 返回 token
        return Result.ok(token);
    }

    /**
     * 创建用户
     *
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        // USER_NICK_NAME_PREFIX : user_    新建用户昵称前缀
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 保存用户
        save(user);
        return user;
    }
}
