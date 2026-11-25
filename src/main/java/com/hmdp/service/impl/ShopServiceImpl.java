package com.hmdp.service.impl;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.business.*;
import com.hmdp.utils.CacheClient;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper,Shop> implements IShopService {
 @Resource private CacheClient cache; @Resource private ShopQueryService queries; @Resource private CatalogService catalog;
 public Result queryById(Long id){Shop shop=cache.queryWithLogicalExpire("cache:shop:logical:",id,Shop.class,queries::entity,30L,TimeUnit.SECONDS);return shop==null?Result.fail("店铺不存在或已下架"):Result.ok(shop);}
 public Result update(Shop shop){return Result.ok(catalog.saveShop(shop));}
 public Result queryShopByType(Integer type,Integer page,Double x,Double y){return queryShopByType(type,page,x,y,5);}
 public Result queryShopByType(Integer type,Integer page,Double x,Double y,Integer size){return queries.list(null,type.longValue(),page,size,"default",x,y);}
}
