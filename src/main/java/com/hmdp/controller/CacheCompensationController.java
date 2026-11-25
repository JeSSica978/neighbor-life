package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.ReliableEvent;
import com.hmdp.mapper.ReliableEventMapper;
import com.hmdp.service.DemoShopAccess;
import com.hmdp.service.ReliableEventService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/demo")
public class CacheCompensationController {
    @Resource private DemoShopAccess access;
    @Resource private ReliableEventMapper events;

    @GetMapping("/shop/{id}/capabilities")
    public Result capabilities(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canEdit", access.allowed(id)); return Result.ok(result);
    }

    @GetMapping("/cache-events/{id}")
    public Result status(@PathVariable String id) {
        ReliableEvent event = events.selectById(id);
        if (!allowed(event)) return Result.fail("事件不存在或无权查看");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", event.getId()); result.put("status", event.getStatus());
        result.put("sendAttempts", event.getSendAttempts()); result.put("consumeAttempts", event.getConsumeAttempts());
        result.put("lastError", event.getLastError()); return Result.ok(result);
    }

    @PostMapping("/cache-events/{id}/retry")
    public Result retry(@PathVariable String id) {
        ReliableEvent event = events.selectById(id);
        if (!allowed(event)) return Result.fail("事件不存在或无权操作");
        if (events.requeue(id) != 1) return Result.fail("只有阻塞事件可以重新投递");
        return status(id);
    }
    private boolean allowed(ReliableEvent event) {
        return event != null && ReliableEventService.CACHE_DELETE.equals(event.getEventType()) && access.allowed(event.getResourceId());
    }
}
