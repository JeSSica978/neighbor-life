package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.ReliableEvent;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ReliableEventMapper extends BaseMapper<ReliableEvent> {
    @Select("SELECT * FROM tb_reliable_event WHERE id=#{id} FOR UPDATE")
    ReliableEvent lock(@Param("id") String id);

    @Select("SELECT * FROM tb_reliable_event WHERE (status=0 AND next_attempt_at<=CURRENT_TIMESTAMP) " +
            "OR (status=1 AND update_time<TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP)) " +
            "ORDER BY create_time,id LIMIT 20")
    List<ReliableEvent> dispatchable();

    @Update("UPDATE tb_reliable_event SET status=1,send_attempts=send_attempts+1,update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{id} AND status IN (0,1)")
    int sent(@Param("id") String id);

    @Update("UPDATE tb_reliable_event SET status=2,last_error=NULL,update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{id} AND status IN (0,1)")
    int completed(@Param("id") String id);

    @Update("UPDATE tb_reliable_event SET status=0,send_attempts=send_attempts+1,last_error=#{error}," +
            "next_attempt_at=TIMESTAMPADD(SECOND,LEAST(30,send_attempts+1),CURRENT_TIMESTAMP) " +
            "WHERE id=#{id} AND status IN (0,1)")
    int sendFailed(@Param("id") String id, @Param("error") String error);

    @Update("UPDATE tb_reliable_event SET consume_attempts=consume_attempts+1," +
            "status=IF(consume_attempts>=#{maxAttempts},3,status),last_error=#{error} " +
            "WHERE id=#{id} AND status IN (0,1)")
    int consumeFailed(@Param("id") String id, @Param("maxAttempts") int maxAttempts, @Param("error") String error);

    @Update("UPDATE tb_reliable_event SET status=0,consume_attempts=0,last_error=NULL," +
            "next_attempt_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND status=3")
    int requeue(@Param("id") String id);
}
