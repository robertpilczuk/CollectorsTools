package com.collectorstools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Wpis dziennika audytowego: kto, kiedy, na czym i jaka zmiana.
 *
 * <p>Wpis jest niezależny od obiektu, którego dotyczy — przeżywa jego usunięcie,
 * bo ślad po skasowaniu danych jest tym, którego szuka się najczęściej.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEntry {

    private Long id;
    private LocalDateTime occurredAt;
    private String userId;

    private AuditEntity entityType;
    private Long entityId;
    private Long debtorId;      // Denormalizacja — pełna historia dłużnika jednym zapytaniem

    private AuditOperation operation;
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String reason;

    /**
     * Zwięzły opis zmiany do wyświetlenia w tabeli.
     */
    public String describeChange() {
        if (fieldName == null && oldValue == null && newValue == null) {
            return reason != null ? reason : "";
        }
        if (oldValue == null || oldValue.isBlank()) {
            return newValue != null ? newValue : "";
        }
        if (newValue == null || newValue.isBlank()) {
            return oldValue;
        }
        return oldValue + " → " + newValue;
    }
}
