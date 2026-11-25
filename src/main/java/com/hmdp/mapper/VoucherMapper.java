package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    @Select("SELECT * FROM tb_voucher WHERE id = #{voucherId} FOR UPDATE")
    Voucher selectForUpdate(@Param("voucherId") Long voucherId);

    // 查询秒杀优惠券,where type = 1
    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
