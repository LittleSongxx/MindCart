package com.smartore.ai.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.smartore.ai.entity.AiModelConfig;
import com.smartore.ai.mapper.AiModelConfigMapper;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.common.util.CryptoUtils;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 模型配置。安全口径（相对单体版）：
 * 1. API Key 用 AES-GCM 加密落库（密钥只在环境变量/Nacos），明文不再进数据库；
 * 2. 任何查询出参一律脱敏为 sk-****末4位；调用模型时在进程内解密；
 * 3. 单类型（CHAT/EMBEDDING）只允许一个启用配置：启用前把同类型其他配置置为停用（原子约束防双启用）。
 */
@Service
public class AiModelConfigService {

    /** 落库密文前缀，用于区分"已加密"与"历史上明文遗留" */
    private static final String CIPHER_PREFIX = "enc:";

    @Resource
    private AiModelConfigMapper aiModelConfigMapper;

    @Value("${smartore.model.crypto-key:}")
    private String cryptoKey;

    /** 插入/更新 + 互斥禁用必须原子，否则并发可留下两条 enabled */
    @org.springframework.transaction.annotation.Transactional
    public void add(AiModelConfig config) {
        validate(config);
        if (config.getTemperature() == null) {
            config.setTemperature(new BigDecimal("0.70"));
        }
        if (config.getMaxTokens() == null) {
            config.setMaxTokens(2048);
        }
        String now = DateUtil.now();
        config.setCreateTime(now);
        config.setUpdateTime(now);
        config.setApiKey(encrypt(config.getApiKey()));
        if (config.getIsEnabled() != null && config.getIsEnabled() == 1) {
            disableOthers(config);
        }
        aiModelConfigMapper.insert(config);
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateById(AiModelConfig config) {
        if (ObjectUtil.isEmpty(config.getId())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        validate(config);
        // 前端回传的是脱敏占位（sk-****xxxx）时视为"未修改密钥"，保留库内密文
        AiModelConfig dbConfig = aiModelConfigMapper.selectById(config.getId());
        if (dbConfig == null) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR);
        }
        if (isMasked(config.getApiKey())) {
            config.setApiKey(dbConfig.getApiKey());
        } else {
            config.setApiKey(encrypt(config.getApiKey()));
        }
        config.setUpdateTime(DateUtil.now());
        if (config.getIsEnabled() != null && config.getIsEnabled() == 1) {
            disableOthers(config);
        }
        aiModelConfigMapper.updateById(config);
    }

    public void deleteById(Integer id) {
        aiModelConfigMapper.deleteById(id);
    }

    public void deleteBatch(List<Integer> ids) {
        for (Integer id : ids) {
            aiModelConfigMapper.deleteById(id);
        }
    }

    public List<AiModelConfig> selectAll(AiModelConfig condition) {
        return aiModelConfigMapper.selectAll(condition).stream().map(this::mask).toList();
    }

    public PageInfo<AiModelConfig> selectPage(AiModelConfig condition, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        return PageInfo.of(aiModelConfigMapper.selectAll(condition).stream().map(this::mask).toList());
    }

    /** 供 AiChatService 内部使用：解密后的真实配置（不出服务） */
    public AiModelConfig decryptIfNeeded(AiModelConfig config) {
        if (config != null && config.getApiKey() != null && config.getApiKey().startsWith(CIPHER_PREFIX)) {
            requireCryptoKey();
            config.setApiKey(CryptoUtils.decrypt(
                    config.getApiKey().substring(CIPHER_PREFIX.length()), cryptoKey));
        }
        return config;
    }

    /** 同类型互斥启用：单条条件 UPDATE（原子），替代"读-改-写"循环——
     *  循环版本并发启用可留下两条 enabled，模型选择（findEnabledConfig 取 id desc）变得不确定 */
    private void disableOthers(AiModelConfig config) {
        aiModelConfigMapper.disableOthersOfModelType(config.getModelType(), config.getId());
    }

    private String encrypt(String plainKey) {
        if (StrUtil.isBlank(plainKey) || plainKey.startsWith(CIPHER_PREFIX)) {
            return plainKey;
        }
        requireCryptoKey();
        return CIPHER_PREFIX + CryptoUtils.encrypt(plainKey, cryptoKey);
    }

    private boolean isMasked(String apiKey) {
        return apiKey != null && apiKey.startsWith("sk-****");
    }

    /** 掩码作用在库内值（enc: 密文）上——展示的是密文尾 4 位，真实 Key 尾 4 位不可逆。
     *  管理界面据此区分"已配置/未配置"，不能用于比对 Key 内容。 */
    private AiModelConfig mask(AiModelConfig config) {
        String key = config.getApiKey();
        if (StrUtil.isNotBlank(key)) {
            String tail = key.length() <= 4 ? "****" : key.substring(key.length() - 4);
            config.setApiKey("sk-****" + tail);
        }
        return config;
    }

    private void requireCryptoKey() {
        if (StrUtil.isBlank(cryptoKey)) {
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR,
                    "未配置 smartore.model.crypto-key，无法加密/解密模型密钥（runtime.env 生成后注入）");
        }
    }

    private void validate(AiModelConfig config) {
        if (ObjectUtil.isEmpty(config.getProvider())
                || ObjectUtil.isEmpty(config.getModelType())
                || ObjectUtil.isEmpty(config.getModelName())
                || ObjectUtil.isEmpty(config.getBaseUrl())
                || ObjectUtil.isEmpty(config.getApiKey())) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
    }
}
