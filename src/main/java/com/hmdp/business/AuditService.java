package com.hmdp.business;

import java.util.UUID;
import javax.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  @Resource private BusinessStore db;

  public void record(String action, String resource) {
    db.jdbc.update(
        "INSERT INTO tb_operation_audit(id,operator_id,action,resource_id) VALUES(?,?,?,?)",
        UUID.randomUUID().toString(),
        BusinessStore.user(),
        action,
        resource);
  }
}
