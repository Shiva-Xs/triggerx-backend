package com.triggerx.config;

import java.util.List;

public final class PublicPaths {

    private PublicPaths() {}

    public static final List<String> PATTERNS = List.of(
            "/api/v1/auth/**",
            "/api/v1/symbols/**",
            "/api/v1/extension/redeem",
            "/api/v1/health",
            "/actuator/health"
    );
}
