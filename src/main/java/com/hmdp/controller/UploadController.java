package com.hmdp.controller;

import com.hmdp.business.*;
import com.hmdp.dto.Result;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/upload")
public class UploadController {
  @Resource private MediaService media;

  @PostMapping("/blog")
  public Result upload(@RequestParam("file") MultipartFile image) {
    return Result.ok(media.upload(image, BusinessStore.user()));
  }

  @DeleteMapping("/blog")
  public Result delete(@RequestParam("name") String path) {
    media.delete(path, BusinessStore.user());
    return Result.ok();
  }
}
