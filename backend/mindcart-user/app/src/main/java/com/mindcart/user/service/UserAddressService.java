package com.mindcart.user.service;

import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.context.UserContext;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.user.entity.UserAddress;
import com.mindcart.user.mapper.UserAddressMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址：userId 一律取当前登录用户（网关注入），请求体里的 userId 不信任。
 */
@Service
public class UserAddressService {

    @Resource
    private UserAddressMapper userAddressMapper;

    public void add(UserAddress userAddress) {
        userAddress.setUserId(UserContext.requireUserId());
        if (ObjectUtil.isEmpty(userAddress.getReceiverName())
                || ObjectUtil.isEmpty(userAddress.getReceiverPhone())
                || ObjectUtil.isEmpty(userAddress.getDetailAddress())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        userAddressMapper.insert(userAddress);
    }

    public void updateById(UserAddress userAddress) {
        requireOwned(userAddress.getId());
        userAddress.setUserId(null);
        userAddressMapper.updateById(userAddress);
    }

    public void deleteById(Integer id) {
        requireOwned(id);
        userAddressMapper.deleteById(id);
    }

    public List<UserAddress> selectMine() {
        return userAddressMapper.selectByUserId(UserContext.requireUserId());
    }

    @Transactional
    public void setDefault(Integer id) {
        requireOwned(id);
        UserAddress owned = userAddressMapper.selectByUserId(UserContext.requireUserId()).stream()
                .filter(a -> id.equals(a.getId())).findFirst()
                .orElseThrow(() -> new CustomException(ResultCodeEnum.PARAM_ERROR));
        userAddressMapper.clearDefault(UserContext.requireUserId());
        userAddressMapper.setDefault(id, owned.getUserId());
    }

    private void requireOwned(Integer addressId) {
        if (ObjectUtil.isEmpty(addressId)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        boolean owned = userAddressMapper.selectByUserId(UserContext.requireUserId()).stream()
                .anyMatch(a -> addressId.equals(a.getId()));
        if (!owned) {
            throw new CustomException(ResultCodeEnum.FORBIDDEN);
        }
    }
}
