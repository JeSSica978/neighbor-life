package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {
    @Select("SELECT * FROM tb_voucher_order WHERE user_id=#{userId} AND request_id=#{requestId}")
    VoucherOrder normalRequest(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Insert("INSERT INTO tb_voucher_order(id,user_id,voucher_id,status,request_id) " +
            "VALUES(#{order.id},#{order.userId},#{order.voucherId},1,#{requestId})")
    int insertNormal(@Param("order") VoucherOrder order, @Param("requestId") String requestId);

    @Insert("INSERT INTO tb_voucher_order(id,user_id,voucher_id,status,seckill_voucher_id) " +
            "VALUES(#{order.id},#{order.userId},#{order.voucherId},1,#{order.voucherId})")
    int insertSeckill(@Param("order") VoucherOrder order);

    @Select("SELECT * FROM tb_voucher_order WHERE id=#{id} FOR UPDATE")
    VoucherOrder current(@Param("id") Long id);

    @Update("UPDATE tb_voucher_order SET status=2,pay_type=0,pay_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{id} AND user_id=#{userId} AND status=1 " +
            "AND create_time>TIMESTAMPADD(SECOND,-#{timeout},CURRENT_TIMESTAMP)")
    int pay(@Param("id") Long id, @Param("userId") Long userId, @Param("timeout") long timeout);

    @Update("<script>UPDATE tb_voucher_order SET status=4,update_time=CURRENT_TIMESTAMP " +
            "WHERE id=#{id} AND status=1 " +
            "<if test='expiredOnly'>AND create_time&lt;=TIMESTAMPADD(SECOND,-#{timeout},CURRENT_TIMESTAMP)</if></script>")
    int close(@Param("id") Long id, @Param("expiredOnly") boolean expiredOnly, @Param("timeout") long timeout);

    @Select("SELECT id FROM tb_voucher_order WHERE status=1 " +
            "AND create_time<=TIMESTAMPADD(SECOND,-#{timeout},CURRENT_TIMESTAMP) ORDER BY create_time,id LIMIT #{batch}")
    List<Long> expired(@Param("timeout") long timeout, @Param("batch") int batch);

    @Select("SELECT GREATEST(TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP,TIMESTAMPADD(SECOND,#{timeout},create_time)),0) " +
            "FROM tb_voucher_order WHERE id=#{id}")
    Long remainingSeconds(@Param("id") Long id, @Param("timeout") long timeout);
}
