package com.collectorstools.repository;

import com.collectorstools.model.Debtor;
import com.collectorstools.util.DatabaseTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebtorRepositoryTest {

    private Connection conn;
    private DebtorRepository repository;

    @BeforeEach
    void setUp() {
        conn = DatabaseTestHelper.createTestConnection();
        repository = new DebtorRepository(conn);
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private Debtor debtor(String first, String last) {
        return Debtor.builder().firstName(first).lastName(last).build();
    }

    @Test
    void saveAssignsIdAndFindByIdReturnsIt() throws Exception {
        Debtor saved = repository.save(debtor("Jan", "Kowalski"));
        assertNotNull(saved.getId());

        Debtor found = repository.findById(saved.getId()).orElseThrow();
        assertEquals("Jan", found.getFirstName());
        assertEquals("Kowalski", found.getLastName());
        assertFalse(found.isUrgent());
    }

    @Test
    void findAllPutsUrgentFirstThenByName() throws Exception {
        repository.save(debtor("Anna", "Zielińska"));
        Debtor urgent = debtor("Piotr", "Nowak");
        urgent.setUrgent(true);
        repository.save(urgent);
        repository.save(debtor("Adam", "Adamski"));

        List<Debtor> all = repository.findAll();
        assertEquals(3, all.size());
        assertTrue(all.get(0).isUrgent());
        assertEquals("Nowak", all.get(0).getLastName());
    }

    @Test
    void updatePersistsChangesIncludingUrgent() throws Exception {
        Debtor saved = repository.save(debtor("Jan", "Kowalski"));
        saved.setCity("Lublin");
        saved.setUrgent(true);
        repository.update(saved);

        Debtor found = repository.findById(saved.getId()).orElseThrow();
        assertEquals("Lublin", found.getCity());
        assertTrue(found.isUrgent());
    }

    @Test
    void deleteRemovesDebtor() throws Exception {
        Debtor saved = repository.save(debtor("Jan", "Kowalski"));
        repository.delete(saved.getId());
        assertTrue(repository.findById(saved.getId()).isEmpty());
    }

    @Test
    void findByNameIsCaseInsensitive() throws Exception {
        repository.save(debtor("Jan", "Kowalski"));
        assertEquals(1, repository.findByName("kowal").size());
        assertEquals(0, repository.findByName("xyz").size());
    }

    @Test
    void findDistinctCitiesReturnsSortedNonEmpty() throws Exception {
        Debtor a = debtor("A", "A"); a.setCity("Lublin"); repository.save(a);
        Debtor b = debtor("B", "B"); b.setCity("Kraków"); repository.save(b);
        Debtor c = debtor("C", "C"); c.setCity("Lublin"); repository.save(c);
        Debtor d = debtor("D", "D"); d.setCity(""); repository.save(d);

        List<String> cities = repository.findDistinctCities();
        assertEquals(List.of("Kraków", "Lublin"), cities);
    }

    @Test
    void countReturnsNumberOfDebtors() throws Exception {
        assertEquals(0, repository.count());
        repository.save(debtor("Jan", "Kowalski"));
        repository.save(debtor("Anna", "Nowak"));
        assertEquals(2, repository.count());
    }
}
