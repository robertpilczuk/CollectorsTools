package com.collectorstools.service;

import com.collectorstools.model.AuditEntity;
import com.collectorstools.model.AuditEntry;
import com.collectorstools.model.AuditOperation;
import com.collectorstools.repository.AuditLogRepository;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Zapis i odczyt dziennika audytowego.
 *
 * <p>Autor wpisu pobierany jest z {@link CurrentUser}, więc wołający nie musi
 * o nim pamiętać — a przy wprowadzeniu logowania nic w serwisach się nie zmieni.
 */
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService() {
        this.auditLogRepository = new AuditLogRepository();
    }

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Wariant bez zapisu, przeznaczony wyłącznie dla starszych konstruktorów serwisów
     * używanych w testach, które audytu nie sprawdzają.
     *
     * <p>Ścieżki produkcyjne (konstruktory bezargumentowe serwisów) zawsze wpinają
     * działającą instancję. Testy weryfikujące audyt tworzą serwisy z pełnym zestawem
     * zależności.
     */
    public static AuditService noOp() {
        return new AuditService((AuditLogRepository) null);
    }

    public boolean isEnabled() {
        return auditLogRepository != null;
    }

    /**
     * Odnotowuje operację.
     *
     * @param entityType rodzaj obiektu
     * @param entityId   identyfikator obiektu (może być {@code null} po usunięciu)
     * @param debtorId   dłużnik, którego dotyczy zdarzenie — pozwala pokazać jego pełną historię
     * @param operation  rodzaj operacji
     * @param fieldName  nazwa zmienionego pola albo {@code null}
     * @param oldValue   wartość przed zmianą
     * @param newValue   wartość po zmianie
     * @param reason     uzasadnienie podane przez użytkownika
     */
    public void record(AuditEntity entityType, Long entityId, Long debtorId,
                       AuditOperation operation, String fieldName,
                       String oldValue, String newValue, String reason) {
        if (!isEnabled()) {
            return;
        }
        AuditEntry entry = AuditEntry.builder()
                .occurredAt(LocalDateTime.now())
                .userId(CurrentUser.id())
                .entityType(entityType)
                .entityId(entityId)
                .debtorId(debtorId)
                .operation(operation)
                .fieldName(fieldName)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .build();
        try {
            auditLogRepository.save(entry);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd zapisu dziennika audytowego: " + e.getMessage(), e);
        }
    }

    /**
     * Skrót dla operacji bez porównania wartości — zamknięcia sprawy, wpłaty, usunięcia.
     */
    public void record(AuditEntity entityType, Long entityId, Long debtorId,
                       AuditOperation operation, String reason) {
        record(entityType, entityId, debtorId, operation, null, null, null, reason);
    }

    /**
     * Odnotowuje zmianę wartości tylko wtedy, gdy faktycznie nastąpiła.
     * Wartości puste i {@code null} są traktowane jednakowo.
     */
    public void recordChange(AuditEntity entityType, Long entityId, Long debtorId,
                             AuditOperation operation, String fieldName,
                             String oldValue, String newValue, String reason) {
        String before = oldValue == null ? "" : oldValue;
        String after = newValue == null ? "" : newValue;
        if (before.equals(after)) {
            return;
        }
        record(entityType, entityId, debtorId, operation, fieldName, before, after, reason);
    }

    public List<AuditEntry> findByEntity(AuditEntity type, long entityId) {
        if (!isEnabled()) return List.of();
        try {
            return auditLogRepository.findByEntity(type, entityId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd odczytu dziennika audytowego: " + e.getMessage(), e);
        }
    }

    public List<AuditEntry> findByDebtorId(long debtorId) {
        if (!isEnabled()) return List.of();
        try {
            return auditLogRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd odczytu dziennika audytowego: " + e.getMessage(), e);
        }
    }

    public List<AuditEntry> findRecent(int limit) {
        if (!isEnabled()) return List.of();
        try {
            return auditLogRepository.findRecent(limit);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd odczytu dziennika audytowego: " + e.getMessage(), e);
        }
    }
}
