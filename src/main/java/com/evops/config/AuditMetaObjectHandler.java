package com.evops.config;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充。当前还没有真实用户体系，操作人统一记 0（system）；
 * 接入登录后从登录上下文取用户 ID 即可。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final Long SYSTEM_USER = 0L;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        fillIfPresent("createTime", now, metaObject);
        fillIfPresent("updateTime", now, metaObject);
        fillIfPresent("createBy", SYSTEM_USER, metaObject);
        fillIfPresent("updateBy", SYSTEM_USER, metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        fillIfPresent("updateTime", LocalDateTime.now(), metaObject);
        fillIfPresent("updateBy", SYSTEM_USER, metaObject);
    }

    private void fillIfPresent(String field, Object value, MetaObject metaObject) {
        if (metaObject.hasSetter(field) && getFieldValByName(field, metaObject) == null) {
            setFieldValByName(field, value, metaObject);
        }
    }
}
