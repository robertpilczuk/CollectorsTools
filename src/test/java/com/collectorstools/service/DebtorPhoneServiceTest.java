package com.collectorstools.service;

import com.collectorstools.model.Debtor;
import com.collectorstools.model.DebtorPhone;
import com.collectorstools.repository.DebtorPhoneRepository;
import com.collectorstools.repository.DebtorRepository;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebtorPhoneServiceTest {

    private Connection conn;
    private DebtorPhoneService service;
    private DebtorRepository debtorRepository;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        service = new DebtorPhoneService(new DebtorPhoneRepository(conn));
        debtorRepository = new DebtorRepository(conn);
        debtorId = debtorRepository.save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private DebtorPhone phone(String number, boolean primary) {
        return DebtorPhone.builder()
                .debtorId(debtorId)
                .phoneNumber(number)
                .primary(primary)
                .source("IMPORT")
                .build();
    }

    @Test
    @DisplayName("Numer podstawowy jest zwracany jako pierwszy")
    void primaryPhoneComesFirst() {
        service.save(phone("662701256", false));
        service.save(phone("817442336", true));
        service.save(phone("537080020", false));

        List<DebtorPhone> phones = service.findByDebtorId(debtorId);

        assertEquals(3, phones.size());
        assertEquals("817442336", phones.get(0).getPhoneNumber());
        assertTrue(phones.get(0).isPrimary());
    }

    @Test
    @DisplayName("Ten sam numer nie jest dodawany dwa razy")
    void doesNotDuplicateSameNumber() {
        assertTrue(service.addIfAbsent(phone("662701256", true)));
        assertFalse(service.addIfAbsent(phone("662701256", false)),
                "Powtórzony numer przy kolejnej sprawie tego samego dłużnika nie jest błędem");

        assertEquals(1, service.findByDebtorId(debtorId).size());
    }

    @Test
    @DisplayName("Wyszukiwanie po numerze ignoruje spacje, myślniki i prefiks kierunkowy")
    void searchIgnoresFormatting() {
        service.save(phone("662701256", true));

        assertEquals(Set.of(debtorId), service.findDebtorIdsByPhone("662701256"));
        assertEquals(Set.of(debtorId), service.findDebtorIdsByPhone("662 701 256"));
        assertEquals(Set.of(debtorId), service.findDebtorIdsByPhone("662-701-256"));
        assertEquals(Set.of(debtorId), service.findDebtorIdsByPhone("+48662701256"));
        assertEquals(Set.of(debtorId), service.findDebtorIdsByPhone("701256"),
                "Fragment numeru też powinien znaleźć dłużnika");
    }

    @Test
    @DisplayName("Numer bez dłużnika lub bez treści jest odrzucany")
    void validatesRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(DebtorPhone.builder().phoneNumber("600100200").build()));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(DebtorPhone.builder().debtorId(debtorId).phoneNumber("  ").build()));
    }

    @Test
    @DisplayName("Usunięcie dłużnika kasuje jego numery")
    void deletingDebtorRemovesPhones() throws Exception {
        service.save(phone("662701256", true));
        assertEquals(1, service.findByDebtorId(debtorId).size());

        debtorRepository.delete(debtorId);

        assertEquals(0, service.findByDebtorId(debtorId).size());
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM debtor_phones")) {
            assertEquals(0, rs.next() ? rs.getInt(1) : -1);
        }
    }
}
