package com.collectorstools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtorAudit {

    private Long id;
    private Long debtorId;
    private LocalDateTime changedAt;
    private String fieldName;
    private String oldValue;
    private String newValue;
}
