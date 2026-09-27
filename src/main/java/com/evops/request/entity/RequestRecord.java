package com.evops.request.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 请求号台账：所有新增/变更请求必须携带 requestNo。
 * 唯一约束承担防重放与并发首笔判定。
 */
@Data
@TableName("t_request_record")
public class RequestRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestNo;
    private String bizType;
    private String bizKey;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
