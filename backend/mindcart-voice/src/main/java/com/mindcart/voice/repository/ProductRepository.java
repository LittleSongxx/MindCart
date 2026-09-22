package com.mindcart.voice.repository;

import com.mindcart.voice.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * voice_product 是 MindCart 商品目录的同步副本，只读使用。
 * 库存扣减/下单在 trade 服务，本仓库不提供任何写业务事实的入口。
 * （原 jc 的 decrementStock 与 merchant scope 过滤随本地电商域一并移除。）
 */
public interface ProductRepository extends JpaRepository<ProductEntity, Long> {

    List<ProductEntity> findByStatus(String status);

    List<ProductEntity> findByIdIn(List<Long> ids);
}
