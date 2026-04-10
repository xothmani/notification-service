package com.notifications.notificationservice.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisKeyValidatorTest {

    // ------------------------------------------------------------------ null

    @Test
    void validate_nullUserId_throwsUserIdNull() {
        assertThatThrownBy(() -> RedisKeyValidator.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USER_ID_NULL");
    }

    // ----------------------------------------------------------------- empty

    @Test
    void validate_emptyUserId_throwsUserIdEmpty() {
        assertThatThrownBy(() -> RedisKeyValidator.validate(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USER_ID_EMPTY");
    }

    // -------------------------------------------------------- invalid format

    @ParameterizedTest
    @ValueSource(strings = {
            "user:with:colons",
            "user with spaces",
            "user*wildcard",
            "user[bracket",
            "user.dot",
            "user@at",
            "user/slash"
    })
    void validate_invalidFormat_throwsUserIdInvalidFormat(String userId) {
        assertThatThrownBy(() -> RedisKeyValidator.validate(userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("USER_ID_INVALID_FORMAT");
    }

    // ---------------------------------------------------------------- valid

    @ParameterizedTest
    @ValueSource(strings = {
            "user123",
            "user_name",
            "user-name",
            "ABC123",
            "a",
            "User_123-abc",
            "UPPER_LOWER-123"
    })
    void validate_validUserId_doesNotThrow(String userId) {
        assertThatNoException().isThrownBy(() -> RedisKeyValidator.validate(userId));
    }
}
