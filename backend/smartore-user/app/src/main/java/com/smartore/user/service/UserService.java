package com.smartore.user.service;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.smartore.common.context.UserContext;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.user.entity.User;
import com.smartore.user.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 账号服务。安全口径（相对单体版）：
 * 1. 密码 BCrypt 哈希存取；
 * 2. 任何出参都不带密码哈希（sanitize）；
 * 3. 普通用户只能改自己的资料字段，角色/余额提升只有管理员能做；
 * 4. 改密码以网关注入的当前用户为准，不再信任请求体里的 username（防越权）。
 */
@Service
public class UserService {

    /** 管理员新建用户未填密码时的默认密码 */
    private static final String DEFAULT_PASSWORD = "123456";

    /** 登录防时序枚举：用户不存在时也对固定哈希跑一次 BCrypt，消除响应时间差 */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Resource
    private UserMapper userMapper;
    @Resource
    private TokenService tokenService;
    @Resource
    private BCryptPasswordEncoder passwordEncoder;
    @Resource
    private com.smartore.user.mapper.WalletRecordMapper walletRecordMapper;
    @Resource
    private com.smartore.user.mapper.UserAddressMapper userAddressMapper;

    public User login(User request) {
        User dbUser = userMapper.selectByUsername(request.getUsername());
        if (ObjectUtil.isNull(dbUser) || StrUtil.isBlank(dbUser.getPassword())) {
            passwordEncoder.matches(StrUtil.nullToEmpty(request.getPassword()), DUMMY_BCRYPT_HASH);
            throw new CustomException(ResultCodeEnum.USER_ACCOUNT_ERROR);
        }
        if (!passwordEncoder.matches(StrUtil.nullToEmpty(request.getPassword()), dbUser.getPassword())) {
            throw new CustomException(ResultCodeEnum.USER_ACCOUNT_ERROR);
        }
        dbUser.setToken(tokenService.create(dbUser.getId(), dbUser.getRole()));
        return sanitize(dbUser);
    }

    /** 公开注册：密码必填且≥4位（默认密码只保留给管理员建号路径） */
    public void register(User user) {
        if (StrUtil.isBlank(user.getUsername()) || StrUtil.isBlank(user.getPassword())
                || user.getPassword().length() < 4) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "用户名与密码必填，密码至少 4 位");
        }
        user.setRole("USER");
        add(user);
    }

    public void add(User user) {
        if (StrUtil.isBlank(user.getUsername())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        if (ObjectUtil.isNotNull(userMapper.selectByUsername(user.getUsername()))) {
            throw new CustomException(ResultCodeEnum.USER_EXIST_ERROR);
        }
        user.setPassword(passwordEncoder.encode(
                StrUtil.isBlank(user.getPassword()) ? DEFAULT_PASSWORD : user.getPassword()));
        if (StrUtil.isBlank(user.getName())) {
            user.setName(user.getUsername());
        }
        if (StrUtil.isBlank(user.getRole())) {
            user.setRole("USER");
        }
        userMapper.insert(user);
    }

    public void updateById(User user) {
        if (ObjectUtil.isEmpty(user.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        if (!UserContext.isAdmin()) {
            // 普通用户：只能改自己的资料字段，其余字段一律丢弃（防提权/改余额）
            Integer selfId = UserContext.requireUserId();
            if (!selfId.equals(user.getId())) {
                throw new CustomException(ResultCodeEnum.FORBIDDEN);
            }
            User safe = new User();
            safe.setId(user.getId());
            safe.setName(user.getName());
            safe.setAvatar(user.getAvatar());
            safe.setPhone(user.getPhone());
            safe.setEmail(user.getEmail());
            user = safe;
        } else if (StrUtil.isNotBlank(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        userMapper.updateById(user);
    }

    public void updatePassword(String oldPassword, String newPassword) {
        if (StrUtil.isBlank(newPassword) || newPassword.length() < 4) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        User dbUser = userMapper.selectById(UserContext.requireUserId());
        if (ObjectUtil.isNull(dbUser)) {
            throw new CustomException(ResultCodeEnum.USER_NOT_EXIST_ERROR);
        }
        if (!passwordEncoder.matches(StrUtil.nullToEmpty(oldPassword), dbUser.getPassword())) {
            throw new CustomException(ResultCodeEnum.PARAM_PASSWORD_ERROR);
        }
        User update = new User();
        update.setId(dbUser.getId());
        update.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);
    }

    /** 删除用户：钱包流水与地址有 FK 约束，先在同一事务内清理子表再删主表 */
    @org.springframework.transaction.annotation.Transactional
    public void deleteById(Integer id) {
        if (id != null && id.equals(UserContext.getUserIdOrNull())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "不能删除当前登录账号");
        }
        walletRecordMapper.deleteByUserId(id);
        userAddressMapper.deleteByUserId(id);
        userMapper.deleteById(id);
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    /** 按用户名精确查询（内部接口） */
    public User selectByUsername(String username) {
        return sanitize(userMapper.selectByUsername(username));
    }

    public User selectById(Integer id) {
        return sanitize(userMapper.selectById(id));
    }

    public List<User> selectAll(User user) {
        return userMapper.selectAll(user).stream().map(this::sanitize).toList();
    }

    public PageInfo<User> selectPage(User user, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(userMapper.selectAll(user).stream().map(this::sanitize).toList());
    }

    /** 出参脱敏：密码哈希永不出服务 */
    private User sanitize(User user) {
        if (user != null) {
            user.setPassword(null);
            user.setNewPassword(null);
        }
        return user;
    }
}
