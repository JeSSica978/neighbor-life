package com.hmdp.business;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import org.springframework.stereotype.Component;

/** 启动时只核验、不自动迁移。缺结构就关闭新业务，避免半迁移时写入不完整订单。 */
@Component
public class BusinessSchema {
  @Resource private BusinessStore db;
  private boolean ready;

  @PostConstruct
  public void verify() {
    try {
      ready =
          db.count(
                      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('tb_business_meta','tb_admin','tb_shop_member','tb_shop_settings','tb_voucher_policy','tb_order_detail','tb_stock_ledger','tb_payment','tb_payment_notice','tb_user_voucher','tb_refund','tb_redemption','tb_favorite','tb_blog_like','tb_blog_visibility','tb_social_follow','tb_upload','tb_shop_review','tb_operation_audit','tb_reliable_event')")
                  == 20
              && db.count("SELECT COUNT(*) FROM tb_business_meta WHERE id=1 AND version>=6") == 1
              && db.count(
                      "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND column_name IN ('request_id','seckill_voucher_id')")
                  == 2
              && db.count(
                      "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND non_unique=0 AND index_name IN ('uk_voucher_order_request','uk_voucher_order_seckill')")
                  == 2
              && db.count(
                      "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='idx_order_status_create'")
                  == 2
              && db.count(
                      "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='tb_voucher_order' AND index_name='uk_voucher_order_user_voucher'")
                  == 0;
    } catch (Exception e) {
      ready = false;
    }
  }

  public boolean ready() {
    return ready;
  }

  public void require() {
    if (!ready) throw new BusinessException(503, "MIGRATION_REQUIRED", "业务升级尚未完成，请完成数据库迁移并重启后端");
  }
}
