package com.triggerx.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse(String error, String message, Integer attemptsRemaining) {}
