package com.smartore.goods.api;

import java.math.BigDecimal;

/**
 * 语音服务目录同步专用视图：在 ProductVO 基础上带上类目/品牌名称
 * （voice 服务的检索与向量化文本需要名称而非 id）。
 * 事实源仍是 goods 服务，消费方只读。
 */
public class ProductSyncVO {
    private Integer id;
    private String name;
    private String coverImage;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String tags;
    private String sellingPoint;
    private String status;
    private Integer stockQuantity;
    private Integer categoryId;
    private String categoryName;
    private Integer brandId;
    private String brandName;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getSellingPoint() { return sellingPoint; }
    public void setSellingPoint(String sellingPoint) { this.sellingPoint = sellingPoint; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(Integer stockQuantity) { this.stockQuantity = stockQuantity; }
    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Integer getBrandId() { return brandId; }
    public void setBrandId(Integer brandId) { this.brandId = brandId; }
    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }
}
