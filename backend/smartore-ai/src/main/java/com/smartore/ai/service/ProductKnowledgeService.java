package com.smartore.ai.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.ai.entity.ProductKnowledge;
import com.smartore.common.exception.CustomException;
import com.smartore.ai.mapper.ProductKnowledgeMapper;
import com.smartore.ai.mapper.ProductKnowledgeChunkMapper;
import com.smartore.ai.mapper.ProductKnowledgeEmbeddingMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProductKnowledgeService {

    @Resource
    private NameFillService nameFillService;

    // 由商品详情和规格参数自动导入的资料统一用这个来源，方便重复导入时精确覆盖
    private static final String SOURCE_PRODUCT_DETAIL = "PRODUCT_DETAIL";

    @Resource
    private ProductKnowledgeMapper productKnowledgeMapper;
    @Resource
    private ProductKnowledgeChunkMapper productKnowledgeChunkMapper;
    @Resource
    private ProductKnowledgeEmbeddingMapper productKnowledgeEmbeddingMapper;
    @Resource
    private com.smartore.goods.api.GoodsFeignClient goodsClient;

    private com.smartore.goods.api.KnowledgeSourceVO knowledgeSource(Integer productId) {
        com.smartore.common.result.Result<com.smartore.goods.api.KnowledgeSourceVO> result =
                goodsClient.knowledgeSource(productId);
        return result == null || !"200".equals(result.getCode()) ? null : result.getData();
    }

    private <T> T unwrap(com.smartore.common.result.Result<T> result) {
        if (result == null || !"200".equals(result.getCode())) {
            throw new CustomException("500", "商品服务不可用");
        }
        return result.getData();
    }

    private com.smartore.goods.api.ProductVO loadProduct(Integer productId) {
        com.smartore.common.result.Result<com.smartore.goods.api.ProductVO> result = goodsClient.getProduct(productId);
        return (result == null || !"200".equals(result.getCode())) ? null : result.getData();
    }

    public void add(ProductKnowledge productKnowledge) {
        validate(productKnowledge);
        com.smartore.goods.api.ProductVO product = loadProduct(productKnowledge.getProductId());
        if (ObjectUtil.isNull(product)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        if (productKnowledge.getIsEnabled() == null) {
            productKnowledge.setIsEnabled(1);
        }
        if (productKnowledge.getSort() == null) {
            productKnowledge.setSort(1);
        }
        String now = DateUtil.now();
        productKnowledge.setCreateTime(now);
        productKnowledge.setUpdateTime(now);
        productKnowledgeMapper.insert(productKnowledge);
    }

    public void updateById(ProductKnowledge productKnowledge) {
        if (ObjectUtil.isEmpty(productKnowledge.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(productKnowledge);
        com.smartore.goods.api.ProductVO product = loadProduct(productKnowledge.getProductId());
        if (ObjectUtil.isNull(product)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        productKnowledge.setUpdateTime(DateUtil.now());
        productKnowledgeMapper.updateById(productKnowledge);
    }

    /**
     * 把商品自己的详情描述和规格参数导入成知识资料。
     * 商品详情和规格参数本来只在前台展示，不进知识库就无法被向量检索到，
     * 导入后它们和人工维护的资料一样参与切片、向量化和相似度检索。
     */
    public int importFromProduct(Integer productId) {
        if (ObjectUtil.isEmpty(productId)) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        com.smartore.goods.api.ProductVO product = loadProduct(productId);
        if (ObjectUtil.isNull(product)) {
            throw new CustomException("5001", "商品不存在或已被删除");
        }

        // 先清掉这个商品上一次导入生成的资料（连同切片和向量），保证重复导入是覆盖而不是堆叠。
        // 人工维护（MANUAL）的资料不受影响，只删来源为商品详情的。
        ProductKnowledge condition = new ProductKnowledge();
        condition.setProductId(productId);
        condition.setSourceType(SOURCE_PRODUCT_DETAIL);
        List<ProductKnowledge> oldList = productKnowledgeMapper.selectAll(condition);
        for (ProductKnowledge old : oldList) {
            productKnowledgeEmbeddingMapper.deleteByKnowledgeId(old.getId());
            productKnowledgeChunkMapper.deleteByKnowledgeId(old.getId());
            productKnowledgeMapper.deleteById(old.getId());
        }

        int count = 0;
        int sort = 1;
        com.smartore.goods.api.KnowledgeSourceVO source = knowledgeSource(productId);
        com.smartore.goods.api.KnowledgeSourceVO detail = source;
        if (ObjectUtil.isNotNull(detail)) {
            // 商品介绍：回答"这个商品是什么、有什么特点"这类问题
            if (ObjectUtil.isNotEmpty(detail.getDetailContent())) {
                saveImported(product, "SELLING_POINT", product.getName() + " 商品介绍",
                        detail.getDetailContent(), sort++);
                count++;
            }
            // 包装清单：回答"盒子里有什么、含不含配件"这类问题
            if (ObjectUtil.isNotEmpty(detail.getPackageInfo())) {
                saveImported(product, "PARAMETER", product.getName() + " 包装清单",
                        detail.getPackageInfo(), sort++);
                count++;
            }
            // 售后说明：回答"保修多久、怎么退换"这类问题
            if (ObjectUtil.isNotEmpty(detail.getAfterSaleInfo())) {
                saveImported(product, "AFTER_SALE", product.getName() + " 售后说明",
                        detail.getAfterSaleInfo(), sort++);
                count++;
            }
        }

        List<com.smartore.goods.api.KnowledgeSourceVO.ParamEntry> params =
                source == null || source.getParams() == null ? List.of() : source.getParams();
        if (!params.isEmpty()) {
            // 规格参数按分组归并成一条条资料，一组一条，避免单条参数太短检索不出语义
            Map<String, StringBuilder> groupMap = new LinkedHashMap<>();
            for (com.smartore.goods.api.KnowledgeSourceVO.ParamEntry param : params) {
                String group = ObjectUtil.isEmpty(param.getParamGroup()) ? "基础参数" : param.getParamGroup();
                StringBuilder sb = groupMap.computeIfAbsent(group, key -> new StringBuilder());
                sb.append(param.getParamName()).append("：").append(param.getParamValue()).append("；");
            }
            for (Map.Entry<String, StringBuilder> entry : groupMap.entrySet()) {
                String content = product.getName() + " 的" + entry.getKey() + "如下。" + entry.getValue();
                saveImported(product, "PARAMETER", product.getName() + " " + entry.getKey(), content, sort++);
                count++;
            }
        }

        if (count == 0) {
            throw new CustomException("5003", "商品“" + product.getName() + "”还没有维护详情和规格参数，没有可导入的内容");
        }
        return count;
    }

    /**
     * 一键导入全部商品的详情和规格参数。
     * 逐个商品复用单个导入逻辑，某个商品没有可导入内容时跳过而不是整批失败。
     */
    public int importAll() {
        List<com.smartore.goods.api.ProductVO> products = unwrap(goodsClient.searchOnSale(null, null, 50));
        int count = 0;
        for (com.smartore.goods.api.ProductVO product : products) {
            try {
                count += importFromProduct(product.getId());
            } catch (CustomException e) {
                // 单个商品没维护详情和参数是正常情况，跳过它继续导入下一个
                continue;
            }
        }
        if (count == 0) {
            throw new CustomException("5004", "所有商品都还没有维护详情和规格参数，没有可导入的内容");
        }
        return count;
    }

    /**
     * 保存一条由商品详情或规格参数导入的知识资料。
     */
    private void saveImported(com.smartore.goods.api.ProductVO product, String knowledgeType, String title, String content, int sort) {
        ProductKnowledge knowledge = new ProductKnowledge();
        knowledge.setProductId(product.getId());
        knowledge.setKnowledgeType(knowledgeType);
        knowledge.setTitle(title);
        knowledge.setContent(content);
        // 来源标成商品详情，下次导入时据此判断哪些是自动生成的、可以安全覆盖
        knowledge.setSourceType(SOURCE_PRODUCT_DETAIL);
        knowledge.setIsEnabled(1);
        knowledge.setSort(sort);
        String now = DateUtil.now();
        knowledge.setCreateTime(now);
        knowledge.setUpdateTime(now);
        productKnowledgeMapper.insert(knowledge);
    }

    public void deleteById(Integer id) {
        productKnowledgeEmbeddingMapper.deleteByKnowledgeId(id);
        productKnowledgeChunkMapper.deleteByKnowledgeId(id);
        productKnowledgeMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            productKnowledgeEmbeddingMapper.deleteByKnowledgeId(id);
            productKnowledgeChunkMapper.deleteByKnowledgeId(id);
            productKnowledgeMapper.deleteById(id);
        }
    }

    public List<ProductKnowledge> selectAll(ProductKnowledge productKnowledge) {
        List<com.smartore.ai.entity.ProductKnowledge> __rows = productKnowledgeMapper.selectAll(productKnowledge);
        nameFillService.fillProducts(__rows, com.smartore.ai.entity.ProductKnowledge::getProductId, (row, p) -> {
            row.setProductName(p.getName());
            row.setProductNo(p.getProductNo());
            row.setCoverImage(p.getCoverImage());
        });
        return __rows;
    }

    public PageInfo<ProductKnowledge> selectPage(ProductKnowledge productKnowledge, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<ProductKnowledge> list = productKnowledgeMapper.selectAll(productKnowledge);
        return PageInfo.of(list);
    }

    private void validate(ProductKnowledge productKnowledge) {
        if (ObjectUtil.isEmpty(productKnowledge.getProductId())
                || ObjectUtil.isEmpty(productKnowledge.getKnowledgeType())
                || ObjectUtil.isEmpty(productKnowledge.getTitle())
                || ObjectUtil.isEmpty(productKnowledge.getContent())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
