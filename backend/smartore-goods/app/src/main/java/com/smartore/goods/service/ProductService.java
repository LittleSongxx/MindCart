package com.smartore.goods.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.entity.Product;
import com.smartore.common.exception.CustomException;
import com.smartore.goods.mapper.ProductMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

    @Resource
    private ProductMapper productMapper;
    @Resource
    private com.smartore.goods.mapper.StockMapper stockMapper;

    /** 商品与库存权威表双写必须在同一本地事务：中途崩溃会出现"无 stock 行"的商品，之后扣减永远失败 */
    @Transactional
    public void add(Product product) {
        validate(product);
        if (ObjectUtil.isEmpty(product.getProductNo())) {
            product.setProductNo("SP" + DateUtil.format(DateUtil.date(), "yyyyMMddHHmmssSSS"));
        }
        Product dbProduct = productMapper.selectByProductNo(product.getProductNo());
        if (ObjectUtil.isNotNull(dbProduct)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        setDefaultValue(product);
        String now = DateUtil.now();
        product.setCreateTime(now);
        product.setUpdateTime(now);
        productMapper.insert(product);
        // 库存权威表与商品展示列同事务对齐（新增时以 stock_quantity 初始化）
        stockMapper.upsert(product.getId(), product.getStockQuantity() == null ? 0 : product.getStockQuantity());
    }

    @Transactional
    public void updateById(Product product) {
        if (ObjectUtil.isEmpty(product.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(product);
        Product dbProduct = productMapper.selectByProductNo(product.getProductNo());
        if (ObjectUtil.isNotNull(dbProduct) && !dbProduct.getId().equals(product.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        setDefaultValue(product);
        product.setUpdateTime(DateUtil.now());
        productMapper.updateById(product);
        // 管理端改库存 = 显式重置权威库存（其余情况展示列由库存变动流水驱动同步）
        if (product.getStockQuantity() != null) {
            stockMapper.upsert(product.getId(), product.getStockQuantity());
        }
    }

    public void deleteById(Integer id) {
        productMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            productMapper.deleteById(id);
        }
    }

    public List<Product> selectAll(Product product) {
        return productMapper.selectAll(product);
    }

    public PageInfo<Product> selectPage(Product product, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Product> list = productMapper.selectAll(product);
        return PageInfo.of(list);
    }

    private void validate(Product product) {
        if (ObjectUtil.isEmpty(product.getName())
                || ObjectUtil.isEmpty(product.getCategoryId())
                || ObjectUtil.isEmpty(product.getBrandId())
                || product.getPrice() == null
                || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }

    private void setDefaultValue(Product product) {
        if (product.getOriginalPrice() == null) {
            product.setOriginalPrice(product.getPrice());
        }
        if (product.getIsRecommend() == null) {
            product.setIsRecommend(0);
        }
        if (ObjectUtil.isEmpty(product.getStatus())) {
            product.setStatus("ON_SALE");
        }
        if (product.getSort() == null) {
            product.setSort(1);
        }
    }
}
