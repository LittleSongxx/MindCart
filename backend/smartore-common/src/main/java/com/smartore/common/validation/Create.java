package com.smartore.common.validation;

/**
 * Bean Validation 分组：新增场景。
 * 与 Update 分组配合，让「新增必填、更新选填」的字段约束可以分开声明——
 * 更新接口若沿用新增的必填约束，前端的部分更新（只提交改动字段）会被直接拒绝。
 */
public interface Create {
}
