package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tb_reliable_event")
public class ReliableEvent {
    @TableId(type = IdType.INPUT) private String id;
    private String eventType;
    private Long resourceId;
    private Integer status;
    private Integer sendAttempts;
    private Integer consumeAttempts;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
