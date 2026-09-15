package com.smartore.ai.service;

import com.smartore.common.result.Result;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.api.GoodsFeignClient;
import com.smartore.goods.api.ProductVO;
import com.smartore.user.api.UserFeignClient;
import com.smartore.user.api.UserVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 展示名回填：跨库 JOIN 拆微服务后，列表视图里的用户名/商品信息
 * 统一在服务层批量取数回填（一次 Feign 批量调用，不是逐行查询）。
 */
@Service
public class NameFillService {

    @Resource
    private UserFeignClient userClient;
    @Resource
    private GoodsFeignClient goodsClient;

    public <T> void fillProducts(List<T> rows, Function<T, Integer> idGetter, BiConsumer<T, ProductVO> filler) {
        List<Integer> ids = rows.stream().map(idGetter).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Integer, ProductVO> map = safeProducts(ids);
        rows.forEach(row -> {
            ProductVO product = map.get(idGetter.apply(row));
            if (product != null) {
                filler.accept(row, product);
            }
        });
    }

    public <T> void fillUserNames(List<T> rows, Function<T, Integer> idGetter, BiConsumer<T, String> nameSetter) {
        List<Integer> ids = rows.stream().map(idGetter).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        Result<List<UserVO>> result;
        try {
            result = userClient.getUsers(ids);
        } catch (Exception e) {
            return; // 展示名缺失不阻塞列表
        }
        if (result == null || !ResultCodeEnum.SUCCESS.getCode().equals(result.getCode()) || result.getData() == null) {
            return;
        }
        Map<Integer, String> names = result.getData().stream()
                .collect(Collectors.toMap(UserVO::getId, u -> u.getName() == null ? u.getUsername() : u.getName()));
        rows.forEach(row -> {
            String name = names.get(idGetter.apply(row));
            if (name != null) {
                nameSetter.accept(row, name);
            }
        });
    }

    private Map<Integer, ProductVO> safeProducts(List<Integer> ids) {
        try {
            Result<List<ProductVO>> result = goodsClient.getProducts(ids);
            if (result != null && ResultCodeEnum.SUCCESS.getCode().equals(result.getCode()) && result.getData() != null) {
                return result.getData().stream()
                        .collect(Collectors.toMap(ProductVO::getId, Function.identity()));
            }
        } catch (Exception ignored) {
            // 展示信息缺失不阻塞列表
        }
        return Map.of();
    }
}
