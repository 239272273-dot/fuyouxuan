package com.fuyouxuan.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fuyouxuan.dto.Result;
import com.fuyouxuan.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final long MAX_IMAGE_SIZE = 5L * 1024 * 1024;
    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "image/jpeg", "image/png", "image/webp", "image/gif");
    private static final List<String> ALLOWED_SUFFIXES = Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif");

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("请选择要上传的图片");
        }
        if (image.getSize() > MAX_IMAGE_SIZE) {
            return Result.fail("单张图片不能超过5MB");
        }

        String originalFilename = image.getOriginalFilename() == null ? "" : image.getOriginalFilename();
        String suffix = StrUtil.subAfter(originalFilename, ".", true).toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(image.getContentType()) || !ALLOWED_SUFFIXES.contains(suffix)) {
            return Result.fail("仅支持JPG、PNG、WebP或GIF图片");
        }

        try {
            // 生成新文件名
            String fileName = createNewFileName(suffix);
            // 保存文件
            File target = new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName.substring(1));
            image.transferTo(target);
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        Path uploadRoot = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR).toAbsolutePath().normalize();
        String relativeName = filename == null ? "" : filename.replaceFirst("^[\\\\/]+", "");
        Path imagePath = uploadRoot.resolve(relativeName).normalize();
        if (!imagePath.startsWith(uploadRoot) || Files.isDirectory(imagePath)) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(imagePath.toFile());
        return Result.ok();
    }

    private String createNewFileName(String suffix) throws IOException {
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("blogs/{}/{}", d1, d2));
        Files.createDirectories(dir.toPath());
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
