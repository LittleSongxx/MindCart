package com.mindcart.user.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mindcart.common.context.UserContext;
import com.mindcart.common.exception.CustomException;
import com.mindcart.common.result.Result;
import com.mindcart.common.result.ResultCodeEnum;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Set;

/**
 * 文件上传/下载。安全口径（相对单体版）：
 * 1. 上传必须登录（网关侧白名单只放行 /files/download/**）；
 * 2. 类型白名单 + 5MB 上限（spring.servlet.multipart 同步限制）；
 * 3. 落盘文件名完全服务端生成（时间戳+随机），原文件名不参与路径拼接；
 * 4. 下载只按白名单目录内文件名取文件，拒绝路径穿越。
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${mindcart.file.dir:./files}")
    private String fileDir;

    @Value("${mindcart.file.base-url:}")
    private String fileBaseUrl;

    @PostMapping("/upload")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        UserContext.requireUserId();
        if (file == null || file.isEmpty()) {
            throw new CustomException(ResultCodeEnum.PARAM_LOST_ERROR);
        }
        String original = file.getOriginalFilename();
        String extension = StrUtil.subAfter(StrUtil.nullToEmpty(original), ".", true).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CustomException(ResultCodeEnum.PARAM_ERROR, "仅支持图片格式：" + ALLOWED_EXTENSIONS);
        }
        try {
            FileUtil.mkdir(fileDir);
            String fileName = System.currentTimeMillis() + "-" + randomSuffix() + "." + extension;
            FileUtil.writeBytes(file.getBytes(), fileDir + "/" + fileName);
            return Result.success(fileBaseUrl + "/files/download/" + fileName);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            throw new CustomException(ResultCodeEnum.SYSTEM_ERROR);
        }
    }

    @GetMapping("/download/{fileName}")
    public void download(@PathVariable String fileName, HttpServletResponse response) {
        // 只允许纯文件名，杜绝 ../ 路径穿越
        if (StrUtil.isBlank(fileName) || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        try {
            if (!FileUtil.exist(fileDir + "/" + fileName)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.addHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
            response.setContentType("application/octet-stream");
            byte[] bytes = FileUtil.readBytes(fileDir + "/" + fileName);
            try (OutputStream os = response.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
        } catch (Exception e) {
            log.warn("文件下载失败：{}", fileName);
        }
    }

    private String randomSuffix() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
