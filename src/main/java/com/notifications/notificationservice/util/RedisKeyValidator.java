package com.notifications.notificationservice.util;

import java.util.regex.Pattern;

public final class RedisKeyValidator {

    private static final Pattern VALID_USER_ID = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private RedisKeyValidator() {}

    public static void validate(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("USER_ID_NULL");
        }
        if (userId.isEmpty()) {
            throw new IllegalArgumentException("USER_ID_EMPTY");
        }
        if (!VALID_USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("USER_ID_INVALID_FORMAT");
        }
    }
}
