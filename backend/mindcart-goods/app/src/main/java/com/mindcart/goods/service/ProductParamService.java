package com.mindcart.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.mindcart.common.result.ResultCodeEnum;
import com.mindcart.goods.cache.GoodsCacheNames;
import com.mindcart.goods.entity.ProductParam;
import com.mindcart.common.exception.CustomException;
import com.mindcart.goods.mapper.ProductParamMapper;
import jakarta.annotation.Resource;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductParamService {

    @Resource
    private ProductParamMapper productParamMapper;

    @CacheEvict(value = GoodsCacheNames.PRODUCT_PARAM, key = "#productParam.productId")
    public void add(ProductParam productParam) {
        validate(productParam);
        if (productParam.getSort() == null) {
            productParam.setSort(1);
        }
        if (productParam.getIsCore() == null) {
            productParam.setIsCore(0);
        }
        String now = DateUtil.now();
        productParam.setCreateTime(now);
        productParam.setUpdateTime(now);
        productParamMapper.insert(productParam);
    }

    /**
     * 按 productId 失效。更新入参带 productId 才能精确失效——
     * 管理端表单提交的是整行（含 productId），这条路径成立；
     * 若将来出现只传 id 的局部更新，这里要改成先查库拿 productId 再失效。
     */
    @CacheEvict(value = GoodsCacheNames.PRODUCT_PARAM, key = "#productParam.productId")
    public void updateById(ProductParam productParam) {
        if (ObjectUtil.isEmpty(productParam.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(productParam);
        productParam.setUpdateTime(DateUtil.now());
        productParamMapper.updateById(productParam);
    }

    /** 删除只有行 id，拿不到 productId，只能整区清（参数表体量小，代价可接受） */
    @CacheEvict(value = GoodsCacheNames.PRODUCT_PARAM, allEntries = true)
    public void deleteById(Integer id) {
        productParamMapper.deleteById(id);
    }

    @CacheEvict(value = GoodsCacheNames.PRODUCT_PARAM, allEntries = true)
    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            deleteById(id);
        }
    }

    /**
     * 详情页按 productId 取参数。未限定 productId 的查询（管理端全量列表）不进缓存：
     * 键会退化成 "all"，与"某个商品的参数"混在一个缓存区里，失效时容易清错或漏清。
     */
    @Cacheable(value = GoodsCacheNames.PRODUCT_PARAM, key = "#productParam.productId",
            condition = "#productParam != null and #productParam.productId != null")
    public List<ProductParam> selectAll(ProductParam productParam) {
        return productParamMapper.selectAll(productParam);
    }

    private void validate(ProductParam productParam) {
        if (ObjectUtil.isEmpty(productParam.getProductId())
                || ObjectUtil.isEmpty(productParam.getParamGroup())
                || ObjectUtil.isEmpty(productParam.getParamName())
                || ObjectUtil.isEmpty(productParam.getParamValue())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
