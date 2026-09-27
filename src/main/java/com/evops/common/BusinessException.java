package com.evops.common;

/** 业务规则异常，统一由 GlobalExceptionHandler 转成 ApiResponse.fail。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
