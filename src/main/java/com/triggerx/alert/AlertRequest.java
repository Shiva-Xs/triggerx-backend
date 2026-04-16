package com.triggerx.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AlertRequest(

        @NotBlank(message = "symbol is required")
        @Size(max = 20, message = "symbol must be 20 characters or fewer")
        @Pattern(regexp = "^[A-Za-z0-9]+$", message = "symbol must contain only letters and digits")
        String symbol,

        @NotNull(message = "targetPrice is required")
        @DecimalMin(value = "0.00000001", message = "targetPrice must be greater than zero")
        @Digits(integer = 11, fraction = 8,
                message = "targetPrice must have at most 11 integer digits and 8 decimal places")
        BigDecimal targetPrice,

        @NotNull(message = "condition is required — ABOVE, BELOW, or CROSSES")
        AlertCondition condition

) {}
