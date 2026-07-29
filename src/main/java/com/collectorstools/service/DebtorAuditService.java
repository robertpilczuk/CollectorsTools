package com.collectorstools.service;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.DebtorAudit;
import com.collectorstools.repository.DebtorAuditRepository;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

public class DebtorAuditService {

    private final DebtorAuditRepository auditRepository;

    public DebtorAuditService() {
        this.auditRepository = new DebtorAuditRepository();
    }

    public DebtorAuditService(DebtorAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public List<DebtorAudit> findByDebtorId(long debtorId) {
        try {
            return auditRepository.findByDebtorId(debtorId);
        } catch (SQLException e) {
            throw new RuntimeException("Błąd pobierania historii zmian: " + e.getMessage(), e);
        }
    }

    /**
     * Porównuje stan dłużnika przed i po edycji i zapisuje rekordy audytu dla zmienionych pól.
     */
    public void recordChanges(Debtor oldD, Debtor newD) {
        LocalDateTime now = LocalDateTime.now();
        compare(newD.getId(), now, "Imię", oldD.getFirstName(), newD.getFirstName());
        compare(newD.getId(), now, "Nazwisko", oldD.getLastName(), newD.getLastName());
        compare(newD.getId(), now, "PESEL", oldD.getPesel(), newD.getPesel());
        compare(newD.getId(), now, "Nr dowodu", oldD.getIdNumber(), newD.getIdNumber());
        compare(newD.getId(), now, "Telefon", oldD.getPhone(), newD.getPhone());
        compare(newD.getId(), now, "E-mail", oldD.getEmail(), newD.getEmail());
        compare(newD.getId(), now, "Ulica", oldD.getStreet(), newD.getStreet());
        compare(newD.getId(), now, "Miasto", oldD.getCity(), newD.getCity());
        compare(newD.getId(), now, "Kod pocztowy", oldD.getPostalCode(), newD.getPostalCode());
        compare(newD.getId(), now, "Notatki", oldD.getNotes(), newD.getNotes());
        compare(newD.getId(), now, "Pilne", oldD.isUrgent() ? "tak" : "nie", newD.isUrgent() ? "tak" : "nie");
    }

    private void compare(Long debtorId, LocalDateTime ts, String field, String oldValue, String newValue) {
        String o = oldValue == null ? "" : oldValue;
        String n = newValue == null ? "" : newValue;
        if (o.equals(n)) return;
        try {
            auditRepository.save(DebtorAudit.builder()
                    .debtorId(debtorId)
                    .changedAt(ts)
                    .fieldName(field)
                    .oldValue(o)
                    .newValue(n)
                    .build());
        } catch (SQLException e) {
            throw new RuntimeException("Błąd zapisu historii zmian: " + e.getMessage(), e);
        }
    }
}
