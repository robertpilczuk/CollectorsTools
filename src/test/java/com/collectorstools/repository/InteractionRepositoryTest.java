package com.collectorstools.repository;

import com.collectorstools.model.ContactType;
import com.collectorstools.model.Debtor;
import com.collectorstools.model.Interaction;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class InteractionRepositoryTest {

    private Connection conn;
    private InteractionRepository repository;
    private long debtorId;

    @BeforeEach
    void setUp() throws Exception {
        conn = DatabaseTestHelper.createTestConnection();
        repository = new InteractionRepository(conn);
        debtorId = new DebtorRepository(conn).save(
                Debtor.builder().firstName("Jan").lastName("Kowalski").build()).getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Interaction interaction(ContactType type, String notes) {
        return Interaction.builder()
                .debtorId(debtorId)
                .interactionDate(LocalDate.now())
                .contactType(type)
                .notes(notes)
                .build();
    }

    @Test
    void saveAndCountByDebtorId() throws Exception {
        repository.save(interaction(ContactType.TELEFON, "rozmowa"));
        repository.save(interaction(ContactType.WIZYTA, "wizyta"));
        assertEquals(2, repository.countByDebtorId(debtorId));
    }

    @Test
    void findByDebtorIdReturnsSaved() throws Exception {
        repository.save(interaction(ContactType.PISMO, "wezwanie"));
        assertEquals(1, repository.findByDebtorId(debtorId).size());
        assertEquals(ContactType.PISMO, repository.findByDebtorId(debtorId).get(0).getContactType());
    }

    @Test
    void deleteRemovesInteraction() throws Exception {
        Interaction saved = repository.save(interaction(ContactType.EMAIL, "mail"));
        repository.delete(saved.getId());
        assertEquals(0, repository.countByDebtorId(debtorId));
    }
}
