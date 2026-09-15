package com.smartore.goods.service;

import cn.hutool.core.util.StrUtil;
import com.smartore.common.exception.CustomException;
import com.smartore.common.result.ResultCodeEnum;
import com.smartore.goods.api.StockOpRequest;
import com.smartore.goods.entity.StockChangeRecord;
import com.smartore.goods.mapper.StockChangeRecordMapper;
import com.smartore.goods.mapper.StockMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 库存 Saga 步骤（仅交易域经 Feign 调用）。
 *
 * 幂等实现与钱包一致：先 INSERT IGNORE 变动流水（唯一键 biz_no+product_id+change_type），
 * 插入成功才动库存；整批在一次本地事务里，任一行库存不足则整批回滚（本服务内原子）。
 * 扣减用条件 UPDATE（available >= qty）防超卖，不再"先查后写"。
 */
@Service
public class StockSagaService {

    @Resource
    private StockMapper stockMapper;
    @Resource
    private StockChangeRecordMapper stockChangeRecordMapper;

    @Transactional
    public void deductForOrder(StockOpRequest request) {
        validate(request);
        for (StockOpRequest.StockItem item : request.getItems()) {
            if (insertRecordIgnore(request.getOrderNo(), item.getProductId(),
                    StockChangeRecord.TYPE_DEDUCT, item.getQuantity())) {
                int rows = stockMapper.deductAvailable(item.getProductId(), item.getQuantity());
                if (rows == 0) {
                    throw new CustomException(ResultCodeEnum.STOCK_NOT_ENOUGH,
                            "商品库存不足（productId=" + item.getProductId() + "）");
                }
                stockMapper.syncProductDisplay(item.getProductId());
            }
        }
    }

    @Transactional
    public void restoreForOrder(StockOpRequest request) {
        validate(request);
        List<StockChangeRecord> deducted = stockChangeRecordMapper.selectByBizNoAndType(
                request.getOrderNo(), StockChangeRecord.TYPE_DEDUCT);
        if (deducted.isEmpty()) {
            // 该订单从未扣过库存（例如下单失败补偿），回补本身就是空操作
            return;
        }
        for (StockChangeRecord record : deducted) {
            if (insertRecordIgnore(request.getOrderNo(), record.getProductId(),
                    StockChangeRecord.TYPE_RESTORE, record.getQuantity())) {
                stockMapper.increaseAvailable(record.getProductId(), record.getQuantity());
                stockMapper.syncProductDisplay(record.getProductId());
            }
        }
    }

    private boolean insertRecordIgnore(String orderNo, Integer productId, String type, Integer quantity) {
        StockChangeRecord record = new StockChangeRecord();
        record.setBizNo(orderNo);
        record.setProductId(productId);
        record.setChangeType(type);
        record.setQuantity(quantity);
        return stockChangeRecordMapper.insertIgnore(record) > 0;
    }

    private void validate(StockOpRequest request) {
        if (request == null || StrUtil.isBlank(request.getOrderNo())
                || request.getItems() == null || request.getItems().isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        for (StockOpRequest.StockItem item : request.getItems()) {
            if (item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new CustomException(ResultCodeEnum.PARAM_ERROR);
            }
        }
    }
}
