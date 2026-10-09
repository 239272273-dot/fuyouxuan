package com.fuyouxuan.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = System.getenv().getOrDefault(
            "FUYOUXUAN_IMAGE_DIR", System.getProperty("java.io.tmpdir") + "fuyouxuan-uploads");
    public static final String USER_NICK_NAME_PREFIX = "福优员工_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
