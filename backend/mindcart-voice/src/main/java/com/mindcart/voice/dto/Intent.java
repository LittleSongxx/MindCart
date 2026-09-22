package com.mindcart.voice.dto;

public enum Intent {
    PRODUCT_RECOMMENDATION,   // 明确型商品需求
    CLARIFY_NEEDED,          // 模糊型需求
    PRODUCT_COMPARE,         // 商品对比
    CHITCHAT,                // 闲聊
    ORDER_CONFIRM,           // 下单确认
    ORDER_CANCEL,            // 暂缓/放弃购买（浏览中，非订单操作）
    OUT_OF_SCOPE             // 越权 / 异常
}