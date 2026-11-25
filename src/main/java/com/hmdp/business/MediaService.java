package com.hmdp.business;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** 上传文件只接受可解码的 JPEG/PNG，重新编码后保存；客户端文件名不参与路径计算。 */
@Service
public class MediaService {
  @Resource private BusinessStore db;
  @Resource private BusinessSchema schema;

  @Value("${hmdp.media.directory:data/uploads}")
  private String directory;

  public Path root() {
    return Paths.get(directory).toAbsolutePath().normalize();
  }

  @Transactional
  public String upload(MultipartFile file, long user) {
    schema.require();
    if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024)
      throw new BusinessException(400, "INVALID_IMAGE", "图片须在 5 MB 以内");
    try (ImageInputStream input = ImageIO.createImageInputStream(file.getInputStream())) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext())
        throw new BusinessException(400, "INVALID_IMAGE", "请选择 JPEG 或 PNG 图片");
      ImageReader reader = readers.next();
      BufferedImage decoded;
      String format;
      try {
        reader.setInput(input);
        format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!(format.equals("png") || format.equals("jpeg"))
            || (long) reader.getWidth(0) * reader.getHeight(0) > 20000000)
          throw new BusinessException(400, "INVALID_IMAGE", "图片格式或尺寸不支持");
        decoded = reader.read(0);
      } finally {
        reader.dispose();
      }
      String id = UUID.randomUUID().toString();
      String name = id + (format.equals("png") ? ".png" : ".jpg");
      Files.createDirectories(root());
      Path target = root().resolve(name);
      if (!ImageIO.write(decoded, format, target.toFile()))
        throw new IOException("Image encoder unavailable");
      // 文件系统不参加 DB 事务；回滚时删除本次新文件，避免登记失败留下孤儿文件。
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED)
                try {
                  Files.deleteIfExists(target);
                } catch (IOException ignored) {
                }
            }
          });
      String path = "/media/" + name;
      db.jdbc.update("INSERT INTO tb_upload(id,user_id,path) VALUES(?,?,?)", id, user, path);
      return path;
    } catch (IOException e) {
      throw new BusinessException(400, "INVALID_IMAGE", "图片读取或保存失败");
    }
  }

  @Transactional
  public void delete(String path, long user) {
    schema.require();
    if (path == null || !path.matches("/media/[a-f0-9-]{36}\\.(png|jpg)"))
      throw new BusinessException(400, "INVALID_PATH", "图片路径不正确");
    Map<String, Object> upload =
        db.one("SELECT * FROM tb_upload WHERE path=? AND user_id=? FOR UPDATE", path, user);
    if (upload == null) throw new BusinessException(404, "IMAGE_NOT_FOUND", "图片不存在或无权删除");
    if (db.count("SELECT COUNT(*) FROM tb_blog WHERE FIND_IN_SET(?,images)>0", path) > 0
        || db.count("SELECT COUNT(*) FROM tb_shop WHERE FIND_IN_SET(?,images)>0", path) > 0
        || db.count("SELECT COUNT(*) FROM tb_user WHERE icon=?", path) > 0)
      throw new BusinessException(409, "IMAGE_IN_USE", "图片仍被资料或内容使用");
    Path target = root().resolve(path.substring(7)).normalize();
    if (!target.startsWith(root())) throw new BusinessException(400, "INVALID_PATH", "图片路径不正确");
    // 先删文件；失败保留登记记录，用户仍可重试。仅处理当前用户明确指定的一张图。
    try {
      Files.deleteIfExists(target);
    } catch (IOException e) {
      throw new BusinessException(503, "IMAGE_DELETE_FAILED", "图片暂时无法删除");
    }
    db.jdbc.update("DELETE FROM tb_upload WHERE id=?", upload.get("id"));
  }
}
